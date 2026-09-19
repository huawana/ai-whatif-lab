package com.whatif.lab.data;

import com.whatif.lab.data.csv.CsvReader;
import com.whatif.lab.data.csv.CsvTable;
import com.whatif.lab.data.extract.TimeParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 数据接入层的纯逻辑测试。
 *
 * <p>为什么这几条最值得测：它们全都属于「错了也不报错」的类型 ——
 * <ul>
 *   <li>CSV 引号/换行处理错 → 列错位，指标算错但程序正常运行；</li>
 *   <li>CR 换行没处理 → 整份文件被当成一行，导入 0 条；</li>
 *   <li>时间格式猜错 → 事件时间全 null，行为模型的时间特征全废；</li>
 *   <li>列映射猜错 → 模型学到一堆垃圾规律。</li>
 * </ul>
 * 这四个坑在真实数据（UCI Online Retail 54 万行）上全部踩了一遍，
 * 所以下面的样例都来自真实数据形态，不是凭空构造的。
 *
 * <p><b>测试数据为什么用字符常量拼，而不是写 {@code "\n"} / {@code "\r"} 转义</b>：
 * 转义序列是「看着一样、语义完全不同」的重灾区 ——
 * {@code "\\n"}（字面反斜杠+n）与 {@code "\n"}（换行）在屏幕上几乎一样，
 * 但前者会让「按行切分」的测试在错误的前提下通过/失败。
 * 用 {@link #LF} / {@link #CR} 这些具名常量后，测试意图在代码里是显式的、不可能被转义规则改写。
 */
class DataLayerTest {

    /** 换行（LF）。 */
    private static final char LF = (char) 10;
    /** 回车（CR）。UCI 那份 CSV 用的就是 CR 单字符换行。 */
    private static final char CR = (char) 13;
    /** 双引号。 */
    private static final char Q = (char) 34;
    /** UTF-8 BOM。 */
    private static final char BOM = (char) 0xFEFF;

    private static String line(String... cells) {
        return String.join(",", cells);
    }

    private static List<String[]> readAll(String csv) throws Exception {
        List<String[]> rows = new ArrayList<>();
        try (CsvReader reader = new CsvReader(new StringReader(csv), ',')) {
            String[] row;
            while ((row = reader.next()) != null) {
                rows.add(row);
            }
        }
        return rows;
    }

    @Test
    @DisplayName("CSV：引号内的逗号不能被当成分隔符（真实数据里有这种行）")
    void handlesQuotedComma() throws Exception {
        String csv = "a,b,c" + LF
                + line("82567", Q + "AIRLINE LOUNGE,METAL SIGN" + Q, "2") + LF;
        List<String[]> rows = readAll(csv);
        assertEquals(2, rows.size());
        assertEquals(3, rows.get(1).length, "引号里的逗号不该把这一行切成 4 列");
        assertEquals("AIRLINE LOUNGE,METAL SIGN", rows.get(1)[1]);
    }

    @Test
    @DisplayName("CSV：转义双引号（两个引号 → 一个引号）")
    void handlesEscapedQuote() throws Exception {
        String csv = "a" + LF + Q + "say " + Q + Q + "hi" + Q + Q + " now" + Q + LF;
        List<String[]> rows = readAll(csv);
        assertEquals("say " + Q + "hi" + Q + " now", rows.get(1)[0]);
    }

    @Test
    @DisplayName("CSV：CR 单字符换行必须被识别（UCI 那份文件就是 CR，wc -l 数出 0 行）")
    void handlesCrOnlyLineEndings() throws Exception {
        String csv = "h1,h2" + CR + "1,2" + CR + "3,4" + CR;
        List<String[]> rows = readAll(csv);
        assertEquals(3, rows.size(), "CR 换行下应该有 表头 + 2 行数据");
        assertEquals("1", rows.get(1)[0]);
        assertEquals("3", rows.get(2)[0]);
    }

    @Test
    @DisplayName("CSV：CRLF 与 LF 混用也能正确切行")
    void handlesMixedLineEndings() throws Exception {
        String csv = "h" + CR + LF + "a" + LF + "b" + CR + LF;
        List<String[]> rows = readAll(csv);
        assertEquals(3, rows.size());
        assertEquals("a", rows.get(1)[0]);
        assertEquals("b", rows.get(2)[0]);
    }

    @Test
    @DisplayName("CSV：文件末尾没有换行时最后一行不能丢")
    void handlesLastLineWithoutNewline() throws Exception {
        String csv = "h1,h2" + CR + "1,2" + CR + "3,4";
        List<String[]> rows = readAll(csv);
        assertEquals(3, rows.size());
        assertEquals("3", rows.get(2)[0], "最后一行没有换行符，也必须被读出来");
    }

    @Test
    @DisplayName("CsvTable：BOM 必须被剥掉，否则第一列永远匹配不上")
    void stripsBom() throws Exception {
        CsvTable table = CsvTable.open(new StringReader(BOM + "InvoiceNo,StockCode" + LF + "1,2" + LF));
        assertEquals("InvoiceNo", table.header().get(0));
        assertEquals(0, table.indexOf("invoiceno"), "列名匹配应当忽略大小写");
        assertEquals(-1, table.indexOf("不存在"));
        assertNull(CsvTable.cell(new String[]{"a", " "}, 1), "空白单元格应视为 null");
        assertEquals("x", CsvTable.cell(new String[]{"x", "y"}, 0));
    }

    @Test
    @DisplayName("时间解析：UCI 真实格式 12/1/10 8:26（两位年份 + 小时不补零）")
    void parsesUciRealFormat() {
        assertEquals(LocalDateTime.of(2010, 12, 1, 8, 26), TimeParser.parse("12/1/10 8:26"));
        assertEquals(LocalDateTime.of(2011, 12, 9, 12, 50), TimeParser.parse("12/9/11 12:50"));
        assertEquals(Integer.valueOf(2010), TimeParser.parse("12/1/10 8:26").getYear(),
                "两位数年份必须落在 2010 而不是 1910 或 0010");
    }

    @Test
    @DisplayName("时间解析：其它常见格式与失败返回 null（失败必须可计数）")
    void parsesOtherFormatsAndFailsSafely() {
        assertEquals(LocalDateTime.of(2024, 3, 5, 14, 30, 59), TimeParser.parse("2024-03-05 14:30:59"));
        assertEquals(LocalDateTime.of(2024, 3, 5, 0, 0), TimeParser.parse("2024-03-05"));
        assertEquals(LocalDateTime.of(2024, 3, 5, 9, 5), TimeParser.parse("2024-03-05T09:05"));
        assertNull(TimeParser.parse("这不是时间"));
        assertNull(TimeParser.parse(""));
        assertNull(TimeParser.parse(null));
    }

    @Test
    @DisplayName("列映射建议：标准表头能全部命中，且置信度为 1.0")
    void mappingSuggesterHitsStandardHeaders() {
        var s = MappingSuggester.suggest(List.of("InvoiceNo", "StockCode", "Description", "Quantity",
                "InvoiceDate", "UnitPrice", "CustomerID", "Country"));
        assertEquals("CustomerID", s.mapping().column(ColumnMapping.ENTITY_ID));
        assertEquals("StockCode", s.mapping().column(ColumnMapping.PRODUCT_ID));
        assertEquals("InvoiceDate", s.mapping().column(ColumnMapping.EVENT_TIME));
        assertEquals("Quantity", s.mapping().column(ColumnMapping.QUANTITY));
        assertEquals("UnitPrice", s.mapping().column(ColumnMapping.UNIT_PRICE));
        assertEquals("1.0", s.confidence().get(ColumnMapping.ENTITY_ID));
        assertTrue(s.mapping().missingRequired().isEmpty());
    }

    @Test
    @DisplayName("列映射建议：认不出来的列必须漏出来（不能被硬猜成别的字段）")
    void mappingSuggesterLeavesUnknownColumnsUnmapped() {
        var s = MappingSuggester.suggest(List.of("col1", "col2", "col3"));
        assertTrue(s.mapping().fields().isEmpty(), "全是无意义列名时不应硬猜: " + s.mapping().fields());
        assertEquals(5, s.mapping().missingRequired().size(), "必填字段应全部报告缺失");
    }

    @Test
    @DisplayName("列映射建议：别名表头（uid/item/qty/price）也能命中")
    void mappingSuggesterHandlesAliases() {
        var s = MappingSuggester.suggest(List.of("uid", "item", "time", "qty", "price"));
        assertEquals("uid", s.mapping().column(ColumnMapping.ENTITY_ID));
        assertEquals("item", s.mapping().column(ColumnMapping.PRODUCT_ID));
        assertEquals("time", s.mapping().column(ColumnMapping.EVENT_TIME));
        assertEquals("qty", s.mapping().column(ColumnMapping.QUANTITY));
        assertEquals("price", s.mapping().column(ColumnMapping.UNIT_PRICE));
    }
}
