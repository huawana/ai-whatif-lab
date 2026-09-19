package com.whatif.lab.data.profile;

import com.whatif.lab.data.csv.CsvTable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.math.BigDecimal;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/**
 * CSV 数据画像器 —— <b>纯确定性统计，不碰 AI、不下语义结论</b>。
 *
 * <p><b>它在链路里的位置</b>：它是 AI Schema Mapper 的**唯一输入**（AI 只看画像，不看原始表），
 * 也是它的**对照物** —— 画像出错是"统计算错"，AI 判断出错是"语义判错"，两者必须能分开定位。
 * 所以这个类的验收方式是：拿公开数据集对"已知事实"（行数、空值率、编码、取值分布）逐条断言。
 *
 * <p><b>三个反复踩过的坑，都在这里被处理了</b>：
 * <ol>
 *   <li><b>编码不能只看开头</b>：UCI 零售是 cp1252（`£`=0xA3），坏字节在第 71KB 处 ——
 *       "读 64KB 探测通过、整表读取时炸"是真实发生过的。这里的做法是：
 *       先用前 4MB 选出候选顺序，然后**整表严格解码**，一旦失败就换下一个候选**重跑一遍**。
 *       并且解码器设成 REPORT（不是 REPLACE）—— REPLACE 会静默把坏字节换成 `?`，
 *       那样"画像"就是假的，而且没有任何提示。</li>
 *   <li><b>解析口径必须与导入一致</b>：复用 {@link CsvTable}（同一个 RFC4180 实现、同样处理
 *       BOM 与单字符 CR 换行），否则"画像说这列没问题、导入却跳掉一半行"。</li>
 *   <li><b>统计必须有界</b>：大表只统计前 N 行并如实标注（{@code statsRows} vs {@code rowCount}）；
 *       唯一值、高频值都有上限，超限就标记，而不是悄悄把内存吃爆。</li>
 * </ol>
 */
@Component
public class CsvProfiler {

    private static final Logger log = LoggerFactory.getLogger(CsvProfiler.class);

    public static final String PROFILE_VERSION = "profile-1.0";
    private static final int DEFAULT_STATS_ROW_LIMIT = 100_000;
    private static final int DISTINCT_CAP = 50_000;
    private static final int KEY_CAP = 20_000;
    private static final int RESERVOIR = 10_000;
    private static final int SAMPLE = 5;
    private static final int RESERVOIR_SEED = 42;
    /** 日期模式只在这么多非空值上判定（避免对每行都跑 9 个 formatter）。 */
    private static final int DATE_DETECT_SAMPLE = 2000;

    private static final List<String> CHARSET_CANDIDATES =
            List.of("UTF-8", "GBK", "windows-1252", "ISO-8859-1");

