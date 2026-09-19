package com.whatif.lab.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.whatif.lab.ai.config.AiProperties;
import com.whatif.lab.ai.schema.ColumnRole;
import com.whatif.lab.ai.schema.HeuristicSchemaMapper;
import com.whatif.lab.ai.schema.RoleAssignment;
import com.whatif.lab.ai.schema.RoleDeriver;
import com.whatif.lab.ai.schema.SchemaMapperAgent;
import com.whatif.lab.ai.schema.SchemaProposal;
import com.whatif.lab.ai.schema.SchemaValidator;
import com.whatif.lab.common.BizException;
import com.whatif.lab.common.JsonCodec;
import com.whatif.lab.data.extract.DomainSpec;
import com.whatif.lab.data.profile.DataProfile;
import com.whatif.lab.persistence.mapper.AiSchemaProposalMapper;
import com.whatif.lab.persistence.po.AiSchemaProposalPO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Schema Mapper 的编排 —— 把"判列"做成一条有护栏的流水线：
 *
 * <pre>
 *   画像（客观统计事实）
 *      ↓  ① 生产者：LLM（可用时）或启发式（降级）—— 只产出"角色 + 置信度 + 证据"
 *   角色判定
 *      ↓  ② 确定性推导：角色 → 领域配置（RoleDeriver，绝不让 LLM 写配置）
 *   领域配置
 *      ↓  ③ 确定性校验：词汇/结构/类型/数值冲突（SchemaValidator）
 *   校验结果 + 未决项
 *      ↓  ④ 人工确认（可改判；改判者记为 HUMAN）
 *   冻结：写成 domains/&lt;domain&gt;.json（与手写领域配置**同一个格式、同一个校验器**）
 * </pre>
 *
 * <p>每一步都落库，且记录"是 LLM 判的还是规则判的"（producer），出了错能分清责任。
 */
@Service
public class SchemaMappingService {

    private static final Logger log = LoggerFactory.getLogger(SchemaMappingService.class);

    private final ProfileService profileService;
    private final HeuristicSchemaMapper heuristic;
    private final SchemaMapperAgent agent;
    private final SchemaValidator validator;
    private final RoleDeriver deriver;
    private final AiSchemaProposalMapper mapper;
    private final AiProperties aiProperties;
    private final JsonCodec json;

    @Value("${whatif.domains.dir:domains}")
    private String domainsDir;

    public SchemaMappingService(ProfileService profileService,
                               HeuristicSchemaMapper heuristic,
                               SchemaMapperAgent agent,
                               SchemaValidator validator,
                               RoleDeriver deriver,
                               AiSchemaProposalMapper mapper,
                               AiProperties aiProperties,
                               JsonCodec json) {
        this.profileService = profileService;
        this.heuristic = heuristic;
        this.agent = agent;
        this.validator = validator;
        this.deriver = deriver;
        this.mapper = mapper;
        this.aiProperties = aiProperties;
        this.json = json;
    }

