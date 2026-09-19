package com.whatif.lab.data.profile;

import com.whatif.lab.data.csv.CsvTable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 分隔符检测与「按指定分隔符解析」的自检。
 *
 * <p><b>为什么这个纯函数值得单测</b>：它错了不会抛异常，只会让一份 17 列的表被读成 1 列 ——
 * 而且下游一切"正常"（列名叫 {@code age;job;marital;...}，统计照样出数）。
 * 这正是本项目最忌讳的那类 bug：看着有结果，其实全错。
 * 真实案例：UCI Bank Marketing 用的是分号分隔，写死逗号就会踩这个坑。
 *
 * <p>断言用的是**可手算的形状**（几个字段、几个分隔符），不依赖任何外部数据文件，
 * 所以它跑得快、且能在总验收的第 0 层（mvn test）里被覆盖。
 */
class CsvProfilerTest {

    private Path csv(String content) throws IOException {
        Path p = Files.createTempFile("profile-delim", ".csv");
        Files.writeString(p, content, StandardCharsets.UTF_8);
        p.toFile().deleteOnExit();
        return p;
    }

    @Test
    @DisplayName("逗号 / 分号 / 制表符 / 竖线 四种分隔符都能认出来")
    void detectsCommonDelimiters() throws IOException {
        assertEquals(',', CsvProfiler.detectDelimiter(csv("a,b,c\n1,2,3\n"), StandardCharsets.UTF_8));
        assertEquals(';', CsvProfiler.detectDelimiter(csv("a;b;c\n1;2;3\n"), StandardCharsets.UTF_8));
        assertEquals('\t', CsvProfiler.detectDelimiter(csv("a\tb\tc\n1\t2\t3\n"), StandardCharsets.UTF_8));
        assertEquals('|', CsvProfiler.detectDelimiter(csv("a|b|c\n1|2|3\n"), StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("引号里的分隔符不算数（「Smith, John」不能把逗号数进去）")
    void ignoresDelimitersInsideQuotes() throws IOException {
        // 表头：a ; "b,c" ; d  → 逗号在引号内(不算)，分号 2 个 → 判定为分号
        assertEquals(';', CsvProfiler.detectDelimiter(
                csv("a;\"b,c\";d\n1;2;3\n"), StandardCharsets.UTF_8));
        // 反过来：逗号 2 个、分号只在引号内 → 判定为逗号
        assertEquals(',', CsvProfiler.detectDelimiter(
                csv("a,\"b;c\",d\n1,2,3\n"), StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("单列文件没有分隔符 → 退回默认逗号（而不是判成某个奇怪字符）")
    void singleColumnFallsBackToComma() throws IOException {
        assertEquals(',', CsvProfiler.detectDelimiter(csv("only\n1\n2\n"), StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("空行不影响判定（取第一行非空内容）")
    void skipsLeadingBlankLines() throws IOException {
        assertEquals(';', CsvProfiler.detectDelimiter(csv("\n\n a;b;c\n"), StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("CsvTable 按指定分隔符解析：分号能解出 3 列，逗号只能解出 1 列")
    void tableHonoursDelimiter() throws IOException {
        try (StringReader r1 = new StringReader("a;b;c\n1;2;3\n")) {
            assertEquals(3, CsvTable.open(r1, ';').header().size());
        }
        try (StringReader r2 = new StringReader("a;b;c\n1;2;3\n")) {
            assertEquals(1, CsvTable.open(r2, ',').header().size(),
                    "写死逗号时整行只有一个字段 —— 这就是 bank-full.csv 曾经的读法");
        }
    }
}