    /**
     * 日期模式表。最后两个是 **epoch 时间戳**（秒/毫秒）——
     * 现实数据里极常见（RetailRocket 的 timestamp=1433221332117），一开始漏了它，
     * 结果那一列被判成 INT（画像当场就错了）。
     *
     * <p>为什么必须带**取值范围**判断，而不是"10 位数字就算秒级时间戳"：
     * 手机号也是 10~13 位数字。约束到 plausible 区间后天然区分开：
     * <ul>
     *   <li>秒级：1.0e9~2.1e9 → 2001-09 ~ 2036（中国手机号 1.3e10、美国 4.1e9 都落在区间外）</li>
     *   <li>毫秒级：1.0e12~2.1e12 → 2001-09 ~ 2036（带国家码的 13 位号码 8.6e12 也在区间外）</li>
     * </ul>
     */
    private static final List<String> DATE_PATTERNS = List.of(
            "yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd'T'HH:mm:ss", "yyyy-MM-dd HH:mm", "yyyy-MM-dd",
            "yyyy/MM/dd HH:mm:ss", "yyyy/MM/dd", "yyyyMMdd", "dd/MM/yyyy", "MM/dd/yyyy",
            // 【踩过的坑】上面这些写成 MM/dd 的形式，只能认"有前导零"的日期。
            // UCI 零售的 InvoiceDate 是 "12/1/2010 8:26"（月/日/时都没有前导零）→ 一列日期被漏判成文本，
            // 进而让判列器以为"这张表没有时间列"、把整张表判成不可做。判列反过来暴露了画像的漏洞。
            "M/d/yyyy H:mm:ss", "M/d/yyyy H:mm", "M/d/yyyy",
            // 更狠的一种：**连年份都是两位**。UCI 零售的真实取值是 "12/1/10 8:26"（= 2010-12-01 08:26）。
            // 四位年的模式全都匹配不上它 —— 第一次修完仍然漏判，就是因为没想到"年份也可能没有前导零"。
            "M/d/yy H:mm:ss", "M/d/yy H:mm", "M/d/yy",
            // 无前导零时 M/d 与 d/M **形状上无法区分**（12/1 既是 12 月 1 日也是 1 月 12 日），
            // 这里按 M/d 优先解释（多数开源数据集是美式），并在画像里如实标注这一点。
            "d/M/yyyy H:mm:ss", "d/M/yyyy H:mm", "d/M/yyyy",
            "epoch-seconds", "epoch-millis");

    private static final long EPOCH_S_MIN = 1_000_000_000L;
    private static final long EPOCH_S_MAX = 2_100_000_000L;
    private static final long EPOCH_MS_MIN = 1_000_000_000_000L;
    private static final long EPOCH_MS_MAX = 2_100_000_000_000L;

    /**
     * 便宜的形状预筛：只有"长得像日期"才去跑 formatter。
     * 三条分别是：带分隔的 yyyy-MM-dd / 无分隔的 yyyyMMdd / dd-MM-yyyy。
     *
     * <p>【必须预编译】String.matches 每次调用都会重新编译正则；一列 10 万行 × 3 条形状
     * 会把这一步放大成主要耗时（实测过）。
     */
    /*
     * 形状预筛（便宜的正则，避免对每个值都跑 DateTimeFormatter）。
     *
     * 【踩过的坑】原来写成三条枚举：yyyy 开头 / 8 位数字 / d-M-yyyy。
     * 结果 UCI 的 "12/1/10 8:26"（两位年）三条都不匹配 → 在进解析器之前就被筛掉，
     * 我在格式表里加了多少种日期格式都没用 —— 改了下游、忘了上游。
     * 现在改成一条通则：只要"数字 分隔符 数字 分隔符 数字"就给解析器一个机会，
     * 判定真伪交给 DateTimeFormatter（它才是权威）。少枚举 = 少这类漏洞。
     */
    private static final List<java.util.regex.Pattern> DATE_SHAPES = List.of(
            java.util.regex.Pattern.compile("^\\d{1,4}[-/.]\\d{1,2}[-/.]\\d{1,4}.*$"),
            java.util.regex.Pattern.compile("^\\d{8}$"),
            java.util.regex.Pattern.compile("^\\d{10}$"),   // epoch 秒
            java.util.regex.Pattern.compile("^\\d{13}$"));  // epoch 毫秒
    /** 与 DATE_SHAPES 一一对应：该形状是否"无分隔日期"（yyyyMMdd）。 */
    private static final List<Boolean> DATE_SHAPES_IS_NOSEP = List.of(false, true, false, false);

    public DataProfile profile(Path file) throws IOException {
        return profile(file, DEFAULT_STATS_ROW_LIMIT);
    }

