package com.whatif.lab;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * AI What-If Lab —— 面向业务决策的智能仿真实验平台。
 *
 * <p>项目主线（这一条链就是全部设计的目的，Kafka/Redis/RAG/SSE 都是为它服务的）：
 * <pre>
 *   自然语言 → LLM(Scenario 编译器) → Scenario DSL → 校验
 *        → 行为模型(逻辑回归) + 规则引擎 → 蒙特卡洛仿真引擎 → 指标
 *        → Baseline vs Scenario 对比 → AI 结果解释
 * </pre>
 *
 * <p>核心设计思想：<b>AI 负责理解「想改变什么」，Simulation Engine 负责计算「改变之后会发生什么」。</b>
 * LLM 绝不直接输出业务数字 —— 它只输出结构化 DSL，由确定性引擎算出所有会被追责的指标。
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@MapperScan("com.whatif.lab.persistence.mapper")
@EnableAsync
public class WhatIfLabApplication {

    public static void main(String[] args) {
        SpringApplication.run(WhatIfLabApplication.class, args);
    }
}
