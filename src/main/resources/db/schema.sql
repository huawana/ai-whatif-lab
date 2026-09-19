-- ============================================================================
-- AI What-If Lab — 数据库结构（MySQL 8.0）
--
-- 设计说明（面试可直接讲）：
--  1) 通用性靠「Entity / Event 两张泛化表 + JSON 属性」实现，核心引擎不绑定行业。
--     ecommerce 的 Customer/Product 与 game 的 Player/Item 存进同一套表结构，
--     只是 entity_type 不同 —— 换行业不用改表、不用改引擎。
--  2) Event 里把 entity_external_id / product_external_id / quantity / unit_price
--     提到独立列，而不是全塞进 JSON。原因是仿真引擎要按 (客户,商品) 做大批量扫描与
--     训练样本构造，JSON 里取值无法走索引，几十万行会拖垮导入与训练。
--     代价是这三列在游戏/企业域里可能为空 —— 这是有意识的取舍。
--  3) 所有参与结果计算的参数（数据集/模型/规则/Scenario/随机种子）都固化到
--     experiment 表上，保证历史实验可完整复现（实验版本控制）。
--  4) 金额一律 DECIMAL，不用 DOUBLE：浮点累加在百万级事件上会出现分位误差，
--     指标是要写进报告的数字，不能有累计漂移。
-- ============================================================================

CREATE DATABASE IF NOT EXISTS whatif_lab DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
USE whatif_lab;

-- ---------------------------------------------------------------------------
-- 数据集：一次导入 = 一个 dataset 版本
-- ---------------------------------------------------------------------------
-- 注意：DROP 清单必须与下面的 CREATE 清单**一一对应**。
-- 漏一个的后果不是"多留一张旧表"，而是第二次执行脚本时在"表已存在"处报错，
-- 而 mysql 遇到错误会**中断整个脚本** → 后面所有表都不建 → 应用到处 BadSqlGrammar（实测踩过）。
DROP TABLE IF EXISTS ai_schema_proposal;
DROP TABLE IF EXISTS dataset_profile;
DROP TABLE IF EXISTS experiment_task;
DROP TABLE IF EXISTS metric_result;
DROP TABLE IF EXISTS experiment_result;
DROP TABLE IF EXISTS experiment;
DROP TABLE IF EXISTS scenario;
DROP TABLE IF EXISTS rule;
DROP TABLE IF EXISTS behavior_model;
DROP TABLE IF EXISTS event;
DROP TABLE IF EXISTS entity;
DROP TABLE IF EXISTS dataset;

CREATE TABLE dataset (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    name            VARCHAR(128) NOT NULL,
    domain          VARCHAR(32)  NOT NULL DEFAULT 'ecommerce' COMMENT '领域模板: ecommerce/game/enterprise',
    source          VARCHAR(255)          DEFAULT NULL COMMENT '数据来源(文件名/URL)',
    version         INT          NOT NULL DEFAULT 1,
    description     VARCHAR(512)          DEFAULT NULL,
    status          VARCHAR(16)  NOT NULL DEFAULT 'CREATED' COMMENT 'CREATED/IMPORTING/READY/FAILED',
    entity_count    INT          NOT NULL DEFAULT 0,
    event_count     INT          NOT NULL DEFAULT 0,
    raw_row_count   INT          NOT NULL DEFAULT 0,
    skipped_row_count INT        NOT NULL DEFAULT 0,
    column_mapping  JSON                  DEFAULT NULL COMMENT '原始列 → Entity/Event 字段的映射(可人工确认)',
    baseline_rule_version INT            DEFAULT NULL COMMENT '当前生效的规则集版本(作为 Baseline)',
    created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_dataset_name_version (name, version)
) ENGINE = InnoDB COMMENT '数据集版本';

-- ---------------------------------------------------------------------------
-- 业务实体（泛化）：Customer/Product/Player/Employee ...
-- ---------------------------------------------------------------------------
CREATE TABLE entity (
    id           BIGINT      NOT NULL AUTO_INCREMENT,
    dataset_id   BIGINT      NOT NULL,
    entity_type  VARCHAR(32) NOT NULL COMMENT 'Customer/Product/...',
    external_id  VARCHAR(64) NOT NULL COMMENT '原始业务主键',
    attributes   JSON                 DEFAULT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_entity (dataset_id, entity_type, external_id),
    KEY idx_entity_type (dataset_id, entity_type)
) ENGINE = InnoDB COMMENT '业务实体(泛化)';

