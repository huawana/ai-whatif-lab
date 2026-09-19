package com.whatif.lab.data.extract;

import com.whatif.lab.data.ColumnMapping;
import com.whatif.lab.data.csv.CsvTable;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 电商域适配器：UCI Online Retail 形态的「一行一笔交易明细」→ Entity/Event。
 *
 * <p>这是本项目里<b>唯一一处</b>写死业务语义的地方（哪一列是客户、取消怎么判、负数量怎么处理）。
 * 换行业时新增一个实现即可，仿真引擎、行为模型、指标口径一行都不用改 ——
 * 这正是「核心引擎不绑定行业」的验证点：demo 换域只换这个类。
 *
 * <p>容错原则：<b>脏行必须计数，绝不静默丢弃</b>。
 * UCI 原始数据里有无客户 ID 的匿名交易、有单价为 0 的费用行（POST/AMAZON FEE）、
 * 有以 C 开头的取消单 —— 它们各自有明确的业务含义：
 * <ul>
 *   <li>无客户 ID → 无法归属行为主体，丢弃并计数；</li>
 *   <li>单价 ≤ 0 → 不是商品行（运费/调整），参与价格建模会污染价格弹性，丢弃并计数；</li>
 *   <li>取消单（发票号以 C 开头或数量为负）→ 是真实的负向行为，保留成 CANCEL 事件
 *       （后续可衡量取消率），但<b>不参与购买概率训练</b>。</li>
 * </ul>
 */
@Component
public class EcommerceExtractor implements DomainExtractor {

    public static final String CUSTOMER = "Customer";
    public static final String PRODUCT = "Product";
    public static final String EVENT_PURCHASE = "PURCHASE";
    public static final String EVENT_CANCEL = "CANCEL";

    @Override
    public String domain() {
        return "ecommerce";
    }

    @Override
    public void extract(CsvTable table,
                        ColumnMapping mapping,
                        Consumer<RawEvent> events,
                        DataQualityReport.Builder report,
                        EntityAccumulator entities) throws IOException {

        int cEntity = table.indexOf(mapping.column(ColumnMapping.ENTITY_ID));
        int cProduct = table.indexOf(mapping.column(ColumnMapping.PRODUCT_ID));
        int cTime = table.indexOf(mapping.column(ColumnMapping.EVENT_TIME));
        int cQty = table.indexOf(mapping.column(ColumnMapping.QUANTITY));
        int cPrice = table.indexOf(mapping.column(ColumnMapping.UNIT_PRICE));
        int cOrder = table.indexOf(mapping.column(ColumnMapping.ORDER_ID));
        int cDesc = table.indexOf(mapping.column(ColumnMapping.DESCRIPTION));
        int cGroup = table.indexOf(mapping.column(ColumnMapping.GROUP));

        if (cEntity < 0 || cProduct < 0 || cTime < 0 || cQty < 0 || cPrice < 0) {
            throw new IllegalArgumentException("列映射不完整，缺少必填列");
        }

        table.forEach(row -> {
            report.rawRow();

            String entityId = CsvTable.cell(row, cEntity);
            if (entityId == null) {
                report.skip("缺少客户ID(匿名交易)");
                return;
            }
            String productId = CsvTable.cell(row, cProduct);
            if (productId == null) {
                report.skip("缺少商品编码");
                return;
            }
            LocalDateTime time = DomainExtractor.time(CsvTable.cell(row, cTime));
            if (time == null) {
                report.skip("时间无法解析");
                return;
            }
            Integer qty = DomainExtractor.parseInt(CsvTable.cell(row, cQty));
            if (qty == null || qty == 0) {
                report.skip("数量为0或非法");
                return;
            }
            BigDecimal price = DomainExtractor.parseDecimal(CsvTable.cell(row, cPrice));
            if (price == null || price.signum() <= 0) {
                report.skip("单价<=0(非商品行/费用行)");
                return;
            }

            String orderId = CsvTable.cell(row, cOrder);
            boolean cancel = qty < 0 || (orderId != null && orderId.toUpperCase(Locale.ROOT).startsWith("C"));
            int absQty = Math.abs(qty);
            BigDecimal amount = price.multiply(BigDecimal.valueOf(absQty));

            String description = CsvTable.cell(row, cDesc);
            String group = CsvTable.cell(row, cGroup);

            // 实体属性（同一实体多行时会反复写入，值相同，幂等）
            entities.entity(CUSTOMER, entityId, "group", group);
            if (description != null) {
                entities.entity(PRODUCT, productId, "description", description);
            }
            entities.entity(PRODUCT, productId, "lastUnitPrice", price);

            events.accept(new RawEvent(entityId,
                    cancel ? EVENT_CANCEL : EVENT_PURCHASE,
                    productId,
                    orderId,
                    time,
                    absQty,
                    price,
                    amount));
            report.acceptedEvent(time, cancel);
        });
    }

    /** 供导入服务复用的属性读取：客户分组名。 */
    public static Map<String, Object> customerAttributes(String group) {
        return group == null ? Map.of() : Map.of("group", group);
    }
}
