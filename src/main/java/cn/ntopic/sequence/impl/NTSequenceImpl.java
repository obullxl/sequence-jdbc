/**
 * Author: obullxl@163.com
 * Copyright (c) 2020-2023 All Rights Reserved.
 */
package cn.ntopic.sequence.impl;

import cn.ntopic.sequence.NTSequence;
import cn.ntopic.sequence.model.NTValueRange;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 分布式序列服务实现
 *
 * @author obullxl 2023年06月21日: 新增
 */
public class NTSequenceImpl implements NTSequence {
    private static final Logger LOGGER = LoggerFactory.getLogger(NTSequenceImpl.class);

    /**
     * 属性-数据源
     */
    private final DataSource ntDataSource;

    /**
     * 属性-重试次数
     */
    private int retryTimes = 10;

    /**
     * 属性-数据表名
     */
    private String tableName = "nt_sequence";

    /**
     * 属性-序列获取步长（即序列缓存大小）
     */
    private long step = 1000L;

    /**
     * 属性-序列最小值
     */
    private long minValue = 1L;

    /**
     * 属性-序列最大值
     */
    private long maxValue = 99999999L;


    /**
     * 序列缓存
     */
    private static final Map<String, NTValueRange> VALUE_RANGES = new ConcurrentHashMap<>();

    public NTSequenceImpl(DataSource ntDataSource) {
        if (ntDataSource == null) {
            throw new IllegalArgumentException("序列数据源为NULL.");
        }

        this.ntDataSource = ntDataSource;
    }

    /**
     * 初始化
     */
    public void init() {
        if (this.minValue >= this.maxValue) {
            throw new IllegalArgumentException("序列最小和最大值参数非法(" + this.minValue + "," + this.maxValue + ")");
        }

        // 尝试获取序列，检测服务是否可用
        long value = this.next(DEFAULT_SEQUENCE_NAME);
        LOGGER.info("NTSequence服务初始化-检测序列值({}).", value);
    }

    /**
     * 尝试创建数据表
     */
    public void createTable() {
        Connection conn = null;
        try {
            conn = this.ntDataSource.getConnection();

            // 1. 检测数据表是否存在
            ResultSet rs = null;
            try {
                rs = conn.getMetaData().getTables(null, null, this.tableName, null);
                if (rs.next()) {
                    LOGGER.info("序列数据表存在-无需创建[{}].", this.tableName);
                    return;
                }
            } finally {
                this.closeQuietly(rs);
            }

            // 2. 创建数据表
            PreparedStatement stmt = null;
            try {
                StringBuilder createSQL = new StringBuilder();
                createSQL.append(String.format("CREATE TABLE %s", this.tableName));
                createSQL.append("(");
                createSQL.append("name  VARCHAR(64) NOT NULL,");
                createSQL.append("value BIGINT      NOT NULL,");
                createSQL.append("PRIMARY KEY (name)");
                createSQL.append(")");

                String createTableSQL = createSQL.toString();
                stmt = conn.prepareStatement(createTableSQL);
                stmt.executeUpdate();
                LOGGER.info("创建序列数据表成功[{}].", this.tableName);
            } finally {
                this.closeQuietly(stmt);
            }
        } catch (Throwable e) {
            LOGGER.error("检测序列数据表是否存在异常，请求人工创建序列数据表[{}].", this.tableName, e);
            throw new RuntimeException("检测序列数据表是否存在异常，请求人工创建序列数据表(" + this.tableName + ")", e);
        } finally {
            this.closeAutoCommit(conn);
        }
    }

    @Override
    public long next(String sequenceName) {
        // 入参验证
        if (sequenceName == null || sequenceName.length() > MAX_SEQUENCE_NAME_LENGTH) {
            throw new IllegalArgumentException("序列名称非法(" + sequenceName + ")");
        }

        // 上锁验证缓存序列区间
        synchronized (VALUE_RANGES) {
            if (VALUE_RANGES.containsKey(sequenceName)) {
                // 缓存存在，则获取是否可以获取到下一个值
                Optional<Long> optValue = VALUE_RANGES.get(sequenceName).nextValue();
                if (optValue.isPresent()) {
                    // 如果区间有效，则直接返回
                    return optValue.get();
                }

                // 区间无需，则直接清理，统一当作无缓存处理
                VALUE_RANGES.remove(sequenceName);
            }

            // 缓存区间不存在，则从DB拉取序列数据
            Optional<NTValueRange> optRange = this.fetchRange(sequenceName);
            if (optRange.isPresent()) {
                Optional<Long> optValue = optRange.get().nextValue();
                if (optValue.isPresent()) {
                    // 区间有效，重新缓存
                    VALUE_RANGES.put(sequenceName, optRange.get());

                    // 返回当前区间值
                    return optValue.get();
                }
            }
        }

        // 多次尝试均无法获取序列区间
        throw new RuntimeException("无法获取序列值(" + sequenceName + ")");
    }