-- ---------------------------------------------------------------------------
-- 行为事件（泛化）：PURCHASE/CANCEL/VIEW/LOGIN ...
-- ---------------------------------------------------------------------------
CREATE TABLE event (
    id                 BIGINT        NOT NULL AUTO_INCREMENT,
    dataset_id         BIGINT        NOT NULL,
    entity_external_id VARCHAR(64)   NOT NULL COMMENT '行为主体(CustomerID)',
    event_type         VARCHAR(32)   NOT NULL COMMENT 'PURCHASE/CANCEL/...',
    product_external_id VARCHAR(64)           DEFAULT NULL COMMENT '行为客体(StockCode)',
    event_time         DATETIME      NOT NULL,
    quantity           INT                    DEFAULT NULL,
    unit_price         DECIMAL(12, 4)         DEFAULT NULL,
    amount             DECIMAL(14, 4)         DEFAULT NULL COMMENT 'quantity * unit_price',
    metadata           JSON                   DEFAULT NULL,
    PRIMARY KEY (id),
    KEY idx_event_ds_time (dataset_id, event_time),
    KEY idx_event_type_time (dataset_id, event_type, event_time),
    KEY idx_event_pair (dataset_id, entity_external_id, product_external_id)
) ENGINE = InnoDB COMMENT '行为事件(泛化)';

