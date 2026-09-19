package com.whatif.lab.data.extract;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 时间解析工具：把「一个字符串里的时间」转成 {@link LocalDateTime}。
 *
 * <p>为什么把格式清单写死、并且失败要显式计数：日期格式猜错的后果不是抛异常，
 * 而是「全部解析失败 → 事件时间全为 null → 行为模型的时间特征全废」。
 * 这里宁可在抽取阶段就把失败行数报出来（进 {@link DataQualityReport#skipReasons()}），
 * 也不允许静默丢弃。
 */
public final class TimeParser {

    private static final List<DateTimeFormatter> DATE_TIME_FORMATS = List.of(
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd H:mm:ss"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd H:mm"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"),
            DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss"),
            DateTimeFormatter.ofPattern("yyyy/MM/dd H:mm"),
            DateTimeFormatter.ofPattern("yyyy/M/d H:mm"),
            DateTimeFormatter.ofPattern("M/d/yyyy H:mm"),
            DateTimeFormatter.ofPattern("M/d/yyyy HH:mm:ss"),
            DateTimeFormatter.ofPattern("M/d/yyyy H:mm:ss"),
            // UCI Online Retail 的真实格式是「12/1/10 8:26」——两位年份 + 小时不补零。
            // Java 的 "yy" 按 2000+ 归约，正好落在 2010~2011，符合数据实际年份。
            // 这两个格式当初漏了，会让全部 54 万行时间解析失败而被静默跳过 ——
            // 所以数据质量报告里必须能看到「时间无法解析」这一项，否则这种错会一路静默到指标。
            DateTimeFormatter.ofPattern("M/d/yy H:mm"),
            DateTimeFormatter.ofPattern("M/d/yy HH:mm:ss"),
            DateTimeFormatter.ofPattern("M/d/yy H:mm:ss"),
            DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm"),
            DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));

    private static final List<DateTimeFormatter> DATE_FORMATS = List.of(
            DateTimeFormatter.ofPattern("yyyy-MM-dd"),
            DateTimeFormatter.ofPattern("yyyy/MM/dd"),
            DateTimeFormatter.ofPattern("yyyy/M/d"),
            DateTimeFormatter.ofPattern("M/d/yyyy"),
            DateTimeFormatter.ofPattern("dd/MM/yyyy"),
            DateTimeFormatter.ofPattern("yyyyMMdd"));

    private TimeParser() {
    }

    public static LocalDateTime parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String v = raw.trim().replace('T', ' ');
        for (DateTimeFormatter f : DATE_TIME_FORMATS) {
            try {
                return LocalDateTime.parse(v, f);
            } catch (Exception ignored) {
                // 试下一个格式
            }
        }
        for (DateTimeFormatter f : DATE_FORMATS) {
            try {
                return LocalDate.parse(v, f).atStartOfDay();
            } catch (Exception ignored) {
                // 试下一个格式
            }
        }
        return null;
    }
}