    /** 生产者的可用性（前端/脚本据此显示"这次是 AI 判的还是规则判的"）。 */
    public Map<String, Object> producerStatus() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("llmAvailable", agent.available());
        out.put("llmReason", agent.available() ? null : agent.unavailableReason());
        out.put("model", agent.available() ? aiProperties.effectiveModel() : null);
        out.put("promptVersion", SchemaMapperAgent.PROMPT_VERSION);
        out.put("heuristicAvailable", true);
        return out;
    }

    /**
     * 生成提案。
     *
     * @param path         CSV 路径（与 profileId 二选一）
     * @param profileId    已落库的画像 id（二选一）
     * @param producerHint "auto"（有 key 用 LLM，否则启发式）/ "heuristic"（强制启发式，用于对照实验）
     */
    public Map<String, Object> propose(String path, String profileId, String producerHint, String declaredDomain) {
        DataProfile profile;
        String sourcePath;
        if (profileId != null && !profileId.isBlank()) {
            Map<String, Object> raw = profileService.raw(profileId);
            profile = (DataProfile) raw.get("profile");
            sourcePath = (String) raw.get("sourcePath");
        } else {
            if (path == null || path.isBlank()) {
                throw new BizException(1002, "必须给 path 或 profileId");
            }
            profile = profileService.analyze(path, null);
            sourcePath = Paths.get(path).toAbsolutePath().normalize().toString();
        }

        boolean forceHeuristic = "heuristic".equalsIgnoreCase(producerHint);
        boolean useLlm = !forceHeuristic && agent.available();

        List<RoleAssignment> roles;
        List<String> pos = new ArrayList<>();
        List<String> neg = new ArrayList<>();
        List<SchemaProposal.Unknown> unknowns = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        String producer;
        String model = null;
        String promptVersion = null;
        String rawOutput = null;
        String llmError = null;
        int attempts = 0;

        if (useLlm) {
            SchemaMapperAgent.LlmOutput out = agent.propose(profile);
            attempts = out.attempts();
            rawOutput = out.raw();
            if (out.error() != null) {
                // 上游失败/输出无法解析：**不静默降级**，把原因带回给调用方（降级与否由人决定）
                llmError = out.error();
                warnings.add("LLM 未产出可用结果（" + out.error() + "）→ 本次用启发式结果，producer=HEURISTIC");
            } else {
                roles = out.columns();
                pos = out.positiveEventValues();
                neg = out.negativeEventValues();
                unknowns.addAll(out.unknowns());
                warnings.addAll(out.notes());
                producer = "LLM";
                model = aiProperties.effectiveModel();
                promptVersion = SchemaMapperAgent.PROMPT_VERSION;
                return finish(profile, sourcePath, roles, pos, neg, unknowns, warnings, producer, model,
                        promptVersion, rawOutput, attempts, declaredDomain != null ? declaredDomain : out.declaredDomain(),
                        null);
            }
        }
        // 启发式（无 key / 强制对照 / LLM 失败）
        HeuristicSchemaMapper.Mapped m = heuristic.map(profile);
        warnings.addAll(m.warnings());
        producer = "HEURISTIC";
        promptVersion = SchemaMapperAgent.PROMPT_VERSION;
        return finish(profile, sourcePath, m.columns(), m.positiveEventValues(), m.negativeEventValues(),
                unknowns, warnings, producer, model, promptVersion, rawOutput, attempts, declaredDomain, llmError);
    }

    private Map<String, Object> finish(DataProfile profile, String sourcePath, List<RoleAssignment> roles,
                                       List<String> pos, List<String> neg,
                                       List<SchemaProposal.Unknown> unknowns, List<String> warnings,
                                       String producer, String model, String promptVersion,
                                       String rawOutput, int attempts, String declaredDomainInput, String llmError) {
        String domain = (declaredDomainInput == null || declaredDomainInput.isBlank())
                ? guessDomain(profile) : declaredDomainInput.toLowerCase(Locale.ROOT);
        String description = "由 Schema Mapper（" + producer + "）从画像 " + profile.fileName() + " 生成";

        RoleDeriver.Derived derived = deriver.derive(domain, description, roles, profile, pos, neg);
        String proposalId = java.util.UUID.randomUUID().toString();
        SchemaProposal.Provenance provenance = new SchemaProposal.Provenance(producer, model, promptVersion,
                profile.profileId(), profile.fileHash16(), LocalDateTime.now().toString());
        SchemaProposal proposal = new SchemaProposal(proposalId, "1.0", domain,
                "BEHAVIOR_COUNTERFACTUAL_MONTE_CARLO", null, null, roles, pos, neg,
                unknowns, warnings, derived.spec(), provenance);

        Path file = sourcePath == null ? null : Paths.get(sourcePath);
        SchemaValidator.Result vr = validator.validate(proposal, profile, file);
        SchemaProposal.Capability capability = vr.capability();
        boolean executable = capability != SchemaProposal.Capability.NOT_CAPABLE
                && vr.conflicts().isEmpty()
                && roles.stream().noneMatch(r -> r.lowConfidence(SchemaValidator.CONFIDENCE_THRESHOLD));

        SchemaProposal finalProposal = new SchemaProposal(proposalId, "1.0", domain,
                "BEHAVIOR_COUNTERFACTUAL_MONTE_CARLO", capability, vr.capabilityReason(),
                roles, pos, neg, vr.conflicts(), vr.warnings(), derived.spec(), provenance);

        AiSchemaProposalPO po = new AiSchemaProposalPO();
        po.setProposalId(proposalId);
        po.setProfileId(profile.profileId());
        po.setFileHash(profile.fileHash16());
        po.setProducer(producer);
        po.setModel(model);
        po.setPromptVersion(promptVersion);
        po.setDeclaredDomain(domain);
        po.setCapability(capability.name());
        po.setExecutable(executable);
        po.setOpenUnknowns(vr.conflicts().size());
        po.setErrorCount(vr.errors().size());
        po.setProposalJson(json.write(finalProposal));
        po.setValidationJson(json.write(vr));
        po.setConfirmed(false);
        mapper.insert(po);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("proposalId", proposalId);
        out.put("producer", producer);
        out.put("model", model);
        out.put("promptVersion", promptVersion);
        out.put("attempts", attempts);
        out.put("declaredDomain", domain);
        out.put("capability", capability.name());
        out.put("capabilityReason", vr.capabilityReason());
        out.put("executable", executable);
        out.put("errors", vr.errors());
        out.put("warnings", vr.warnings());
        out.put("unknowns", vr.conflicts());
        out.put("columns", roles);
        out.put("positiveEventValues", pos);
        out.put("negativeEventValues", neg);
        out.put("derivedSpec", derived.spec());
        out.put("fieldToColumn", derived.fieldToColumn());
        out.put("derivationNotes", derived.notes());
        out.put("provenance", provenance);
        out.put("profileId", profile.profileId());
        out.put("sourcePath", sourcePath);
        out.put("llmError", llmError);
        out.put("rawLlmOutput", rawOutput);
        return out;
    }

    /** 领域名猜测：启发式/LLM 都可以给，这里只做兜底（取文件名主干，去掉扩展名与日期后缀）。 */
    private static String guessDomain(DataProfile profile) {
        String n = profile.fileName().toLowerCase(Locale.ROOT).replaceAll("\\.[a-z0-9]+$", "");
        n = n.replaceAll("[^a-z0-9_-]", "-");
        return n.isBlank() ? "imported" : n;
    }

    public Map<String, Object> get(String proposalId) {
        AiSchemaProposalPO po = mapper.selectOne(new QueryWrapper<AiSchemaProposalPO>()
                .eq("proposal_id", proposalId));
        if (po == null) {
            throw new BizException(1002, "提案不存在：" + proposalId);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("proposalId", po.getProposalId());
        out.put("profileId", po.getProfileId());
        out.put("producer", po.getProducer());
        out.put("model", po.getModel());
        out.put("promptVersion", po.getPromptVersion());
        out.put("declaredDomain", po.getDeclaredDomain());
        out.put("capability", po.getCapability());
        out.put("executable", po.getExecutable());
        out.put("confirmed", po.getConfirmed());
        out.put("confirmedDomain", po.getConfirmedDomain());
        out.put("createdAt", po.getCreatedAt() == null ? null : po.getCreatedAt().toString());
        out.put("proposal", json.read(po.getProposalJson(), Map.class));
        out.put("validation", po.getValidationJson() == null ? null : json.read(po.getValidationJson(), Map.class));
        return out;
    }

    /**
     * 人工确认并冻结：把提案落成一个**领域配置文件**。
     *
     * <p>护栏：校验有错 → 拒绝；有未决项（unknowns）→ 拒绝（不猜、不默认）。
     * 人工可以通过 {@code columns} 改判角色 —— 改过的列 decider 记为 HUMAN，责任清楚。
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> confirm(String proposalId, Map<String, Object> req) {
        AiSchemaProposalPO po = mapper.selectOne(new QueryWrapper<AiSchemaProposalPO>()
                .eq("proposal_id", proposalId));
        if (po == null) {
            throw new BizException(1002, "提案不存在：" + proposalId);
        }
        SchemaValidator.Result vr = json.read(po.getValidationJson(), SchemaValidator.Result.class);
        if (vr != null && !vr.errors().isEmpty()) {
            throw new BizException(1002, "校验未通过的提案不允许冻结：" + String.join("；", vr.errors()));
        }
        if (vr != null && !vr.conflicts().isEmpty() && !Boolean.TRUE.equals(req.get("resolveUnknowns"))) {
            throw new BizException(1002, "还有 " + vr.conflicts().size()
                    + " 个未决项必须人来裁决（不许自动冻结）："
                    + vr.conflicts().stream().map(SchemaProposal.Unknown::question).toList());
        }
        SchemaProposal proposal = json.read(po.getProposalJson(), SchemaProposal.class);

        // 人工改判：columns 形如 {"列名": "ROLE"}
        List<RoleAssignment> roles = new ArrayList<>(proposal.columns());
        Map<String, Object> overrides = req.get("columns") instanceof Map<?, ?> m
                ? (Map<String, Object>) m : Map.of();
        for (Map.Entry<String, Object> e : overrides.entrySet()) {
            ColumnRole role;
            try {
                role = ColumnRole.valueOf(String.valueOf(e.getValue()).trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ex) {
                throw new BizException(1002, "未知角色：" + e.getValue());
            }
            roles.replaceAll(r -> r.column().equals(e.getKey())
                    ? new RoleAssignment(r.column(), role, 1.0, "人工改判（原判 " + r.role() + "）",
                    RoleAssignment.Decider.HUMAN)
                    : r);
        }
        String domain = req.get("domain") == null ? po.getDeclaredDomain() : String.valueOf(req.get("domain"));
        DataProfile profile = (DataProfile) profileService.raw(po.getProfileId()).get("profile");
        List<String> pos = req.get("positiveEventValues") instanceof List<?> l1
                ? l1.stream().map(String::valueOf).toList() : proposal.positiveEventValues();
        List<String> neg = req.get("negativeEventValues") instanceof List<?> l2
                ? l2.stream().map(String::valueOf).toList() : proposal.negativeEventValues();

        RoleDeriver.Derived derived = deriver.derive(domain, "人工确认后冻结（提案 " + proposalId + "）",
                roles, profile, pos, neg);
        // 落成领域配置：与手写领域配置**同一个格式、同一个加载器、同一个校验器**
        Path dir = Paths.get(domainsDir);
        Path target = dir.resolve(domain + ".json");
        try {
            Files.createDirectories(dir);
            Files.writeString(target, json.write(derived.spec()), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new BizException(1002, "写领域配置失败：" + e.getMessage());
        }
        po.setConfirmed(true);
        po.setConfirmedDomain(domain);
        mapper.updateById(po);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("proposalId", proposalId);
        out.put("domain", domain);
        out.put("configPath", target.toAbsolutePath().toString());
        out.put("spec", derived.spec());
        out.put("fieldToColumn", derived.fieldToColumn());
        out.put("notes", derived.notes());
        out.put("humanOverrides", overrides);
        out.put("hint", "该配置已落地到领域目录；重启应用后 /api/datasets/domains 会列出它");
        log.info("Schema 提案已冻结 proposalId={} domain={} path={}", proposalId, domain, target);
        return out;
    }
}