-- ---------------------------------------------------------------------------
-- 数据画像：导入之前先看清这单子长什么样（AI Schema Mapper 的唯一输入）
-- 画像必须落库：否则"AI 当时看到的是什么"无法追溯，判错时无从复盘。
-- ---------------------------------------------------------------------------
CREATE TABLE dataset_profile (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    profile_id      CHAR(36)     NOT NULL COMMENT '对外标识（可回放）',
    dataset_id      BIGINT                DEFAULT NULL COMMENT '导入后回填；画像早于导入，允许为空',
    file_name       VARCHAR(255)          DEFAULT NULL,
    source_path     VARCHAR(512)          DEFAULT NULL COMMENT '源文件绝对路径（判列时做数值冲突检测要用）',
    file_hash       CHAR(16)              DEFAULT NULL COMMENT '文件内容哈希前 16 位：输入变了要能看出来',
    size_bytes      BIGINT                DEFAULT NULL,
    row_count       BIGINT                DEFAULT NULL,
    column_count    INT                   DEFAULT NULL,
    charset         VARCHAR(32)           DEFAULT NULL,
    profile_version VARCHAR(32)  NOT NULL COMMENT '统计口径版本：口径变了旧画像不可比',
    profile_json    JSON                  DEFAULT NULL,
    created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_dataset_profile (profile_id),
    KEY idx_dataset_profile_dataset (dataset_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = 'CSV 客观画像（统计事实，不含语义判断）';

-- ---------------------------------------------------------------------------
-- 行为模型：逻辑回归权重 + 标准化参数（训练/推理必须用同一套参数）
-- ---------------------------------------------------------------------------
CREATE TABLE behavior_model (
    id                 BIGINT        NOT NULL AUTO_INCREMENT,
    dataset_id         BIGINT        NOT NULL,
    version            INT           NOT NULL,
    model_type         VARCHAR(32)   NOT NULL DEFAULT 'LOGISTIC_REGRESSION',
    feature_names      JSON          NOT NULL COMMENT '特征顺序，推理时按此顺序拼向量',
    coefficients       JSON          NOT NULL,
    intercept          DECIMAL(18, 8) NOT NULL,
    feature_means      JSON          NOT NULL COMMENT '标准化均值',
    feature_stds       JSON          NOT NULL COMMENT '标准化标准差',
    train_metrics      JSON                   DEFAULT NULL COMMENT 'AUC/LogLoss/Accuracy/Brier',
    train_sample_count INT           NOT NULL DEFAULT 0,
    positive_count     INT           NOT NULL DEFAULT 0,
    training_seed      BIGINT        NOT NULL DEFAULT 20260919,
    trained_at         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    active             TINYINT(1)    NOT NULL DEFAULT 1,
    PRIMARY KEY (id),
    UNIQUE KEY uk_model_version (dataset_id, version)
) ENGINE = InnoDB COMMENT '行为模型版本';

-- ---------------------------------------------------------------------------
-- 规则集：同一 version 的多行 = 一套规则
-- ---------------------------------------------------------------------------
CREATE TABLE rule (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    dataset_id  BIGINT       NOT NULL,
    version     INT          NOT NULL,
    set_name    VARCHAR(128) NOT NULL COMMENT '规则集名称',
    rule_type   VARCHAR(32)  NOT NULL COMMENT 'PRICE/DISCOUNT/PROBABILITY',
    params      JSON         NOT NULL,
    description VARCHAR(512)          DEFAULT NULL,
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_rule_version (dataset_id, version)
) ENGINE = InnoDB COMMENT '业务规则(规则集版本)';

-- ---------------------------------------------------------------------------
-- 情景：LLM / 手工生成的 Scenario DSL
-- ---------------------------------------------------------------------------
CREATE TABLE scenario (
    id                 BIGINT        NOT NULL AUTO_INCREMENT,
    dataset_id         BIGINT        NOT NULL,
    name               VARCHAR(128)           DEFAULT NULL,
    natural_language   VARCHAR(1024)          DEFAULT NULL COMMENT '用户原始自然语言',
    dsl                JSON          NOT NULL COMMENT 'Scenario DSL',
    duration_days      INT           NOT NULL DEFAULT 30,
    source             VARCHAR(16)   NOT NULL DEFAULT 'MANUAL' COMMENT 'MANUAL/AI',
    status             VARCHAR(16)   NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT/VALIDATED/INVALID',
    validation_message VARCHAR(1024)          DEFAULT NULL,
    dsl_hash           CHAR(64)      NOT NULL COMMENT 'DSL 归一化后的 SHA-256，用于缓存/去重',
    created_at         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_scenario_ds_hash (dataset_id, dsl_hash)
) ENGINE = InnoDB COMMENT '情景(Scenario DSL)';

-- ---------------------------------------------------------------------------
-- 实验：一切参与计算的参数都固化在这里 → 可完整复现
-- ---------------------------------------------------------------------------
CREATE TABLE experiment (
    id                    BIGINT      NOT NULL AUTO_INCREMENT,
    dataset_id            BIGINT      NOT NULL,
    scenario_id           BIGINT      NOT NULL,
    behavior_model_id     BIGINT      NOT NULL,
    baseline_rule_version INT         NOT NULL,
    scenario_dsl_hash     CHAR(64)    NOT NULL,
    domain                VARCHAR(32) NOT NULL DEFAULT 'ecommerce',
    duration_days         INT         NOT NULL,
    simulation_count      INT         NOT NULL,
    random_seed           BIGINT      NOT NULL,
    active_customers      INT         NOT NULL,
    candidates_per_customer INT       NOT NULL,
    status                VARCHAR(16) NOT NULL DEFAULT 'CREATED'
                          COMMENT 'CREATED/PENDING/RUNNING/SUCCESS/FAILED',
    stage                 VARCHAR(64)          DEFAULT NULL COMMENT '当前阶段文案(SSE 推送)',
    progress              INT         NOT NULL DEFAULT 0,
    cache_key             CHAR(64)             DEFAULT NULL COMMENT '结果缓存指纹=SHA256 十六进制(64字符)，不是带前缀的 Redis key；前缀只在访问缓存时现拼',
    cache_hit             TINYINT(1)  NOT NULL DEFAULT 0,
    retry_count           INT         NOT NULL DEFAULT 0,
    error_message         VARCHAR(1024)        DEFAULT NULL,
    elapsed_ms            BIGINT               DEFAULT NULL,
    created_at            DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    started_at            DATETIME             DEFAULT NULL,
    finished_at           DATETIME             DEFAULT NULL,
    PRIMARY KEY (id),
    KEY idx_exp_status (status),
    KEY idx_exp_cache_key (cache_key),
    KEY idx_exp_dataset (dataset_id)
) ENGINE = InnoDB COMMENT '实验(可复现)';

-- ---------------------------------------------------------------------------
-- 实验结果：Baseline / Scenario 两臂的分布统计
-- ---------------------------------------------------------------------------
CREATE TABLE experiment_result (
    id            BIGINT      NOT NULL AUTO_INCREMENT,
    experiment_id BIGINT      NOT NULL,
    arm           VARCHAR(16) NOT NULL COMMENT 'BASELINE/SCENARIO',
    metrics       JSON        NOT NULL COMMENT '每指标 mean/p5/p50/p95/std',
    created_at    DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_result_arm (experiment_id, arm)
) ENGINE = InnoDB COMMENT '实验结果(两臂分布)';

-- ---------------------------------------------------------------------------
-- 指标明细：每指标一行，方便直接 SQL 对比与前端画图
-- ---------------------------------------------------------------------------
CREATE TABLE metric_result (
    id            BIGINT         NOT NULL AUTO_INCREMENT,
    experiment_id BIGINT         NOT NULL,
    arm           VARCHAR(16)    NOT NULL,
    metric        VARCHAR(32)    NOT NULL COMMENT 'GMV/ORDERS/CONVERSION/DISCOUNT_COST/PROFIT/AOV',
    mean_value    DECIMAL(20, 6) NOT NULL,
    p5_value      DECIMAL(20, 6) NOT NULL,
    p50_value     DECIMAL(20, 6) NOT NULL,
    p95_value     DECIMAL(20, 6) NOT NULL,
    std_value     DECIMAL(20, 6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_metric (experiment_id, arm, metric),
    KEY idx_metric_metric (metric)
) ENGINE = InnoDB COMMENT '指标明细';

-- ---------------------------------------------------------------------------
-- 实验任务：状态机 + 消费幂等 + 重试计数
-- ---------------------------------------------------------------------------
CREATE TABLE experiment_task (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    experiment_id BIGINT       NOT NULL,
    status        VARCHAR(16)  NOT NULL DEFAULT 'PENDING'
                  COMMENT 'PENDING/RUNNING/SUCCESS/FAILED',
    retry_count   INT          NOT NULL DEFAULT 0,
    max_retry     INT          NOT NULL DEFAULT 3,
    worker        VARCHAR(64)           DEFAULT NULL,
    error_message VARCHAR(1024)         DEFAULT NULL,
    created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_task_experiment (experiment_id)
) ENGINE = InnoDB COMMENT '实验任务(幂等键=experiment_id)';

-- ---------------------------------------------------------------------------
-- Schema 提案（AI 判列）：每一次提案都落库 —— 要能回答
--   "当时 AI 判了什么、凭什么、用的哪份画像、哪个模型、哪个提示词版本"（可审计 + 可复现）
-- ---------------------------------------------------------------------------
CREATE TABLE ai_schema_proposal (
    id               BIGINT       NOT NULL AUTO_INCREMENT,
    proposal_id      CHAR(36)     NOT NULL COMMENT '对外标识（可回放/可确认）',
    profile_id       CHAR(36)     NOT NULL COMMENT '输入画像（可追溯到那份统计事实）',
    file_hash        CHAR(16)              DEFAULT NULL COMMENT '源文件哈希：输入换了要能看出来',
    producer         VARCHAR(16)  NOT NULL COMMENT 'HEURISTIC / LLM / HUMAN —— 是规则判的还是模型判的',
    model            VARCHAR(64)           DEFAULT NULL,
    prompt_version   VARCHAR(32)           DEFAULT NULL,
    declared_domain  VARCHAR(64)           DEFAULT NULL,
    capability       VARCHAR(16)  NOT NULL COMMENT 'CAPABLE/IMPACT_ONLY/NOT_CAPABLE',
    executable       TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '能不能冻结执行（依赖校验通过且无未决项）',
    open_unknowns    INT          NOT NULL DEFAULT 0,
    error_count      INT          NOT NULL DEFAULT 0,
    proposal_json    JSON         NOT NULL,
    validation_json  JSON                  DEFAULT NULL COMMENT '校验器的错误/告警/冲突',
    confirmed        TINYINT(1)   NOT NULL DEFAULT 0,
    confirmed_domain VARCHAR(64)           DEFAULT NULL COMMENT '确认后落地成哪个领域配置',
    created_at       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_ai_schema_proposal (proposal_id),
    KEY idx_ai_schema_profile (profile_id)
) ENGINE = InnoDB COMMENT 'Schema 提案（AI 判列，可审计可复现）';
