package com.whatif.lab.data;

import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.sql.SQLException;

/**
 * 批量写入器。
 *
 * <p><b>为什么这里绕开 MyBatis-Plus 直接用 JdbcTemplate</b>：
 * 导入 50 万行事件时，逐条 {@code mapper.insert()} 会为每行生成一条 SQL 并单独往返数据库，
 * 50 万次网络往返在本地 mysql 上也要几十秒到几分钟。
 * {@code batchUpdate} 配合 JDBC URL 上的 {@code rewriteBatchedStatements=true}，
 * 会把整批拼成单条多值 INSERT，实测能快一到两个数量级。
 *
 * <p>这是有意的「性能拐点处偏离框架默认」：常规 CRUD 依然走 MyBatis-Plus，
 * 只有这条高吞吐写入路径用 JDBC 批量。代价是这里要手填占位符个数，
 * 所以只有两处调用点（entity / event），不接受扩散。
 */
@Component
public class BulkInserter {

    private final JdbcTemplate jdbcTemplate;

    public BulkInserter(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    private static final String INSERT_EVENT = """
            INSERT INTO event (dataset_id, entity_external_id, event_type, product_external_id,
                               event_time, quantity, unit_price, amount, metadata)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private static final String INSERT_ENTITY = """
            INSERT INTO entity (dataset_id, entity_type, external_id, attributes)
            VALUES (?, ?, ?, ?)
            """;

    /**
     * 批量写事件。
     *
     * @param batchSize 每批行数；过小则往返多，过大则单条 SQL 超 max_allowed_packet
     */
    public int insertEvents(long datasetId, List<Object[]> rows, int batchSize) {
        return flush(rows, batchSize, INSERT_EVENT, (ps, row) -> {
            int i = 1;
            ps.setLong(i++, datasetId);
            ps.setString(i++, (String) row[0]);
            ps.setString(i++, (String) row[1]);
            ps.setString(i++, (String) row[2]);
            ps.setTimestamp(i++, (Timestamp) row[3]);
            ps.setInt(i++, (Integer) row[4]);
            ps.setBigDecimal(i++, (java.math.BigDecimal) row[5]);
            ps.setBigDecimal(i++, (java.math.BigDecimal) row[6]);
            ps.setString(i++, (String) row[7]);
        });
    }

    public int insertEntities(long datasetId, List<Object[]> rows, int batchSize) {
        return flush(rows, batchSize, INSERT_ENTITY, (ps, row) -> {
            int i = 1;
            ps.setLong(i++, datasetId);
            ps.setString(i++, (String) row[0]);
            ps.setString(i++, (String) row[1]);
            ps.setString(i, (String) row[2]);
        });
    }

    /**
     * 参数绑定回调。
     *
     * <p>刻意自定义而不用 {@code BiConsumer}：{@code PreparedStatement.setXxx} 会抛
     * {@link SQLException}，而 {@code BiConsumer} 的 {@code accept} 不允许受检异常 ——
     * 用它会逼着在 lambda 里写 try-catch 包住每个 setter，既啰嗦又丢掉了异常信息。
     * 自定义一个允许抛 SQLException 的函数式接口是这里最干净的写法。
     */
    @FunctionalInterface
    private interface SqlBinder {
        void bind(PreparedStatement ps, Object[] row) throws SQLException;
    }

    private int flush(List<Object[]> rows, int batchSize, String sql, SqlBinder binder) {
        if (rows.isEmpty()) {
            return 0;
        }
        int written = 0;
        List<Object[]> chunk = new ArrayList<>(Math.min(batchSize, rows.size()));
        for (Object[] row : rows) {
            chunk.add(row);
            if (chunk.size() >= batchSize) {
                written += doBatch(sql, chunk, binder);
                chunk.clear();
            }
        }
        if (!chunk.isEmpty()) {
            written += doBatch(sql, chunk, binder);
        }
        return written;
    }

    private int doBatch(String sql, List<Object[]> chunk, SqlBinder binder) {
        int[] result = jdbcTemplate.batchUpdate(sql, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement ps, int i) throws SQLException {
                binder.bind(ps, chunk.get(i));
            }

            @Override
            public int getBatchSize() {
                return chunk.size();
            }
        });
        int sum = 0;
        for (int r : result) {
            // 【实测踩到的坑】MySQL 在 rewriteBatchedStatements=true（JDBC URL 里开了）下，
            // 驱动对批量语句会返回 SUCCESS_NO_INFO(-2) 而不是每行 1 —— 「执行成功但受影响行数未知」。
            // 最初的写法 Math.max(r, 0) 把 -2 直接变成 0，于是 dataset.entity_count 落库是 0，
            // 而实体表里其实有 8055 行。批量写入的返回值不可信，权威计数必须回查数据库
            // （见 DatasetImportService#countRows），这里只保证这个返回值本身不再说谎。
            sum += (r == java.sql.Statement.SUCCESS_NO_INFO) ? 1 : Math.max(r, 0);
        }
        return sum;
    }
}
