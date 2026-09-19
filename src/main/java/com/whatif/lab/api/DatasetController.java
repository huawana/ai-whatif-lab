package com.whatif.lab.api;

import com.whatif.lab.common.ApiResponse;
import com.whatif.lab.common.BizException;
import com.whatif.lab.common.JsonCodec;
import com.whatif.lab.data.ColumnMapping;
import com.whatif.lab.data.DatasetImportService;
import com.whatif.lab.data.extract.DomainExtractorRegistry;
import com.whatif.lab.data.profile.DataProfile;
import com.whatif.lab.domain.rule.RuleSpec;
import com.whatif.lab.domain.rule.RuleType;
import com.whatif.lab.service.BehaviorModelService;
import com.whatif.lab.service.ProfileService;
import com.whatif.lab.service.DatasetService;
import com.whatif.lab.service.RuleService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 数据集 API。
 *
 * <p>REST 语义上注意：「校验失败」返回 HTTP 200 + {@code code=1002}，
 * 只有「找不到资源」才由全局异常处理器给 5xx/404。
 * 原因见 {@link ApiResponse}：调用方要能用 code 区分「参数问题」与「系统故障」。
 */
@RestController
@RequestMapping("/api/datasets")
public class DatasetController {

    private final DatasetService datasetService;
    private final RuleService ruleService;
    private final BehaviorModelService behaviorModelService;
    private final JsonCodec json;
    private final DomainExtractorRegistry extractors;
    private final String domainConfigDir;
    private final ProfileService profileService;

    public DatasetController(DatasetService datasetService,
                             RuleService ruleService,
                             BehaviorModelService behaviorModelService,
                             JsonCodec json,
                             DomainExtractorRegistry extractors,
                             @Value("${whatif.domains.dir:domains}") String domainConfigDir,
                             ProfileService profileService) {
        this.datasetService = datasetService;
        this.ruleService = ruleService;
        this.behaviorModelService = behaviorModelService;
        this.json = json;
        this.extractors = extractors;
        this.domainConfigDir = domainConfigDir;
        this.profileService = profileService;
    }

    @GetMapping
    public ApiResponse<List<Map<String, Object>>> list() {
        return ApiResponse.ok(datasetService.list());
    }

    @GetMapping("/{id}")
    public ApiResponse<Map<String, Object>> detail(@PathVariable Long id) {
        return ApiResponse.ok(datasetService.detail(id));
    }

    @GetMapping("/{id}/profile")
    public ApiResponse<Map<String, Object>> profile(@PathVariable Long id) {
        return ApiResponse.ok(datasetService.profile(id));
    }

    /** 快照诊断（时间切分泄漏测试用）。 */
    @GetMapping("/{id}/snapshot-diag")
    public ApiResponse<Map<String, Object>> snapshotDiagnostics(@PathVariable Long id) {
        return ApiResponse.ok(datasetService.snapshotDiagnostics(id));
    }

    @GetMapping("/{id}/quality")
    public ApiResponse<Map<String, Object>> quality(@PathVariable Long id) {
        return ApiResponse.ok(datasetService.quality(id));
    }

    /**
     * 数据画像（导入之前）：算这份 CSV 的客观统计并落库，返回 profileId 供回放。
     *
     * <p>为什么它是独立一步、且不碰 AI：画像出错是"统计算错"，AI 判错是"语义判错"，
     * 两者混在一起就没法定位。所以先把这一步做成可独立验收的确定性动作
     * （验收方式：拿公开数据集对已知事实逐条断言，见 verify-profile.sh）。
     *
     * <p>请求体：{@code {"path": "C:/data/x.csv", "statsRowLimit": 100000}}（后者可选）
     */
    @PostMapping("/analyze")
    public ApiResponse<DataProfile> analyze(@RequestBody Map<String, Object> body) {
        Object path = body.get("path");
        if (path == null || String.valueOf(path).isBlank()) {
            throw BizException.validation("缺少 path：请给出待分析 CSV 的服务端路径");
        }
        Object limit = body.get("statsRowLimit");
        Integer statsRowLimit = limit == null ? null : Integer.valueOf(String.valueOf(limit));
        return ApiResponse.ok(profileService.analyze(String.valueOf(path), statsRowLimit));
    }

