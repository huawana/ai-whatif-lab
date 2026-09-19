# AI What-If Lab

> 把「如果我们把满减门槛从 100 降到 80，30 天后 GMV、订单量、优惠成本和利润会怎么变？」这类问题，
> 变成**可复现、可审计、带分布和显著性**的数字答案。AI 只负责理解数据语义和翻译自然语言，**所有业务数字都由确定性引擎产生**。

技术栈：Spring Boot 3.3.5 · Spring AI Alibaba 1.1.2.4 · MyBatis-Plus · MySQL 8 · Redis（可选）· JDK 17+

- 只想跑起来 → 看 [1. 快速开始](#1-快速开始)
- 想知道要配什么 → 看 [2. 需要哪些配置](#2-需要哪些配置)
- **想知道 API Key 写在哪、怎么保证不上传** → 看 [3. 密钥写在哪](#3-密钥写在哪重要)
- 想知道它现在能做到什么程度 → 看 [9. 已知边界](#9-已知边界诚实清单)

---

## 1. 快速开始

### 1.1 前置条件

| 依赖 | 版本 | 是否必需 |
|---|---|---|
| JDK | 17+（本机实测 JDK 22） | 必需 |
| Maven | 3.8+ | 必需（本机没装 CLI 时用 `.hermes/mvn.sh`，见下） |
| MySQL | 8.0+ | 必需（数据、规则、实验、模型都在库里） |
| Redis | 5+ | 可选 —— 不可用时缓存降级（错误码 1006），**只会变慢，不会算错** |
| AI API Key | 任意 OpenAI 兼容服务 | 可选 —— 不配也能跑，AI 相关能力走确定性降级 |

### 1.2 四步跑起来

```bash
# ① 建库 + 建表（12 张表；schema.sql 可重复执行，每次会重建）
mysql -uroot -p -e "CREATE DATABASE IF NOT EXISTS whatif_lab DEFAULT CHARSET utf8mb4;"
mysql -uroot -p whatif_lab < src/main/resources/db/schema.sql

# ② 打包 → target/ai-whatif-lab-1.0.0.jar
mvn -B -DskipTests package
#    本机没装 Maven CLI 时（走 IDEA 自带 Maven 3.9.9）：
#    bash .hermes/mvn.sh -B -DskipTests package

# ③ 启动（约 5 秒，端口 8088）
bash .hermes/start-app.sh

# ④ 打开控制台
#    http://127.0.0.1:8088/
```

**启动成功的唯一判据**：日志里出现 `Started WhatIfLabApplication`。
（Tomcat 先起来 ≠ 可以服务，脚本会等到这一行才返回。）

停止：`bash .hermes/stop-app.sh`

### 1.3 自检

```bash
mvn test                              # 单元测试（40 个：统计分布 / 逻辑回归 / 仿真核 / 数据层 / 数据画像）
bash .hermes/mvn.sh -B test           # 本机没装 Maven CLI 时用这个包装
bash .hermes/verify-no-secrets.sh     # 提交前密钥扫描（见第 3 节）
```

> 开发过程中还有一套更完整的验收脚本（12 层 / 190 条断言：与 Python 独立实现逐位对账、突变测试、
> 结构断言、判列黄金集……）。它依赖本机环境（已启动的实例 + 下载好的基准数据集），
> 属于本地开发工具，**不随仓库发布**。

### 1.4 想"假装没有 API Key"跑一遍

```bash
WHATIF_NO_KEY=1 bash .hermes/start-app.sh
```

这条路径是**设计内的**：判列走规则基线、情景只能手工构造、AI 相关接口返回错误码 `1001`（未启用）。
它和 `1005`（上游欠费/限流）必须区分开 —— 前者是正常降级，后者是外部依赖故障。

---

## 2. 需要哪些配置

**所有配置都有可用默认值，全部可以用环境变量覆盖。** 配置项都在 `src/main/resources/application.properties`。

### 2.1 数据库（必需）

| 环境变量 | 默认值 | 说明 |
|---|---|---|
| `DB_HOST` | `localhost` | MySQL 地址 |
| `DB_PORT` | `3306` | 端口 |
| `DB_NAME` | `whatif_lab` | 库名 |
| `DB_USERNAME` | `root` | 用户名 |
| `DB_PASSWORD` | `root` | 密码 —— **默认值是本机开发用的，部署时务必用环境变量传真实密码** |

### 2.2 服务端口与 Redis

| 环境变量 | 默认值 | 说明 |
|---|---|---|
| `SERVER_PORT` | `8088` | 服务端口 |
| `REDIS_HOST` | `127.0.0.1` | Redis 地址（可选依赖） |
| `REDIS_PORT` | `6379` | 端口 |
| `REDIS_DB` | `5` | **本项目固定用 5 号库**，不会碰你的其它库 |
| `WHATIF_CACHE_ENABLED` | `true` | 结果缓存；关掉只是变慢 |

### 2.3 AI（可选 —— 不配就是降级模式）

| 环境变量 | 默认值 | 说明 |
|---|---|---|
| `WHATIF_AI_ENABLED` | `true` | 总开关 |
| `WHATIF_AI_PROVIDER` | `dashscope` | 供应商：`dashscope` / `deepseek` / `moonshot` / `ollama` / `openai-compatible` |
| `WHATIF_AI_BASE_URL` | 空（用官方默认） | 自建网关填这里。**注意：不带 `/v1`** |
| `WHATIF_AI_MODEL` | `qwen-plus` | 模型名 |
| `WHATIF_AI_API_KEY` | 空 | 你的密钥 → 写法见 [第 3 节](#3-密钥写在哪重要) |

常见组合：

```bash
# 阿里云百炼
WHATIF_AI_PROVIDER=dashscope   WHATIF_AI_MODEL=qwen-plus
# DeepSeek
WHATIF_AI_PROVIDER=deepseek    WHATIF_AI_MODEL=deepseek-chat
# 本地 Ollama（完全离线）
WHATIF_AI_PROVIDER=ollama      WHATIF_AI_BASE_URL=http://127.0.0.1:11434  WHATIF_AI_MODEL=qwen2.5:7b
# 任意 OpenAI 兼容网关
WHATIF_AI_PROVIDER=openai-compatible  WHATIF_AI_BASE_URL=https://your-gateway.example.com/path  WHATIF_AI_MODEL=your-model
```

### 2.4 其它（一般不用改）

| 配置项 | 默认值 | 说明 |
|---|---|---|
| `WHATIF_DOMAINS_DIR` | `domains` | 领域配置目录。**新增一个行业 = 往这里丢一个 JSON + 重启，零 Java 改动** |
| `WHATIF_DISPATCH_MODE` | `local` | 实验调度：`local`（默认）或 `kafka`（需自备 Kafka） |
| `whatif.simulation.default-simulations` | `200` | 默认蒙特卡洛次数 |
| `whatif.simulation.active-customers` | `1000` | 每次仿真抽样的客户数（**绝对量级随它变，比较只看两臂相对变化**） |
| `whatif.simulation.candidates-per-customer` | `8` | 每客户每会话的候选商品数 |
| `whatif.simulation.gross-margin` | `0.35` | **毛利率假设**（成本 = 原价 × (1 − 毛利率)）。数据里没有成本列，所以利润结论对这个参数敏感 |
| `whatif.simulation.negative-ratio` | `4` | 训练时的隐式反馈负采样倍率 |

---

## 3. 密钥写在哪（重要）

### 3.1 规则

> **真实密钥只能存在于两个地方：`.hermes/ai.env`（本机文件，已被 git 忽略），或者进程环境变量。**
> 绝不要把它写进 `application.properties`、README、脚本、注释、提交信息 —— 任何被 Git 跟踪的文件。

### 3.2 推荐用法：本机文件

```bash
cp .hermes/ai.env.example .hermes/ai.env     # 模板里有全部变量名和示例
vi .hermes/ai.env                            # 填你自己的值
bash .hermes/start-app.sh                    # 启动脚本会自动 source 它
```

`.hermes/ai.env` 里长这样（**模板里全是占位符，没有也不可能有真实值**）：

```bash
export WHATIF_AI_PROVIDER=openai-compatible
export WHATIF_AI_BASE_URL=https://your-gateway.example.com/your/path
export WHATIF_AI_MODEL=your-model-name
export WHATIF_AI_API_KEY=your-api-key-here
```

### 3.3 为什么它不会上传（三道锁）

1. `.gitignore` 第一节就写着 `.hermes/ai.env`、`.env`、`*.env`（并显式放行 `ai.env.example` 模板）。
2. **提交前扫描**：`bash .hermes/verify-no-secrets.sh` —— 它会用你 `ai.env` 里的**真实值**反扫整个仓库，
   再用通用密钥模式（`sk-*`/`AKIA*`/私钥头/`ghp_*`）扫一遍，还会检查关键文件是否真的处于「被忽略」状态。
   退出码非 0 就表示**禁止提交**。
3. **配置项本身是占位符**：`application.properties` 里只有 `${WHATIF_AI_API_KEY:...}` 这样的引用，
   源码里不存在任何真实密钥（本项目已用真实 key 值全库反扫确认过：只命中 `ai.env` 一个文件）。

> 如果你不小心提交过密钥：**先去供应商后台吊销/重置那个 key**，再清理 Git 历史。
> 只删文件是没用的 —— 历史里的东西依然可以被检出。

---

## 4. 把数据放进来

```bash
# 方式一：直接给文件路径导入
curl -X POST -H 'Content-Type: application/json' \
     -d '{"path":"C:/data/orders.csv","name":"我的订单","domain":"ecommerce-generic"}' \
     http://127.0.0.1:8088/api/datasets/import-path

# 方式二：先画像看数据长什么样（编码/分隔符/每列类型/空值率/分位数）
curl -X POST -H 'Content-Type: application/json' -d '{"path":"C:/data/orders.csv"}' \
     http://127.0.0.1:8088/api/datasets/analyze

# 方式三：让 AI 判列 —— 看它认为每列是什么角色、这张表能不能做 What-If
#        （控制台里也有一块「AI 判列」面板，同样的事点着鼠标就能做）
curl -X POST -H 'Content-Type: application/json' -d '{"path":"C:/data/orders.csv"}' \
     http://127.0.0.1:8088/api/ai/schema/propose
```

**领域（domain）是配置，不是代码**：`domains/*.json` 声明"哪一列是客户、哪种事件算成交、金额怎么算"。
仓库里自带 `ecommerce-generic`（通用电商）与 `gaming`（把同一份订单数据解释成游戏内购）两个例子。
新增行业只要丢一个 JSON 文件即可，不改一行 Java。

---

## 5. 主要接口

| 接口 | 方法 | 作用 |
|---|---|---|
| `/api/health` | GET | 健康检查（MySQL / Redis / AI 三项真探活） |
| `/api/dashboard` | GET | 概览 |
| `/api/datasets` | GET | 数据集列表 |
| `/api/datasets/analyze` | POST | 数据画像（编码/分隔符/类型/空值率/分位数） |
| `/api/datasets/import-path` | POST | 按路径导入 CSV |
| `/api/datasets/{id}/profile` | GET | 导入后的业务画像（事件/实体/时间范围） |
| `/api/datasets/{id}/snapshot-diag` | GET | 时间切分泄漏检查（三条必须全绿） |
| `/api/datasets/{id}/model/train` | POST | 训练行为模型（返回 AUC / LogLoss / 样本数） |
| `/api/ai/schema/producer` | GET | 当前判列生产者（AI 还是规则基线 + 模型 + 提示词版本） |
| `/api/ai/schema/propose` | POST | AI 判列（提案：每列角色 + 证据 + 工况 + 未决项） |
| `/api/ai/schema/{id}/confirm` | POST | 人工确认并冻结成 `domains/<领域名>.json` |
| `/api/ai/scenario/compile` | POST | 自然语言 → Scenario DSL |
| `/api/experiments` | POST | 跑实验（基线 vs 情景，配对蒙特卡洛） |
| `/api/experiments/{id}/table` | GET | 结果表（均值 / P5 / P95 / 变化率 / p 值 / 置信区间 / 效应量） |
| `/api/experiments/{id}/progress` | GET | SSE 实时进度 |
| `/api/ai/explain/{id}` | POST | 把结果翻译成人话（数字全部来自引擎） |
| `/api/ai/ask` | POST | Agent 问答（自主调工具查数据/跑实验） |

---

## 6. 目录结构

```
src/main/java/com/whatif/lab/
├─ data/extract/        领域配置加载 + 通用抽取（换行业零代码）
├─ data/profile/        CSV 客观画像（编码/分隔符/类型/分布）
├─ ai/schema/           AI 判列：LLM 只判角色，配置由确定性代码推导
├─ simulation/snapshot/ 数据集快照 + 70/30 时间切分（防泄漏）
├─ simulation/behavior/ 特征集（配置化）+ 训练集构造 + 自写逻辑回归
├─ simulation/rule/     规则引擎（3 种规则，真被执行）
├─ simulation/          蒙特卡洛引擎（两臂公共随机数）+ 配对检验
├─ ai/agent/            情景编译 / 结果解释 / Agent 工具调用
├─ cache/ task/         缓存幂等（含引擎版本号）/ 调度 / SSE 进度
└─ api/                 控制器

domains/*.json                                  领域配置（外部目录，丢文件 + 重启即生效）
src/main/resources/domains/*.json               内置领域配置（随 jar 发布）
src/main/resources/features/*.json              特征集配置（按作用域分桶：客户/商品/价格/折扣/成对）
src/main/resources/db/schema.sql   建表脚本（12 张表，可重复执行）
.hermes/            启动 / 停止 / 打包包装 + 密钥模板 + 提交前密钥扫描
```

---

## 7. 常见问题

**启动失败，说端口被占用？**
脚本会拒绝启动并返回退出码 4（不会悄悄换端口）。先 `bash .hermes/stop-app.sh`，或换 `SERVER_PORT=8089`。

**打包后启动报「没有主清单属性」？**
Windows 上应用在跑时打包会把 jar 锁住，产物损坏。**先 `stop-app.sh` 再 `package`**（启动脚本现在会先自检 jar 完整性并明确报出来）。

**AI 相关功能报错 1001 / 1005 分不清？**
`1001` = 没配 Key 或总开关关了 → 正常降级，确定性链路（手工构造情景 → 跑实验）完全可用；
`1005` = 上游欠费/限流/超时 → 外部依赖故障，充值或稍后重试即可。平台把两者分开了，就是为了不让你误判。

**CSV 是中文/西欧编码、或者用分号分隔怎么办？**
画像层能自动探测编码（含 cp1252 等兜底）和分隔符并如实上报；目前**分号分隔的表可以画像、但导入路径按逗号解析**，属于已知缺口。

**Redis 挂了会怎样？**
缓存降级为未命中、分布式锁不会假装拿到（错误码 1006）。**只会变慢，不会算错** —— 这是刻意设计。

**想换 AI 供应商？**
改 `WHATIF_AI_PROVIDER` / `BASE_URL` / `MODEL` 即可，无需改代码。自建网关注意 base-url **不要带 `/v1`**。

---

## 8. 这个项目里 AI 干了什么、没干什么

**AI 的三个落点（都有确定性护栏）**

| 落点 | AI 的输出 | 护栏 | 无 Key 时 |
|---|---|---|---|
| 判列 | 每列的角色 + 置信度 + 证据 | 角色闭集 + 确定性校验器（唯一性/必需角色/金额算法/置信度门禁）+ 人工确认 | 走规则基线，同一套校验 |
| 情景编译 | 自然语言 → Scenario DSL | 每个改动必须对得上当前规则的真实字段 | 手工构造（永远可用） |
| 问答/解释 | 人话解释、工具调用 | 数字只能引用引擎结果 | 返回结构化结果 |

**两条红线**：① LLM 不产生业务数字；② LLM 不执行模拟代码。
为什么：AI 弄错的后果是"看不出来" —— 判错的列、选错的方法，产出的数字依然格式正确、量级合理。
把它关在"只输出结构化判断"的小盒子里，才可能用确定性代码把它拦住。

---

## 9. 已知边界（诚实清单）

| 做不到 | 原因 / 现状 |
|---|---|
| 只有一种模拟形态 | 实体 × 对象"会不会发生"。时间序列 / 排队 / 离散事件三种形态**刻意没做**（每种都需要各自独立的验收方式） |
| 非交易型数据结论无意义 | 能导入、能画像，但模型看到的仍是零售口径特征 —— 换领域只换了"数据怎么解释"，没换"问题是什么形态" |
| 分号分隔的表不能导入 | 只能画像（见 FAQ） |
| 利润结论依赖假设 | 毛利率是配置参数（默认 35%），不是数据里学出来的 |
| 绝对量级不可直接对外 | 仿真是"每客户每会话抽 K 个候选"的采样口径，请只看两臂相对变化（结果里会给出缩放系数作参考） |
| Docker / Kafka 未验证 | compose 与 Kafka 实现保留为可选路径，未经端到端验证 |

---

## 10. 免责声明

本项目是个人学习与技术验证作品。数据来自公开数据集，其中的"业务规则"（满减、毛利率等）是**假设值**，不代表任何真实业务。
模拟结果用于演示方法论，不构成任何决策建议。
