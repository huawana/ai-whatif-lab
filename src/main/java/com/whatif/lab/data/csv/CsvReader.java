package com.whatif.lab.data.csv;

import java.io.Closeable;
import java.io.IOException;
import java.io.Reader;

/**
 * 流式 CSV 读取器（RFC 4180）。
 *
 * <p><b>为什么自己写而不用现成库</b>：这个读法只有 80 行，但必须满足两个硬约束 ——
 * ① <b>不能把文件全读进内存</b>：UCI Online Retail 有 50 万+ 行，一次性
 * {@code readAllLines} + split 会让堆占用冲到几百 MB 且 GC 抖动极大；
 * 这里按行流式返回，调用方边读边聚合/边入库，堆占用是常数级。
 * ② <b>必须处理引号内的逗号与换行</b>：商品描述里带逗号是常态
 * （{@code "WHITE HANGING HEART T-LIGHT HOLDER, 6 CUP" }），
 * 一个 {@code split(",")} 就能把整份数据的列错位，而这种错位不会报错，只会让指标悄悄算错。
 */
public final class CsvReader implements Closeable {

    private final Reader reader;
    private final char delimiter;
    private int pushback = -1;
    private long lineNumber = 0;

    public CsvReader(Reader reader, char delimiter) {
        this.reader = reader;
        this.delimiter = delimiter;
    }

    public static CsvReader of(Reader reader) {
        return new CsvReader(reader, ',');
    }

    public long lineNumber() {
        return lineNumber;
    }

    private int read() throws IOException {
        if (pushback >= 0) {
            int c = pushback;
            pushback = -1;
            return c;
        }
        return reader.read();
    }

    private void unread(int c) {
        pushback = c;
    }

    /**
     * 读一行。
     *
     * @return 字段数组；到达文件末尾返回 {@code null}。
     *         空行返回长度为 0 的数组（由调用方决定忽略）。
     */
    public String[] next() throws IOException {
        StringBuilder field = new StringBuilder();
        java.util.List<String> fields = null;
        boolean inQuotes = false;
        boolean any = false;

        while (true) {
            int ci = read();
            if (ci < 0) {
                if (!any && (fields == null || fields.isEmpty()) && field.length() == 0) {
                    return null;
                }
                break;
            }
            char c = (char) ci;
            any = true;

            if (inQuotes) {
                if (c == '"') {
                    int next = read();
                    if (next == '"') {
                        field.append('"');       // 转义的双引号
                    } else {
                        inQuotes = false;
                        unread(next);
                    }
                } else {
                    field.append(c);
                }
                continue;
            }

            if (c == '"' && field.length() == 0) {
                inQuotes = true;
            } else if (c == delimiter) {
                if (fields == null) {
                    fields = new java.util.ArrayList<>(12);
                }
                fields.add(field.toString());
                field.setLength(0);
            } else if (c == '\n') {
                lineNumber++;
                break;
            } else if (c == '\r') {
                int next = read();
                if (next != '\n' && next >= 0) {
                    unread(next);
                }
                lineNumber++;
                break;
            } else {
                field.append(c);
            }
        }

        if (fields == null) {
            return new String[]{field.toString()};
        }
        fields.add(field.toString());
        return fields.toArray(new String[0]);
    }

    @Override
    public void close() throws IOException {
        reader.close();
    }
}
