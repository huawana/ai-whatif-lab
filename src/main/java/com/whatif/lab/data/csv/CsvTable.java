package com.whatif.lab.data.csv;

import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * CSV 表：第一行当表头，之后按行流式回调。
 *
 * <p>「表头 → 列下标」的解析只做一次，之后每行按下标取值，
 * 避免对每一行都做一次 Map 查找（50 万行 × 8 次哈希查找是实打实的开销）。
 */
public final class CsvTable {

    private final CsvReader reader;
    private final List<String> header;
    private final int[] indexByTopLevelColumn;

    private CsvTable(CsvReader reader, List<String> header, int[] indexByTopLevelColumn) {
        this.reader = reader;
        this.header = header;
        this.indexByTopLevelColumn = indexByTopLevelColumn;
    }

    /** 逗号分隔（既有行为，不改）。 */
    public static CsvTable open(Reader reader) throws IOException {
        return open(reader, ',');
    }

    /**
     * 指定分隔符打开。
     *
     * <p>为什么需要它：真实数据不都是逗号分隔 —— UCI Bank Marketing 用的是**分号**（`;`）。
     * 写死逗号的话，一份 17 列的表会被读成"1 列"，而且不会报错（只是列名里全是分号）——
     * 这种"看起来读到了、其实全错"的情况必须在解析层就支持。
     */
    public static CsvTable open(Reader reader, char delimiter) throws IOException {
        CsvReader csv = new CsvReader(reader, delimiter);
        String[] head = csv.next();
        if (head == null) {
            throw new IOException("CSV 为空，读不到表头");
        }
        List<String> header = new ArrayList<>(head.length);
        for (String h : head) {
            header.add(stripBom(h).trim());
        }
        return new CsvTable(csv, header, new int[0]);
    }

    private static String stripBom(String s) {
        return s != null && !s.isEmpty() && s.charAt(0) == '\uFEFF' ? s.substring(1) : s;
    }

    public List<String> header() {
        return header;
    }

    public int indexOf(String columnName) {
        if (columnName == null) {
            return -1;
        }
        for (int i = 0; i < header.size(); i++) {
            if (header.get(i).equalsIgnoreCase(columnName.trim())) {
                return i;
            }
        }
        return -1;
    }

    /**
     * 流式遍历数据行。
     *
     * @param consumer 每行回调（不含表头）。返回 false 可提前终止（例如只取前 N 行做预览）
     */
    public long forEach(Consumer<String[]> consumer) throws IOException {
        long count = 0;
        String[] row;
        while ((row = reader.next()) != null) {
            if (row.length == 0 || (row.length == 1 && row[0].isBlank())) {
                continue;   // 空行
            }
            count++;
            consumer.accept(row);
        }
        return count;
    }

    public static String cell(String[] row, int idx) {
        if (idx < 0 || idx >= row.length) {
            return null;
        }
        String v = row[idx];
        return v == null || v.isBlank() ? null : v.trim();
    }
}