    public DataProfile profile(Path file, int statsRowLimit) throws IOException {
        if (!Files.isRegularFile(file)) {
            throw new IOException("文件不存在: " + file);
        }
        long size = Files.size(file);
        List<String> candidates = detectCharsetCandidates(file);
        ProfilePass pass = null;
        List<String> failures = new ArrayList<>();
        for (String cs : candidates) {
            try {
                pass = runPass(file, Charset.forName(cs), cs, statsRowLimit);
                break;
            } catch (CharacterCodingException e) {
                // 整表严格解码失败 → 换下一个候选**重跑**（不留半份结果）
                failures.add(cs + " 解码失败@" + e.getMessage());
                log.info("字符集 {} 整表解码失败，换下一个候选重跑", cs);
            }
        }
        if (pass == null) {
            throw new IOException("所有候选字符集都无法解码该文件: " + failures);
        }
        List<ColumnProfile> columns = new ArrayList<>();
        for (int i = 0; i < pass.acc.length; i++) {
            columns.add(pass.acc[i].toProfile(i, pass.header.get(i), pass))
                    ;
        }
        List<String> warnings = warnings(pass, columns);
        return new DataProfile(UUID.randomUUID().toString(), PROFILE_VERSION,
                java.time.LocalDateTime.now().toString(),
                file.getFileName().toString(), hash16(file), size,
                pass.charset, pass.delimiter, candidates, pass.header.size(), pass.rowCount, pass.statsRows,
                statsRowLimit, RESERVOIR_SEED, columns, warnings);
    }

    // ------------------------------------------------------------------ 字符集