    /**
     * 从DB拉取序列数据
     */
    private Optional<NTValueRange> fetchRange(String sequenceName) {
        for (int i = 1; i <= this.retryTimes; i++) {
            Connection conn = null;
            boolean autoCommit = true;
            try {
                // 获取数据库链接
                conn = this.ntDataSource.getConnection();
                autoCommit = conn.getAutoCommit();

                if (!autoCommit) {
                    conn.setAutoCommit(true);
                }

                // 尝试获取或者初始化序列
                Optional<NTValueRange> optRange = this.fetchSequence(conn, sequenceName);
                if (optRange.isPresent()) {
                    return optRange;
                }
            } catch (Throwable e) {
                LOGGER.error("第[{}]次获取序列异常[{}]-重试次数[{}].", i, sequenceName, this.retryTimes, e);
            } finally {
                if (!autoCommit) {
                    this.closeAutoCommit(conn);
                }

                this.closeQuietly(conn);
            }
        }

        // 重试均无法获取到有效序列
        return Optional.empty();
    }

    /**
     * 查询并初始化序列值
     */
    private Optional<NTValueRange> fetchSequence(Connection conn, String sequenceName) throws SQLException {
        final long step = this.step;
        final long minValue = this.minValue;
        final long initValue = minValue + step - 1;

        // 1. 查询
        PreparedStatement stmt1 = null;
        PreparedStatement stmt2 = null;
        PreparedStatement stmt3 = null;
        ResultSet rs = null;
        try {
            // 构建查询语句
            String selectSQL = String.format("SELECT value FROM %s WHERE name=?", this.tableName);
            stmt1 = conn.prepareStatement(selectSQL);
            stmt1.setString(1, sequenceName);

            // 执行查询
            rs = stmt1.executeQuery();
            if (!rs.next()) {
                LOGGER.info("新增序列[{}].", sequenceName);

                // 数据不存在，则新增数据
                String insertSQL = String.format("INSERT INTO %s(name, value) VALUES(?, ?)", this.tableName);
                stmt2 = conn.prepareStatement(insertSQL);
                stmt2.setString(1, sequenceName);
                stmt2.setLong(2, initValue);

                int count = stmt2.executeUpdate();
                if (count > 0) {
                    // 新增序列成功
                    return Optional.of(NTValueRange.from(minValue, initValue));
                }
            } else {
                // 数据存在，则更新数据值
                boolean init = false;
                long value = rs.getLong("value");
                long newValue = value + step;
                if (newValue > this.maxValue) {
                    if (this.maxValue > value) {
                        LOGGER.info("序列即将循环[{}]-[{}->{}].", sequenceName, value, this.maxValue);
                        newValue = this.maxValue;
                    } else {
                        init = true;
                        newValue = initValue;
                    }
                }

                LOGGER.info("更新序列[{}]-[{}->{}]-Cycle{}.", sequenceName, value, newValue, init ? "Y" : "N");

                String updateSQL = String.format("UPDATE %s SET value=? WHERE name=? AND value=?", this.tableName);
                stmt3 = conn.prepareStatement(updateSQL);
                stmt3.setLong(1, newValue);
                stmt3.setString(2, sequenceName);
                stmt3.setLong(3, value);

                int count = stmt3.executeUpdate();
                if (count > 0) {
                    // 更新序列成功
                    long start = init ? minValue : value + 1;
                    LOGGER.debug("序列区间[{}]-[{}->{}].", sequenceName, start, newValue);

                    return Optional.of(NTValueRange.from(start, newValue));
                }
            }
        } finally {
            this.closeQuietly(rs);
            this.closeQuietly(stmt1);
            this.closeQuietly(stmt2);
            this.closeQuietly(stmt3);
        }

        // DB操作失败，重试
        return Optional.empty();
    }

    private void closeAutoCommit(Connection conn) {
        if (conn != null) {
            try {
                conn.setAutoCommit(false);
            } catch (Throwable e) {
                // ignore
            }
        }
    }

    private void closeQuietly(AutoCloseable closeable) {
        if (closeable != null) {
            try {
                closeable.close();
            } catch (Throwable e) {
                // ignore
            }
        }
    }

    // ~~~~~~~~~~~~~ getters and setters ~~~~~~~~~~~~~~ //

    public int getRetryTimes() {
        return retryTimes;
    }

    public void setRetryTimes(int retryTimes) {
        if (retryTimes <= 0) {
            throw new IllegalArgumentException("重试次数参数非法(" + retryTimes + ")");
        }

        this.retryTimes = retryTimes;
    }

    public String getTableName() {
        return tableName;
    }

    public void setTableName(String tableName) {
        if (tableName == null || tableName.isEmpty()) {
            throw new IllegalArgumentException("序列数据表名参数非法(" + tableName + ")");
        }

        this.tableName = tableName;
    }

    public long getStep() {
        return step;
    }

    public void setStep(long step) {
        if (step <= 0L) {
            throw new IllegalArgumentException("获取序列步长参数非法(" + step + ")");
        }

        this.step = step;
    }

    public long getMinValue() {
        return minValue;
    }

    public void setMinValue(long minValue) {
        if (minValue <= 0L) {
            throw new IllegalArgumentException("序列最小值参数非法(" + minValue + ")");
        }

        this.minValue = minValue;
    }

    public long getMaxValue() {
        return maxValue;
    }

    public void setMaxValue(long maxValue) {
        if (maxValue <= 0L) {
            throw new IllegalArgumentException("序列最大值参数非法(" + maxValue + ")");
        }

        this.maxValue = maxValue;
    }
}
