package com.whatif.lab.api;

import com.whatif.lab.common.ApiResponse;
import com.whatif.lab.service.ExperimentService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 平台首页统计（实验数量、缓存命中率、调度水位、各领域模板）。
 */
@RestController
@RequestMapping("/api/dashboard")
public class DashboardController {

    private final ExperimentService experimentService;

    public DashboardController(ExperimentService experimentService) {
        this.experimentService = experimentService;
    }

    @GetMapping
    public ApiResponse<Map<String, Object>> dashboard() {
        Map<String, Object> out = experimentService.dashboard();
        out.put("project", "AI What-If Lab");
        out.put("pipeline", java.util.List.of(
                "自然语言 → LLM(Scenario 编译器) → Scenario DSL",
                "DSL 校验（结构/语义/业务三层）",
                "行为模型（逻辑回归）+ 规则引擎",
                "蒙特卡洛仿真（公共随机数，两臂配对）",
                "指标对比 + 显著性检验",
                "AI 结果解释（数字来自引擎，不来自模型）"));
        out.put("domains", java.util.List.of(new LinkedHashMap<>(Map.of(
                "ecommerce", "已实现并验证（UCI Online Retail）"))));
        return ApiResponse.ok(out);
    }
}