    /**
     * 候选字符集的顺序：在前 4MB 上做严格解码试探。
     *
     * <p>为什么是"候选列表"而不是"一个结论"：GBK 与 windows-1252 对很多字节串都能解码，
     * 谁是"真"编码在统计上无法百分之百确定。诚实的做法是把能解的列出来（按优先级），
     * 并让下游能看到"它其实也可能是另一个"。
     */
    private List<String> detectCharsetCandidates(Path file) throws IOException {
        List<String> ok = new ArrayList<>();
        byte[] head;
        try (InputStream in = Files.newInputStream(file)) {
            head = in.readNBytes(4 * 1024 * 1024);
        }
        for (String cs : CHARSET_CANDIDATES) {
            CharsetDecoder dec = Charset.forName(cs).newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT);
            try {
                dec.decode(java.nio.ByteBuffer.wrap(head));
                ok.add(cs);
            } catch (CharacterCodingException ignored) {
                // 这个候选不成立
            }
        }
        if (ok.isEmpty()) {
            ok.add("ISO-8859-1");
        }
        return ok;
    }

    /**
     * 分隔符检测：在文件头部（解码后）取第一行非空内容，数各候选分隔符**在引号之外**出现几次，
     * 取最多的那个。候选只有四个（, ; \t |），这是刻意的：闭合集合才能被验收。
     *
     * <p>为什么要在引号之外数：CSV 里带分隔符的字段会被引号包起来
     * （如 {@code "Smith, John"}），把它算进去会选错分隔符。
     */
    static char detectDelimiter(Path file, Charset charset) throws IOException {
        byte[] head;
        try (InputStream in = Files.newInputStream(file)) {
            head = in.readNBytes(256 * 1024);
        }
        String text = new String(head, charset);
        String firstLine = null;
        for (String line : text.split("\\r\\n|\\n|\\r", -1)) {
            if (!line.isBlank()) {
                firstLine = line;
                break;
            }
        }
        if (firstLine == null) {
            return ',';
        }
        char[] candidates = {',', ';', '\t', '|'};
        char best = ',';
        int bestCount = 0;
        for (char c : candidates) {
            int count = countOutsideQuotes(firstLine, c);
            if (count > bestCount) {
                bestCount = count;
                best = c;
            }
        }
        return bestCount == 0 ? ',' : best;   // 一列都没有分隔符 → 单列文件，用默认值
    }

    private static int countOutsideQuotes(String line, char delimiter) {
        int count = 0;
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (ch == '"') {
                inQuotes = !inQuotes;
            } else if (ch == delimiter && !inQuotes) {
                count++;
            }
        }
        return count;
    }

    // ------------------------------------------------------------------ 一趟扫描

    private static final class ProfilePass {
        List<String> header = List.of();
        String charset;
        String delimiter = ",";
        long rowCount;
        long statsRows;
        ColAcc[] acc = new ColAcc[0];
    }

    private ProfilePass runPass(Path file, Charset charset, String charsetName, int statsRowLimit)
            throws IOException {
        ProfilePass pass = new ProfilePass();
        pass.charset = charsetName;
        CharsetDecoder decoder = charset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try (Reader r = new BufferedReader(new InputStreamReader(Files.newInputStream(file), decoder), 1 << 16)) {
            // 分隔符也要检测：UCI Bank Marketing 是分号分隔，写死逗号会把 17 列读成 1 列
            char delimiter = detectDelimiter(file, charset);
            pass.delimiter = String.valueOf(delimiter);
            CsvTable table = CsvTable.open(r, delimiter);   // 与导入共用同一个解析器
            pass.header = table.header();
            pass.acc = new ColAcc[pass.header.size()];
            for (int i = 0; i < pass.acc.length; i++) {
                pass.acc[i] = new ColAcc();
            }
            long[] rows = {0};
            table.forEach(row -> {
                rows[0]++;
                // 【两档口径】空值率、最值、负值数、数值解析这些只占一个计数器（O(1) 内存），
                // 因此在**全表**上统计；唯一值 / 分位数 / 样例要占内存，只在前 N 行上做。
                // 踩过的坑：一开始把所有统计都限制在前 10 万行，导致 CustomerID 空值率报 34.92%，
                // 而真实的 24.93%（135,080/541,909）—— 把"采样口径"当成"整表事实"报出去了。
                boolean inPrefix = pass.statsRows < statsRowLimit;
                if (inPrefix) {
                    pass.statsRows++;
                }
                for (int i = 0; i < pass.acc.length; i++) {
                    pass.acc[i].accept(i < row.length ? row[i] : null, inPrefix);
                }
            });
            pass.rowCount = rows[0];
        }
        return pass;
    }

    /** 单列累加器 —— 全部有界（超限只标记，不爆内存）。 */
    private static final class ColAcc {
        long nonBlank;
        long blank;
        final Set<String> distinct = new HashSet<>();
        boolean distinctCapped;
        final Map<String, Integer> freq = new HashMap<>();
        double min = Double.POSITIVE_INFINITY;
        double max = Double.NEGATIVE_INFINITY;
        long negatives;
        long zeros;
        long nonIntegral;
        long numericOk;
        long numericBad;
        long leadingZeros;
        long hasDecimals;
        int maxScale;
        Integer minLen;
        Integer maxLen;
        int[] patternHits = new int[DATE_PATTERNS.size()];
        long dateChecked;
        int patternBest = -1;
        int bestNoSep = -1;
        final double[] reservoir = new double[RESERVOIR];
        int reservoirSize;
        int seenForReservoir;
        final Random rng = new Random(RESERVOIR_SEED);
        final List<String> samples = new ArrayList<>();
        long piiEmail;
        long piiPhone;

        /** 前缀内的非空计数 —— 唯一值比率的分母只能是前缀（分子也是前缀）。 */
        long prefixNonBlank;

        void accept(String raw, boolean inPrefix) {
            if (raw == null || raw.isBlank()) {
                blank++;
                return;
            }
            String v = raw.trim();
            nonBlank++;
            if (inPrefix) {
                prefixNonBlank++;
            }
            if (inPrefix) {
                if (samples.size() < SAMPLE && !samples.contains(v)) {
                    samples.add(v.length() > 64 ? v.substring(0, 64) + "…" : v);
                }
                if (!distinctCapped) {
                    distinct.add(v);
                    if (distinct.size() >= DISTINCT_CAP) {
                        distinctCapped = true;
                    }
                }
                if (freq.size() < KEY_CAP || freq.containsKey(v)) {
                    freq.merge(v, 1, Integer::sum);
                }
            }
            int len = v.length();
            minLen = minLen == null ? len : Math.min(minLen, len);
            maxLen = maxLen == null ? len : Math.max(maxLen, len);
            // 数值解析（手工快判，避免每行都进异常路径）
            BigDecimal num = tryNumber(v);
            if (num != null) {
                numericOk++;
                double d = num.doubleValue();
                min = Math.min(min, d);
                max = Math.max(max, d);
                if (d < 0) {
                    negatives++;
                }
                if (d == 0) {
                    zeros++;
                }
                if (num.scale() > 0) {
                    hasDecimals++;
                    nonIntegral++;   // "非整数取值个数" = 带小数的取值个数（不是另算一遍）
                    maxScale = Math.max(maxScale, num.scale());
                } else if (num.scale() == 0 && num.precision() > 1 && v.startsWith("0")) {
                    // 看起来是数字但带前导零（邮编/编码），不是可运算的数
                    leadingZeros++;
                }
                if (inPrefix) {
                    offerReservoir(d);
                }
            } else {
                numericBad++;
                if (isLeadingZeroCode(v)) {
                    leadingZeros++;
                }
            }
            // 【坑】不能只在"解析不出数字"时才检测日期：yyyyMMdd 本身就是纯数字，
            // 会被上面的数值快判先吞掉，于是 20260101 这种列被误判成 INT、丢掉了日期语义。
            // detectDate 内部先做形状预筛（预编译正则），对普通数字是常数级开销。
            detectDate(v);
            if (v.indexOf('@') > 0 && v.indexOf('.', v.indexOf('@')) > 0 && v.indexOf(' ') < 0) {
                piiEmail++;
            }
            // 手机号判定：**必须排除 plausible 的 epoch 时间戳** ——
            // 13 位毫秒时间戳（1433221332117）在长度上与手机号完全一样，
            // 只按"10~13 位数字"判会把它标成 PII（实测发生过的 false positive）。
            // 带分隔符/加号的形式（+86 138-0001-3800）则视为强证据，不受 epoch 区间影响。
            if (hasPhoneSeparator(v)) {
                String d1 = v.replaceAll("[\\s\\-()+]", "");
                if (d1.length() >= 10 && d1.length() <= 13 && d1.chars().allMatch(Character::isDigit)) {
                    piiPhone++;
                }
            } else {
                String digits = v;
                if (digits.length() >= 10 && digits.length() <= 13
                        && digits.chars().allMatch(Character::isDigit)
                        && !plausibleEpoch(Long.parseLong(digits))) {
                    piiPhone++;
                }
            }
        }

        private void detectDate(String v) {
            if (dateChecked >= DATE_DETECT_SAMPLE || patternBest >= 0) {
                return;
            }
            if (dateShapeOf(v) < 0) {
                return;
            }
            dateChecked++;
            boolean any = false;
            for (int p = 0; p < DATE_PATTERNS.size(); p++) {
                if (parseDate(v, p)) {
                    patternHits[p]++;
                    any = true;
                }
            }
            if (!any) {
                return;
            }
            int best = 0;
            for (int p = 1; p < patternHits.length; p++) {
                if (patternHits[p] > patternHits[best]) {
                    best = p;
                }
            }
            if (patternHits[best] >= Math.max(1, (int) (dateChecked * 0.95))) {
                patternBest = best;
                bestNoSep = DATE_SHAPES_IS_NOSEP.get(dateShapeOf(v)) ? 1 : 0;
            }
        }

        private static boolean parseDate(String v, int p) {
            String pat = DATE_PATTERNS.get(p);
            if (pat.startsWith("epoch-")) {
                // epoch 必须同时满足"形状对"与"落在 plausible 时间区间"——否则手机号会被当时间戳
                try {
                    long n = Long.parseLong(v);
                    return "epoch-seconds".equals(pat)
                            ? n >= EPOCH_S_MIN && n <= EPOCH_S_MAX
                            : n >= EPOCH_MS_MIN && n <= EPOCH_MS_MAX;
                } catch (NumberFormatException e) {
                    return false;
                }
            }
            try {
                if (pat.contains("H")) {
                    LocalDateTime.parse(v, DateTimeFormatter.ofPattern(pat, Locale.ROOT));
                } else {
                    LocalDate.parse(v, DateTimeFormatter.ofPattern(pat, Locale.ROOT));
                }
                return true;
            } catch (Exception e) {
                return false;
            }
        }

        private static int dateShapeOf(String v) {
            for (int i = 0; i < DATE_SHAPES.size(); i++) {
                if (DATE_SHAPES.get(i).matcher(v).matches()) {
                    return i;
                }
            }
            return -1;
        }

        private void offerReservoir(double d) {
            seenForReservoir++;
            if (reservoirSize < RESERVOIR) {
                reservoir[reservoirSize++] = d;
                return;
            }
            int j = rng.nextInt(seenForReservoir);
            if (j < RESERVOIR) {
                reservoir[j] = d;
            }
        }

        ColumnProfile toProfile(int index, String name, ProfilePass pass) {
            // 空值率：分母是**全表行数**（分子也是全表计数）
            double nullRate = (double) blank / Math.max(1, pass.rowCount);
            // 唯一值比率：分子分母都只能来自前缀，否则口径不一致会算出 >1 的比率
            double uniqueRatio = (double) distinct.size() / Math.max(1, prefixNonBlank);
            Double p05 = null;
            Double p50 = null;
            Double p95 = null;
            if (reservoirSize > 0) {
                double[] arr = java.util.Arrays.copyOf(reservoir, reservoirSize);
                java.util.Arrays.sort(arr);
                p05 = quantile(arr, 0.05);
                p50 = quantile(arr, 0.50);
                p95 = quantile(arr, 0.95);
            }
            String type = inferType(this, pass.statsRows);
            List<String> flags = new ArrayList<>();
            if (nonBlank == 0) {
                flags.add(ColumnProfile.ALL_NULL);
            } else {
                if (blank > 0) {
                    flags.add(ColumnProfile.HAS_NULL);
                }
                if (distinct.size() == 1) {
                    flags.add(ColumnProfile.CONSTANT);
                } else if (distinct.size() == nonBlank && !distinctCapped) {
                    flags.add(ColumnProfile.UNIQUE);
                }
                if (uniqueRatio >= 0.5 && distinct.size() > 100) {
                    flags.add(ColumnProfile.HIGH_CARDINALITY);
                }
                if (distinct.size() <= 100 && uniqueRatio < 0.5) {
                    flags.add(ColumnProfile.LOW_CARDINALITY);
                }
                if (negatives > 0) {
                    flags.add(ColumnProfile.HAS_NEGATIVE);
                }
                if (distinct.size() <= 2 && numericOk == nonBlank && max <= 1.0 && min >= 0) {
                    flags.add(ColumnProfile.BINARY_VALUES);
                }
                if (numericOk == nonBlank && distinct.size() > 100 && uniqueRatio >= 0.9) {
                    flags.add(ColumnProfile.LIKELY_IDENTIFIER);
                }
                if (leadingZeros > 0) {
                    flags.add(ColumnProfile.LEADING_ZEROS);
                }
                if (patternBest >= 0 && bestNoSep >= 0 && DATE_SHAPES_IS_NOSEP.get(bestNoSep)) {
                    flags.add(ColumnProfile.DATE_NO_SEPARATOR);
                }
                if (piiEmail > 0 && piiEmail * 2 > nonBlank) {
                    flags.add(ColumnProfile.PII_EMAIL);
                }
                if (piiPhone > 0 && piiPhone * 2 > nonBlank) {
                    flags.add(ColumnProfile.PII_PHONE);
                }
                if (("TEXT".equals(type) || "CATEGORY".equals(type))
                        && numericOk > 0 && numericBad > 0
                        && (double) Math.min(numericOk, numericBad) / nonBlank >= 0.2) {
                    flags.add(ColumnProfile.MIXED_TYPE);
                }
            }
            return new ColumnProfile(index, name, type, nonBlank, blank, round(nullRate, 4),
                    distinct.size(), distinctCapped, round(uniqueRatio, 4),
                    reservoirSize > 0 ? min : null, reservoirSize > 0 ? max : null, p05, p50, p95,
                    negatives, zeros, nonIntegral, minLen, maxLen, leadingZeros,
                    patternBest >= 0 ? DATE_PATTERNS.get(patternBest) : null,
                    patternBest >= 0 ? 1.0 : null,
                    topValues(), samples, flags);
        }

        private List<String> topValues() {
            return freq.entrySet().stream()
                    .sorted((a, b) -> {
                        int c = Integer.compare(b.getValue(), a.getValue());
                        return c != 0 ? c : a.getKey().compareTo(b.getKey());
                    })
                    .limit(SAMPLE)
                    .map(e -> e.getKey().length() > 48 ? e.getKey().substring(0, 48) + "…" : e.getKey())
                    .toList();
        }
    }

    /**
     * 结构类型推断 —— <b>只看数据形状，不看列名</b>。
     *
     * <p>为什么不看列名：列名叫 {@code amount} 不代表它是金额（可能是成本、税后、含折扣、以分为单位）。
     * 列名线索会作为单独的 {@code nameHint} 交给 LLM，而不是混进"事实"里。
     */
    private static String inferType(ColAcc a, long statsRows) {
        if (a.nonBlank == 0) {
            return "EMPTY";
        }
        double n = a.nonBlank;
        if (a.patternBest >= 0) {
            String pat = DATE_PATTERNS.get(a.patternBest);
            // epoch 秒/毫秒本身就是"带时间"的时间戳，归 DATETIME（不能因为是数字就算 DATE）
            return (pat.contains("H") || pat.startsWith("epoch-")) ? "DATETIME" : "DATE";
        }
        if (a.distinct.size() <= 2) {
            boolean textualBool = a.distinct.stream().allMatch(v -> {
                String s = v.toLowerCase(Locale.ROOT);
                return s.equals("true") || s.equals("false") || s.equals("yes") || s.equals("no")
                        || s.equals("y") || s.equals("n") || s.equals("t") || s.equals("f");
            });
            if (textualBool) {
                return "BOOLEAN";
            }
            // 【为什么数值 0/1 不算 BOOLEAN】0/1 只是取值形态，可能是布尔、也可能是编码
            // （hour.csv 的 yr=0/1 表示 2011/2012）。"是不是布尔"是语义判断，
            // 统计层只报事实（INT + BINARY_VALUES 标记），判断权交给上层。
        }
        if (a.numericOk == a.nonBlank) {
            if (a.leadingZeros > 0 && a.hasDecimals == 0) {
                return "ID";   // 带前导零的"数字"是编码（邮编/编号），不是可运算的数
            }
            return a.hasDecimals > 0 ? "DECIMAL" : "INT";
        }
        double numericRatio = a.numericOk / n;
        if (numericRatio >= 0.95) {
            return a.hasDecimals > 0 ? "DECIMAL" : "INT";
        }
        double uniqueRatio = (double) a.distinct.size() / n;
        if (a.distinct.size() == a.nonBlank && a.minLen != null && a.minLen.equals(a.maxLen)) {
            return "ID";
        }
        if (a.distinct.size() <= 100 && uniqueRatio < 0.5) {
            return "CATEGORY";
        }
        if (uniqueRatio >= 0.6 && numericRatio < 0.2) {
            return "ID";
        }
        return "TEXT";
    }

    private static Double quantile(double[] sorted, double q) {
        if (sorted.length == 0) {
            return null;
        }
        double idx = q * (sorted.length - 1);
        int lo = (int) Math.floor(idx);
        int hi = (int) Math.ceil(idx);
        if (lo == hi) {
            return sorted[lo];
        }
        return sorted[lo] + (sorted[hi] - sorted[lo]) * (idx - lo);
    }

    private static BigDecimal tryNumber(String v) {
        try {
            return new BigDecimal(v);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 带明显电话格式痕迹（分隔符），作为强证据。 */
    private static boolean hasPhoneSeparator(String v) {
        return v.indexOf('+') >= 0 || v.indexOf('-') >= 0 || v.indexOf(' ') > 0
                || v.indexOf('(') >= 0 || v.indexOf(')') >= 0;
    }

    /** 是否落在 plausible 的 epoch 时间区间（秒或毫秒）——用来把"时间戳"与"号码"区分开。 */
    private static boolean plausibleEpoch(long n) {
        return (n >= EPOCH_S_MIN && n <= EPOCH_S_MAX) || (n >= EPOCH_MS_MIN && n <= EPOCH_MS_MAX);
    }

    private static boolean isLeadingZeroCode(String v) {
        return v.length() > 1 && v.charAt(0) == '0' && v.chars().allMatch(Character::isDigit);
    }

    private static double round(double v, int scale) {
        double f = Math.pow(10, scale);
        return Math.round(v * f) / f;
    }

    private static String hash16(Path file) throws IOException {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            try (InputStream in = Files.newInputStream(file)) {
                byte[] buf = new byte[1 << 16];
                int n;
                while ((n = in.read(buf)) > 0) {
                    md.update(buf, 0, n);
                }
            }
            StringBuilder sb = new StringBuilder();
            for (byte b : md.digest()) {
                sb.append(String.format("%02x", b));
                if (sb.length() >= 16) {
                    break;
                }
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IOException("计算文件哈希失败: " + e.getMessage(), e);
        }
    }

    // ------------------------------------------------------------------ 汇总结论

    private static List<String> warnings(ProfilePass pass, List<ColumnProfile> columns) {
        List<String> w = new ArrayList<>();
        Map<String, Integer> seen = new LinkedHashMap<>();
        for (String h : pass.header) {
            seen.merge(h.toLowerCase(), 1, Integer::sum);
        }
        seen.forEach((k, v) -> {
            if (v > 1) {
                w.add("列名重复 " + v + " 次: " + k + "（按下标区分，别按名字取）");
            }
        });
        if (columns.stream().noneMatch(c -> c.inferredType().startsWith("DATE"))) {
            w.add("没有任何日期/时间列：无法做时间切分与标签窗口（需要用户指定或补列）");
        }
        columns.stream().filter(c -> c.flags().contains(ColumnProfile.ALL_NULL))
                .forEach(c -> w.add("整列为空: " + c.name()));
        columns.stream().filter(c -> c.flags().contains(ColumnProfile.PII_EMAIL)
                        || c.flags().contains(ColumnProfile.PII_PHONE))
                .forEach(c -> w.add("疑似个人信息列（发往模型前建议脱敏）: " + c.name()));
        columns.stream().filter(c -> c.flags().contains(ColumnProfile.LEADING_ZEROS))
                .forEach(c -> w.add("带前导零的编码列（当数字处理会丢信息）: " + c.name()));
        columns.stream().filter(c -> c.distinctCapped())
                .forEach(c -> w.add("唯一值 >= " + 50_000 + " 个（已截断，这是下界不是精确值）: " + c.name()));
        columns.stream().filter(c -> c.datePattern() != null && c.datePattern().startsWith("M/d/"))
                .forEach(c -> w.add("日期按「" + c.datePattern() + "」解释（" + c.name()
                        + "）：无前导零时 M/d 与 d/M 形状相同，请确认月日顺序是否正确"));
        if (pass.statsRows < pass.rowCount) {
            w.add("唯一值/分位数/样例只覆盖前 " + pass.statsRows + " 行（共 " + pass.rowCount
                    + " 行）；空值率、最值与取值计数是全表的");
        }
        return w;
    }
}