    /** 回放一份历史画像（验收与排查用：证明"当时算出来的到底是什么"）。 */
    @GetMapping("/profiles/{profileId}")
    public ApiResponse<Map<String, Object>> replayProfile(@PathVariable String profileId) {
        return ApiResponse.ok(profileService.replay(profileId));
    }

    /**
     * 可用领域清单 + 每份配置的问题。
     *
     * <p>给「使用者」看的接口：丢了一份领域配置进 {@code domains/} 之后，
     * 他需要能自己确认三件事 —— ① 我的领域被认出来了吗；② 生效的配置到底长什么样；
     * ③ 如果没生效，是哪里写错了。没有这个接口，配置写错只会表现为"导入时报不支持的领域"，
     * 使用者无从排查（这正是"配置化"最容易变成"配了没用"的地方）。
     */
    @GetMapping("/domains")
    public ApiResponse<Map<String, Object>> domains() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("domains", extractors.domains());
        out.put("configDriven", extractors.specs().keySet());
        out.put("specs", extractors.specs());
        out.put("problems", extractors.problems());
        out.put("configDir", domainConfigDir);
        out.put("hint", "新增领域：把一份 *.json（字段手册见 domains/README.md）放进配置目录后重启，"
                + "然后 POST /api/datasets/import-path 时把 domain 指定成它。不需要改代码。");
        return ApiResponse.ok(out);
    }

    /** 上传前预览：返回表头、样例行、列映射建议与置信度。 */
    @PostMapping("/preview")
    public ApiResponse<DatasetImportService.PreviewResult> preview(@RequestParam("file") MultipartFile file,
                                                                  @RequestParam(defaultValue = "10") int limit,
                                                                  @RequestParam(defaultValue = "ecommerce") String domain)
            throws IOException {
        try (InputStream in = file.getInputStream()) {
            return ApiResponse.ok(datasetService.preview(in, limit, domain));
        }
    }

    /** 上传 CSV 导入（mappingJson 可选，缺省用启发式建议）。 */
    @PostMapping("/import")
    public ApiResponse<DatasetImportService.ImportResult> importFile(
            @RequestParam("file") MultipartFile file,
            @RequestParam String name,
            @RequestParam(defaultValue = "ecommerce") String domain,
            @RequestParam(required = false) String description,
            @RequestParam(required = false) String mappingJson) throws IOException {
        ColumnMapping mapping = mappingJson == null || mappingJson.isBlank()
                ? null : new ColumnMapping(toStringMap(json.readMap(mappingJson)));
        try (InputStream in = file.getInputStream()) {
            return ApiResponse.ok(datasetService.importCsv(in, name, domain, description, mapping));
        }
    }

    /** 服务器本地路径导入（脚本/批量导入用）。 */
    @PostMapping("/import-path")
    public ApiResponse<DatasetImportService.ImportResult> importPath(@RequestBody Map<String, Object> body)
            throws IOException {
        String path = str(body.get("path"));
        if (path == null) {
            throw BizException.validation("path 不能为空");
        }
        String name = str(body.getOrDefault("name", "local-csv"));
        String domain = str(body.getOrDefault("domain", "ecommerce"));
        String description = str(body.get("description"));
        Map<String, String> mapping = map(body.get("mapping"));
        return ApiResponse.ok(datasetService.importPath(path, name, domain, description, mapping));
    }

    @PostMapping("/{id}/purge")
    public ApiResponse<Void> purge(@PathVariable Long id) {
        datasetService.purge(id);
        return ApiResponse.ok();
    }

    // ------------------------------------------------------------------ 规则集

    @GetMapping("/{id}/rules")
    public ApiResponse<List<Map<String, Object>>> rules(@PathVariable Long id) {
        return ApiResponse.ok(ruleService.listVersions(id));
    }

    /**
     * 新建规则集版本（= 修改 Baseline）。
     *
     * <p>入参形如 {@code {"setName":"双十一前规则","rules":[{"type":"DISCOUNT","params":{"threshold":80,"amount":15}}]}}。
     * 改完 Baseline 后，后续实验的对比基准随之改变 —— 这是有意的：
     * Baseline 代表「业务现状」，现状变了基准就该变，而历史实验仍然记录着它当时用的版本号。
     */
    @PostMapping("/{id}/rules")
    public ApiResponse<Map<String, Object>> saveRules(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        String setName = str(body.getOrDefault("setName", "自定义规则集"));
        Object raw = body.get("rules");
        if (!(raw instanceof List<?> list) || list.isEmpty()) {
            throw BizException.validation("rules 不能为空");
        }
        List<RuleSpec> specs = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> m)) {
                throw BizException.validation("rules 元素必须是对象");
            }
            String type = str(m.get("type"));
            Object params = m.get("params");
            RuleType ruleType;
            try {
                ruleType = RuleType.valueOf(type == null ? "" : type.toUpperCase());
            } catch (IllegalArgumentException e) {
                throw BizException.validation("未知规则类型: " + type);
            }
            specs.add(new RuleSpec(ruleType, params instanceof Map<?, ?> pm ? castMap(pm) : Map.of(),
                    str(m.get("description"))));
        }
        int version = ruleService.save(id, setName, specs);
        datasetService.updateBaselineRuleVersion(id, version);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("datasetId", id);
        out.put("ruleVersion", version);
        out.put("note", "新规则集已生效为新版本。缓存按指纹隔离，旧结果不会被新规则污染；"
                + "但新规则需要重新训练模型吗？—— 不需要：模型学的是历史行为，"
                + "新规则通过感知折扣率与有效价格进入反事实计算。");
        return ApiResponse.ok(out);
    }

    // ------------------------------------------------------------------ 模型

    @GetMapping("/{id}/model")
    public ApiResponse<List<Map<String, Object>>> models(@PathVariable Long id) {
        return ApiResponse.ok(behaviorModelService.listModels(id));
    }

    /** 训练行为模型（确定性：同数据 + 同种子 → 同权重）。 */
    @PostMapping("/{id}/model/train")
    public ApiResponse<Map<String, Object>> train(@PathVariable Long id, @RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> b = body == null ? Map.of() : body;
        Long seed = b.get("seed") == null ? null : Long.valueOf(String.valueOf(b.get("seed")));
        Integer negativeRatio = b.get("negativeRatio") == null ? null
                : Integer.valueOf(String.valueOf(b.get("negativeRatio")));
        Integer epochs = b.get("epochs") == null ? null : Integer.valueOf(String.valueOf(b.get("epochs")));
        var config = epochs == null ? null
                : new com.whatif.lab.simulation.behavior.LogisticRegressionTrainer.Config(
                        0.5, epochs, 1e-4);
        BehaviorModelService.TrainResult result = behaviorModelService.train(id, null, negativeRatio, seed, config);
        Map<String, Object> out = json.readMap(json.write(result.model()));
        out.put("trainingStats", result.trainingStats());
        out.put("elapsedMs", result.elapsedMs());
        out.put("metrics", json.readMap(result.model().getTrainMetrics()));
        out.put("note", "训练是确定性的（全批量梯度下降 + 固定种子）：同样的数据与种子必然得到同样的权重，"
                + "因此实验可以完整复现。");
        return ApiResponse.ok(out);
    }

    private static Map<String, String> toStringMap(Map<String, Object> raw) {
        Map<String, String> out = new LinkedHashMap<>();
        raw.forEach((k, v) -> out.put(k, v == null ? null : String.valueOf(v)));
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Map<?, ?> raw) {
        Map<String, Object> out = new LinkedHashMap<>();
        raw.forEach((k, v) -> out.put(String.valueOf(k), v));
        return out;
    }

    private static String str(Object v) {
        return v == null ? null : String.valueOf(v);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> map(Object v) {
        if (!(v instanceof Map<?, ?> m)) {
            return null;
        }
        Map<String, String> out = new LinkedHashMap<>();
        m.forEach((k, val) -> out.put(String.valueOf(k), String.valueOf(val)));
        return out;
    }

    /** 供内部调用把文本内容当 CSV 导入（验证脚本用）。 */
    public static InputStream bytes(String csv) {
        return new java.io.ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8));
    }
}
