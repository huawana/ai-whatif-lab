# 接入一个新领域（不用改代码）

你有一份自己的 CSV，想跑"What-If"分析。**不需要写 Java、不需要重新打包**，只需要：

1. 往**本目录**（默认 `domains/`，可用环境变量 `WHATIF_DOMAINS_DIR` 改）放一份 `*.json`；
2. 重启应用；
3. 导入时把 `domain` 指定成你配置里的 `domain` 值。

验证是否生效：

```bash
curl -s localhost:8088/api/datasets/domains | python -m json.tool
```

会返回：认出来的领域清单、生效的配置原文、以及**每份配置的问题**（写错了会明确告诉你错在哪）。
也可以用前端的「数据集」卡片，或直接看启动日志里的 `已注册领域: [...]`。

```bash
# 导入示例（服务端本地路径导入）
curl -X POST localhost:8088/api/datasets/import-path \
  -H 'Content-Type: application/json' \
  -d '{"path":"C:/data/your_file.csv","name":"your-dataset","domain":"gaming",
       "mappingJson":"{\"entityId\":\"PlayerId\",\"productId\":\"ItemId\",\"eventTime\":\"Time\",\"quantity\":\"Qty\",\"unitPrice\":\"Price\"}"}'
```

---

## 这个平台的领域模型（先理解这个，再填配置）

数据被抽象成两样东西，**核心引擎只认这两样**（所以它不绑行业）：

```
实体 Entity   谁 / 什么     —— 例：Customer、Player、User、Device
事件 Event    某实体在某时刻对一个对象做了什么  —— 例：购买、退款、使用、点击
```

一行 CSV 被解释成一条事件 + 若干实体属性。**所有行业差异都收敛在「怎么解释」这一步**，
而这一步现在完全由配置决定。

---

## 配置字段手册

### 顶层字段

| 字段 | 必填 | 说明 |
|---|---|---|
| `domain` | ✅ | 领域名（唯一标识，导入时用它） |
| `description` | | 说明文字，会出现在接口里 |
| `entityTypes` | | `{"main":"Customer","item":"Product"}` —— 行为主体与被操作对象叫什么 |
| `eventTypes` | | `{"positive":"PURCHASE","negative":"CANCEL","neutral":"EVENT"}` —— 事件类型名 |
| `requiredFields` | | 必须有列映射的标准字段，默认 `["entityId","productId","eventTime","quantity","unitPrice"]` |
| `fieldTypes` | | 字段类型语义：`time` / `int` / `decimal` / `string`，默认 `{eventTime:time, quantity:int, unitPrice:decimal}` |
| `skip` | | 跳过规则（脏行，按顺序判定，命中第一条即跳过并**计数**） |
| `classify` | | 事件分类规则（按顺序判定，命中第一条决定事件归入哪个 bucket） |
| `defaultEventType` | | 都不命中时的 bucket，默认 `positive` |
| `absoluteQuantity` | | 事件数量是否取绝对值，默认 `true` |
| `amount` | | 金额算法：`{"op":"multiply","abs":["quantity"],"with":["unitPrice"]}`；`{"op":"none"}` = 无金额概念（记 0） |
| `entityAttributes` | | 实体属性：`[{"target":"main","name":"group","from":"group","type":"string"}]` |

**标准字段名**（`field` 只能用这些，原始列名由列映射负责）：

```
entityId     行为主体 ID（客户 / 玩家 / 用户）   ← 必填
productId    被操作对象 ID（商品 / 道具）        ← 必填
eventTime    事件时间                            ← 必填
quantity     数量
unitPrice    单价
orderId      单据号 / 会话号
description  描述文本
group        分组 / 地区 / 渠道
```

### 算子（闭合词汇表，没有通用表达式）

`skip[].op` 与 `classify[].op` 只能用下面这些 —— 这是刻意的：配置能表达什么必须能被枚举，
否则既没法写验收脚本，也没法说清"这份配置到底会做什么"。

