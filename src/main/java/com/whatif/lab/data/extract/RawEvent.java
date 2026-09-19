package com.whatif.lab.data.extract;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 抽取出来的原始事件（尚未落库）。
 *
 * <p>与 {@code EventPO} 分开的理由：事件在「原始数据 → 标准事件」之间要过一次
 * 领域适配（哪些行算事件、什么算取消、数量怎么定符号）。
 * 让 PO 承担解析中间态会让 PO 被 IO 细节污染，也让领域适配层没法独立单测。
 */
public record RawEvent(String entityExternalId,
                       String eventType,
                       String productExternalId,
                       String orderExternalId,
                       LocalDateTime eventTime,
                       Integer quantity,
                       BigDecimal unitPrice,
                       BigDecimal amount) {
}
