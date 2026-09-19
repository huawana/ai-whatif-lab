package com.whatif.lab.simulation.behavior;

import com.fasterxml.jackson.core.type.TypeReference;
import com.whatif.lab.common.JsonCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * 特征集配置加载器 —— 从「内置配置」+「外部目录」读 {@link FeatureSetSpec}。
 *
 * <p>与 {@code DomainSpecLoader} 完全同一套模式（这是刻意的：一个平台里两处配置机制应该长得一样）：
 * <ul>
 *   <li><b>内置</b>：classpath {@code features/*.json}，随 jar 分发，例如 {@code retail-default.json}
 *       —— 它必须与改造前写死在代码里的 7 个特征<b>逐位等价</b>（有验收脚本守）；</li>
 *   <li><b>外部</b>：{@code whatif.features.dir}（默认 {@code features}），使用者换特征的地方。
 *       丢文件 + 重启即生效，不改代码、不重新打包；同名覆盖内置。</li>
 * </ul>
 *
 * <p><b>严格校验（宽松的 JSON 解析对配置文件有害）</b>：全局 ObjectMapper 配了
 * {@code FAIL_ON_UNKNOWN_PROPERTIES=false}，所以把 {@code "transform"} 敲成 {@code "transfrom"}
 * 会被静默忽略 → 配置看着生效、行为却是默认值。这里额外做"已知键"校验 + 词汇表校验
 * （未知类型/未知变换/重名特征都要报出来），坏配置只跳过自己，不拖垮其它特征集。
 */
@Component
public class FeatureSetLoader {

    private static final Logger log = LoggerFactory.getLogger(FeatureSetLoader.class);

    private static final Set<String> TOP_KEYS = Set.of("featureSet", "description", "features");
    private static final Set<String> DEF_KEYS =
            Set.of("name", "type", "transform", "halfLifeDays", "epsilon");

    private final JsonCodec json;
    private final String externalDir;

    public FeatureSetLoader(JsonCodec json,
                            @Value("${whatif.features.dir:features}") String externalDir) {
        this.json = json;
        this.externalDir = externalDir;
    }

    public record Loaded(List<FeatureSetSpec> specs, Map<String, String> problems) {
    }

    public Loaded load() {
        Map<String, FeatureSetSpec> byName = new LinkedHashMap<>();
        Map<String, String> problems = new LinkedHashMap<>();

        List<String> builtin = classpathLocations();
        for (String location : builtin) {
            readOne(location, byName, problems);
        }
        Path dir = Path.of(externalDir);
        if (Files.isDirectory(dir)) {
            List<Path> files = new ArrayList<>();
            try (Stream<Path> s = Files.list(dir)) {
                s.filter(p -> p.getFileName().toString().toLowerCase().endsWith(".json"))
                        .sorted()
                        .forEach(files::add);
            } catch (Exception e) {
                problems.put(externalDir, "读取外部配置目录失败: " + e.getMessage());
            }
            for (Path p : files) {
                readOne(p.toString(), byName, problems);
            }
            log.info("特征集配置：内置 {} 份 + 外部目录 {} 份（{}），生效特征集 {}",
                    builtin.size(), files.size(), externalDir, byName.keySet());
        } else {
            log.info("特征集配置：外部目录 {} 不存在（可选；放 *.json 进去即可新增特征集），内置 {} 份，生效 {}",
                    externalDir, builtin.size(), byName.keySet());
        }
        if (!problems.isEmpty()) {
            problems.forEach((k, v) -> log.error("特征集配置有问题（已跳过该文件）：{} —— {}", k, v));
        }
        return new Loaded(List.copyOf(byName.values()), problems);
    }

    private List<String> classpathLocations() {
        try {
            Resource[] resources = new PathMatchingResourcePatternResolver()
                    .getResources("classpath*:features/*.json");
            List<String> out = new ArrayList<>();
            for (Resource r : resources) {
                out.add(r.getURL().toString());
            }
            out.sort(String::compareTo);
            return out;
        } catch (Exception e) {
            log.error("读取内置特征集失败: {}", e.getMessage());
            return List.of();
        }
    }

    private void readOne(String location, Map<String, FeatureSetSpec> byName, Map<String, String> problems) {
        String label = shortName(location);
        try {
            String text = read(location);
            Map<String, Object> raw = json.read(text, new TypeReference<Map<String, Object>>() {
            });
            List<String> errors = new ArrayList<>();
            checkUnknownKeys(raw, TOP_KEYS, "", errors);
            for (Object o : list(raw.get("features"))) {
                if (o instanceof Map<?, ?> m) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> mm = (Map<String, Object>) m;
                    checkUnknownKeys(mm, DEF_KEYS, "features[]", errors);
                } else {
                    errors.add("features[] 的每一项都必须是对象");
                }
            }
            FeatureSetSpec spec;
            try {
                spec = json.read(text, FeatureSetSpec.class);
            } catch (Exception e) {
                problems.put(label, "解析失败: " + e.getMessage());
                return;
            }
            errors.addAll(validate(spec));
            if (!errors.isEmpty()) {
                problems.put(label, "配置校验不通过 —— " + String.join("；", errors));
                return;
            }
            byName.put(spec.featureSet().toLowerCase(), spec);
        } catch (Exception e) {
            problems.put(label, "读取失败: " + e.getMessage());
        }
    }

    /** 词汇表校验：未知类型/未知变换/空特征集/重名。 */
    private List<String> validate(FeatureSetSpec spec) {
        List<String> errors = new ArrayList<>();
        if (spec.featureSet() == null || spec.featureSet().isBlank()) {
            errors.add("featureSet 不能为空");
        }
        if (spec.features().isEmpty()) {
            errors.add("features 不能为空（至少要有一个特征）");
        }
        Set<String> seen = new HashSet<>();
        int i = 0;
        for (FeatureSetSpec.FeatureDef d : spec.features()) {
            String where = "features[" + i + "]";
            if (d.name() == null || d.name().isBlank()) {
                errors.add(where + ".name 不能为空");
            } else if (!seen.add(d.name())) {
                errors.add(where + ".name 重复: " + d.name());
            }
            if (d.type() == null || !FeatureSetSpec.TYPE_SCOPE.containsKey(d.type())) {
                errors.add(where + ".type 未知: " + d.type()
                        + "（可用: " + FeatureSetSpec.TYPE_SCOPE.keySet() + "）");
            }
            if (d.transform() == null || !FeatureSetSpec.ALLOWED_TRANSFORMS.contains(d.transform())) {
                errors.add(where + ".transform 未知: " + d.transform()
                        + "（可用: " + FeatureSetSpec.ALLOWED_TRANSFORMS + "）");
            }
            i++;
        }
        return errors;
    }

    private String read(String location) throws Exception {
        if (location.startsWith("file:") || location.startsWith("jar:")) {
            try (InputStream in = new PathMatchingResourcePatternResolver().getResource(location).getInputStream()) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        }
        return Files.readString(Path.of(location), StandardCharsets.UTF_8);
    }

    private static String shortName(String location) {
        int slash = Math.max(location.lastIndexOf('/'), location.lastIndexOf('\\'));
        return slash < 0 ? location : location.substring(slash + 1);
    }

    @SuppressWarnings("unchecked")
    private static List<Object> list(Object v) {
        return v instanceof List ? new ArrayList<>((List<Object>) v) : new ArrayList<>();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object v) {
        return v instanceof Map ? (Map<String, Object>) v : new LinkedHashMap<>();
    }

    private void checkUnknownKeys(Map<String, Object> map, Set<String> allowed, String where, List<String> errors) {
        for (String k : map.keySet()) {
            if (!allowed.contains(k)) {
                errors.add("未知字段 \"" + k + "\"" + (where.isEmpty() ? "" : "（" + where + "）")
                        + "（可用: " + new java.util.TreeSet<>(allowed) + "）");
            }
        }
        if (map.isEmpty() && !where.isEmpty()) {
            errors.add(where + " 不能为空");
        }
    }

}
