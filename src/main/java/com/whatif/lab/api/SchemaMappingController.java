package com.whatif.lab.api;

import com.whatif.lab.common.ApiResponse;
import com.whatif.lab.service.SchemaMappingService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Schema Mapper 的接口面 —— "AI 判列"这一步对外只暴露四件事：
 *
 * <pre>
 *   GET  /api/ai/schema/producer          这次是谁在判列（LLM 还是启发式、哪个模型、哪个提示词版本）
 *   POST /api/ai/schema/propose           对一份 CSV（或已落库的画像）生成提案
 *   GET  /api/ai/schema/{proposalId}      取回提案（含校验结果）—— 可回放、可审计
 *   POST /api/ai/schema/{proposalId}/confirm  人工确认并冻结成领域配置
 * </pre>
 *
 * <p>刻意**没有**"自动冻结"接口：判列必须经过人确认（未决项未清零时 confirm 会拒绝）。
 */
@RestController
@RequestMapping("/api/ai/schema")
public class SchemaMappingController {

    private final SchemaMappingService service;

    public SchemaMappingController(SchemaMappingService service) {
        this.service = service;
    }

    @GetMapping("/producer")
    public ApiResponse<Map<String, Object>> producer() {
        return ApiResponse.ok(service.producerStatus());
    }

    @PostMapping("/propose")
    public ApiResponse<Map<String, Object>> propose(@RequestBody Map<String, Object> body) {
        String path = body.get("path") == null ? null : String.valueOf(body.get("path"));
        String profileId = body.get("profileId") == null ? null : String.valueOf(body.get("profileId"));
        String producer = body.get("producer") == null ? "auto" : String.valueOf(body.get("producer"));
        String domain = body.get("domain") == null ? null : String.valueOf(body.get("domain"));
        return ApiResponse.ok(service.propose(path, profileId, producer, domain));
    }

    @GetMapping("/{proposalId}")
    public ApiResponse<Map<String, Object>> get(@PathVariable String proposalId) {
        return ApiResponse.ok(service.get(proposalId));
    }

    @PostMapping("/{proposalId}/confirm")
    public ApiResponse<Map<String, Object>> confirm(@PathVariable String proposalId,
                                                    @RequestBody(required = false) Map<String, Object> body) {
        return ApiResponse.ok(service.confirm(proposalId, body == null ? Map.of() : body));
    }
}