| op | 含义 |
|---|---|
| `blank` | 空或缺失 |
| `unparsable` | 按 `fieldTypes` 声明的类型解析失败（时间列 / 整数列 / 小数列含义不同） |
| `eq` `ne` | 等于 / 不等于（能解析成数就按数比，否则按字符串） |
| `lt` `lte` `gt` `gte` | 数值比较（无法解析 → 视为不满足） |
| `startsWith` `endsWith` `contains` | 字符串判定 |
| `in` | 取值在给定数组里 |

`classify[].as` 只能是 `positive` / `negative` / `neutral`。
**`negative` 的语义很重要**：它会被记录成事件，但**不参与购买概率训练**（例如退货、取消）。

---

## 一个最小例子

```json
{
  "domain": "saas-usage",
  "description": "SaaS 用量：一行 = 某用户在某个时间点使用了一次某个功能",
  "entityTypes": { "main": "User", "item": "Feature" },
  "eventTypes": { "positive": "USAGE" },
  "requiredFields": ["entityId", "productId", "eventTime", "quantity"],
  "fieldTypes": { "eventTime": "time", "quantity": "decimal" },
  "skip": [
    { "field": "entityId", "op": "blank", "reason": "缺少用户ID" },
    { "field": "eventTime", "op": "unparsable", "reason": "时间无法解析" },
    { "field": "quantity", "op": "lte", "value": 0, "reason": "用量<=0" }
  ],
  "defaultEventType": "positive",
  "amount": { "op": "none" }
}
```

注意几个能做的事：`requiredFields` 里**没有** `unitPrice`（这个领域没有金额），
`amount` 用 `none`，`fieldTypes.quantity` 声明成 `decimal`（用量可以是小数）。

---

## 常见坑（都踩过）

1. **`field` 里写 CSV 原始列名** → 报"需要列映射字段 X，但导入请求里没有映射它"。
   配置里写**标准字段名**（`entityId` 等），原始列名通过 `mappingJson` 映射；
2. **`skip` 里引用了一个没映射的可选字段，条件是 `blank`** → **每一行都会被跳过**（因为该字段永远是空）。
   先确认这个字段在 `mappingJson` 里映射了，再写 `blank` 条件；
3. **键名拼错**（如 `classfy`）→ 会在 `/api/datasets/domains` 的 `problems` 里明确报出来，
   **不会静默生效**；这是刻意的设计（宽松的 JSON 解析会把拼错的键当没写）；
4. **配置目录里放了非 JSON 的临时文件** → 只处理 `*.json`，其它文件忽略；
5. **改了配置没重启** → 配置在启动时加载一次；改完要重启应用。

---

## 这个配置化做到哪一层了（诚实说明）

- ✅ **数据接入层**：完全配置化 —— 换领域不用改代码（本目录的例子 `gaming.json` 就是证明：
  全项目 Java 源码里没有一处提到 gaming，但它能被导入）。
- ⚠️ **特征层**：`FeatureSpec` 里的特征（消费额 / 购买频次 / 商品亲密度 / 复购间隔 / 折扣敏感）
  目前仍写死，属于零售语义。换行业到"同一类行为（会不会发生 + 频率/金额/亲密度）"可用；
  换成完全不同的决策机制则需要扩展特征词汇表。
- ⚠️ **规则与指标层**：`RuleType`（满减 / 价格系数 / 概率系数）与 `MetricType`（GMV / 订单量 /
  转化率 / 优惠成本 / 利润 / 客单价 / 毛收入）是枚举，仍是零售口径。
- ❌ **决策模型形态**：现在是"主体 × 对象 → 会不会买"的二分类。换成"会不会流失 / 会不会复发"
  需要重新设计，不是加几个字段就行。

> 一句话：**骨架（实体/事件/DSL/仿真/缓存/AI）是通用的，语义（特征/规则/指标/模型形态）目前是零售的。**
> 配置化先把最容易卡住使用者的那一层（数据接入）彻底解决。
