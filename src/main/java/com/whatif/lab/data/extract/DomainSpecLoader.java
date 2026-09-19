package com.whatif.lab.data.extract;

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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * 领域配置加载器 —— 从「内置配置」+「外部配置目录」读 {@link DomainSpec}。
 *
 * <p><b>两个来源，语义不同</b>：
 * <ul>
 *   <li><b>内置</b>（classpath {@code domains/*.json}，随 jar 分发）：开箱即用的样例，
 *       例如 {@code ecommerce-generic.json} —— 它与手写实现 {@code EcommerceExtractor} 语义等价，
 *       用来证明"配置层真的能承载业务语义"；</li>
 *   <li><b>外部</b>（{@code whatif.domains.dir}，默认 {@code domains}，相对启动目录）：
 *       使用者放自己的配置的地方。<b>关键：它在 jar 之外</b> ——
 *       丢一个文件 + 重启即可生效，不需要改代码、不需要重新打包。</li>
 * </ul>
 * 同名时外部配置覆盖内置配置（使用者的意图优先）。
 *
 * <p><b>为什么要在加载阶段做严格校验</b>：
 * 本项目全局的 {@code ObjectMapper} 刻意配了 {@code FAIL_ON_UNKNOWN_PROPERTIES=false}
 * （LLM 多输出一个字段不该让链路失败）。但这个宽松策略对**配置文件**是有害的：
 * 把 {@code "classify"} 敲成 {@code "classfy"} 会被静默忽略 → 你的配置看起来生效了、
 * 行为却和预期不同，而且没有任何提示。所以这里额外做一次"已知键"校验，
 * 把拼错的键**明确报出来**（进日志、也进接口），而不是让它悄悄变成默认行为。
 *
 * <p><b>单份配置坏掉不能拖死整个应用</b>（与 AI 配置同一原则）：
 * 某份文件解析失败只记问题、跳过该文件，其它领域照常可用；问题列表会通过
 * {@code GET /api/datasets/domains} 暴露出来，使用者看得见。
 */
@Component
public class DomainSpecLoader {

    private static final Logger log = LoggerFactory.getLogger(DomainSpecLoader.class);

    /** 允许的比较/判定算子（闭合词汇表 —— 不提供通用表达式求值，见 DomainSpec 注释）。 */
    public static final Set<String> ALLOWED_OPS = Set.of(
            "blank", "eq", "ne", "lt", "lte", "gt", "gte",
            "startsWith", "endsWith", "contains", "in", "unparsable");

    public static final Set<String> ALLOWED_AMOUNT_OPS = Set.of("multiply", "none");
    public static final Set<String> ALLOWED_ATTR_TYPES = Set.of("string", "decimal", "int");
    public static final Set<String> ALLOWED_FIELD_TYPES = Set.of("string", "decimal", "int", "time");

    private static final Set<String> TOP_KEYS = Set.of(
            "domain", "description", "features", "entityTypes", "eventTypes", "requiredFields", "fieldTypes",
            "skip", "classify", "defaultEventType", "absoluteQuantity", "amount", "entityAttributes");
    private static final Set<String> CONDITION_KEYS = Set.of("field", "op", "value", "reason");
    private static final Set<String> CLASSIFY_KEYS = Set.of("field", "op", "value", "as");
    private static final Set<String> AMOUNT_KEYS = Set.of("op", "abs", "with");
    private static final Set<String> ATTR_KEYS = Set.of("target", "name", "from", "type");

    private final JsonCodec json;
    private final String externalDir;

    public DomainSpecLoader(JsonCodec json,
                            @Value("${whatif.domains.dir:domains}") String externalDir) {
        this.json = json;
        this.externalDir = externalDir;
    }

    /** 加载结果：可用的配置 + 每份坏配置的具体问题（问题要能被看见，不能静默）。 */
    public record Loaded(List<DomainSpec> specs, Map<String, String> problems) {
    }

    public Loaded load() {
        Map<String, DomainSpec> byName = new LinkedHashMap<>();
        Map<String, String> problems = new LinkedHashMap<>();

        // ① 内置（classpath）—— 解析不了说明打包有问题，记问题、继续
        for (String location : classpathLocations()) {
            readOne(location, byName, problems, false);
        }
        // ② 外部目录 —— 覆盖同名内置配置
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
                readOne(p.toString(), byName, problems, true);
            }
            log.info("领域配置：内置 {} 份 + 外部目录 {} 份（{}），生效领域 {}",
                    classpathLocations().size(), files.size(), externalDir, byName.keySet());
        } else {
            log.info("领域配置：外部目录 {} 不存在（可选；放 *.json 进去即可新增领域），内置 {} 份，生效领域 {}",
                    externalDir, classpathLocations().size(), byName.keySet());
        }
        if (!problems.isEmpty()) {
            problems.forEach((k, v) -> log.error("领域配置有问题（已跳过该文件）：{} —— {}", k, v));
        }
        return new Loaded(List.copyOf(byName.values()), problems);
    }

    private List<String> classpathLocations() {
        try {
            Resource[] resources = new PathMatchingResourcePatternResolver()
                    .getResources("classpath*:domains/*.json");
            List<String> out = new ArrayList<>();
            for (Resource r : resources) {
                out.add(r.getURL().toString());
            }
            return out;
        } catch (Exception e) {
            log.warn("扫描内置领域配置失败: {}", e.getMessage());
            return List.of();
        }
    }

    private void readOne(String location, Map<String, DomainSpec> byName,
                         Map<String, String> problems, boolean external) {
        String text;
        try {
            text = read(location);
        } catch (Exception e) {
            problems.put(location, "读取失败: " + e.getMessage());
            return;
        }
        String label = external ? location : shortName(location);
        try {
            DomainSpec spec = parse(text, label);
            byName.put(spec.domain().toLowerCase(), spec);
        } catch (Exception e) {
            problems.put(label, e.getMessage());
        }
    }

    private String read(String location) throws Exception {
        if (location.startsWith("file:") || location.startsWith("jar:")) {
            try (InputStream in = new java.net.URL(location).openStream()) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        }
        Path p = Path.of(location);
        if (Files.isRegularFile(p)) {
            return Files.readString(p, StandardCharsets.UTF_8);
        }
        return new String(new java.net.URL(location).openStream().readAllBytes(), StandardCharsets.UTF_8);
    }

    private static String shortName(String location) {
        int i = location.lastIndexOf('/');
        return i < 0 ? location : location.substring(i + 1);
    }

    /** 解析 + 校验：先按 Map 读出检查"已知键"，再绑定成 record。 */
    DomainSpec parse(String text, String label) {
        Map<String, Object> raw = json.read(text, new TypeReference<>() {
        });
        if (raw == null) {
            throw new IllegalArgumentException("内容为空");
        }
        List<String> errors = new ArrayList<>();
        checkUnknownKeys(raw, TOP_KEYS, "", errors);
        checkEach(section(raw, "skip", label, errors), CONDITION_KEYS, "skip[]", errors);
        checkEach(section(raw, "classify", label, errors), CLASSIFY_KEYS, "classify[]", errors);
        checkUnknownKeys(asMap(raw.get("amount")), AMOUNT_KEYS, "amount", errors);
        checkEach(section(raw, "entityAttributes", label, errors), ATTR_KEYS, "entityAttributes[]", errors);

        DomainSpec spec;
        try {
            spec = json.read(text, DomainSpec.class);
        } catch (Exception e) {
            throw new IllegalArgumentException("配置格式有误: " + e.getMessage());
        }
        if (spec == null || spec.domain() == null || spec.domain().isBlank()) {
            errors.add("缺少 domain 字段（领域名，导入时要用它）");
        }
        for (DomainSpec.Condition c : spec.skip()) {
            if (c.field() == null || c.field().isBlank()) {
                errors.add("skip[] 缺少 field");
            }
            if (!ALLOWED_OPS.contains(String.valueOf(c.op()))) {
                errors.add("skip[] 不支持的 op: " + c.op() + "（可用: " + ALLOWED_OPS + "）");
            }
        }
        for (DomainSpec.Classification c : spec.classify()) {
            if (!ALLOWED_OPS.contains(String.valueOf(c.op()))) {
                errors.add("classify[] 不支持的 op: " + c.op() + "（可用: " + ALLOWED_OPS + "）");
            }
            if (!DomainSpec.BUCKETS.contains(String.valueOf(c.as()))) {
                errors.add("classify[].as 必须是 " + DomainSpec.BUCKETS + "，实际: " + c.as());
            }
        }
        if (!DomainSpec.BUCKETS.contains(spec.defaultEventType())) {
            errors.add("defaultEventType 必须是 " + DomainSpec.BUCKETS + "，实际: " + spec.defaultEventType());
        }
        if (!ALLOWED_AMOUNT_OPS.contains(spec.amount().op())) {
            errors.add("amount.op 必须是 " + ALLOWED_AMOUNT_OPS + "，实际: " + spec.amount().op());
        }
        spec.fieldTypes().forEach((f, t) -> {
            if (!ALLOWED_FIELD_TYPES.contains(t)) {
                errors.add("fieldTypes." + f + " 必须是 " + ALLOWED_FIELD_TYPES + "，实际: " + t);
            }
        });
        for (DomainSpec.EntityAttribute a : spec.entityAttributes()) {
            if (!Set.of("main", "item").contains(a.target())) {
                errors.add("entityAttributes[].target 必须是 main/item，实际: " + a.target());
            }
            if (!ALLOWED_ATTR_TYPES.contains(a.type())) {
                errors.add("entityAttributes[].type 必须是 " + ALLOWED_ATTR_TYPES + "，实际: " + a.type());
            }
            if (a.name() == null || a.name().isBlank()) {
                errors.add("entityAttributes[] 缺少 name");
            }
        }
        if (!errors.isEmpty()) {
            throw new IllegalArgumentException("配置校验不通过 —— " + String.join("；", errors));
        }
        return spec;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) {
        return o instanceof Map ? (Map<String, Object>) o : Map.of();
    }

    private static List<?> list(Object o) {
        return o instanceof List ? (List<?>) o : List.of();
    }

    /** 取一个数组配置节；如果它存在但不是数组，直接报错（配置写错不能当成"没有"）。 */
    private List<Object> section(Map<String, Object> raw, String key, String label, List<String> errors) {
        Object v = raw.get(key);
        if (v == null) {
            return List.of();
        }
        if (!(v instanceof List)) {
            errors.add(key + " 必须是数组");
            return List.of();
        }
        return new ArrayList<>((List<?>) v);
    }

    /** 数组节的逐项键校验（配置写错必须报出来，而不是被宽松的 ObjectMapper 静默忽略）。 */
    private void checkEach(List<?> items, Set<String> allowed, String where, List<String> errors) {
        for (Object o : items) {
            if (!(o instanceof Map)) {
                errors.add(where + " 的每一项都必须是对象");
                continue;
            }
            checkUnknownKeys(asMap(o), allowed, where, errors);
        }
    }

    private void checkUnknownKeys(Map<String, Object> map, Set<String> allowed, String where, List<String> errors) {
        for (String k : map.keySet()) {
            if (!allowed.contains(k)) {
                errors.add((where.isEmpty() ? "" : where + " ") + "未知字段 \"" + k + "\"（可用: " + allowed + "）");
            }
        }
    }
}
