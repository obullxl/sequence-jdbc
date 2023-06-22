/**
 * Author: obullxl@163.com
 * Copyright (c) 2020-2023 All Rights Reserved.
 */
package cn.ntopic.sequence;

import cn.ntopic.sequence.impl.NTSequenceImpl;
import com.alibaba.druid.pool.DruidDataSource;
import org.junit.Assert;
import org.junit.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;

/**
 * 分布式序列服务单元测试
 *
 * @author obullxl 2023年06月22日: 新增
 */
public class NTSequenceTest {

    @Test
    public void test_next() {
        // 1. 创建数据源
        DruidDataSource dataSource = new DruidDataSource();
        dataSource.setUrl("jdbc:sqlite:/Users/obullxl/CodeSpace/sequence-jdbc/SequenceJDBC.sqlite");
        dataSource.setDriverClassName("org.sqlite.JDBC");
        dataSource.setPoolPreparedStatements(false);
        dataSource.setMaxPoolPreparedStatementPerConnectionSize(-1);
        dataSource.setTestOnBorrow(true);
        dataSource.setTestOnReturn(false);
        dataSource.setTestWhileIdle(true);
        dataSource.setValidationQuery("SELECT '1' FROM sqlite_master LIMIT 1");

        final String tableName = "nt_sequence";
        final String testSeqName = "TEST-" + System.currentTimeMillis() + "-" + System.nanoTime();
        try {
            // 2. 清理数据
            this.deleteSequence(dataSource, tableName, testSeqName);

            // 3. 实例化序列服务
            NTSequenceImpl ntSequence = new NTSequenceImpl(dataSource);
            ntSequence.setTableName(tableName);
            ntSequence.createTable();
            ntSequence.setStep(5);
            ntSequence.init();

            // 4. 并发测试
            this.multiThreadTest(ntSequence, testSeqName);

            // 5. 清理测试数据
            this.deleteSequence(dataSource, tableName, testSeqName);
        } finally {
            dataSource.close();
        }
    }

    private void deleteSequence(DruidDataSource dataSource, String tableName, String sequenceName) {
        Connection conn = null;
        PreparedStatement stmt = null;
        try {
            conn = dataSource.getConnection();

            String deleteSQL = String.format("DELETE FROM %s WHERE name=?", tableName);
            stmt = conn.prepareStatement(deleteSQL);
            stmt.setString(1, sequenceName);

            stmt.executeUpdate();
        } catch (Throwable e) {
            // ignore
        } finally {
            this.closeQuietly(stmt);
            this.closeQuietly(conn);
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

    private void multiThreadTest(NTSequence ntSequence, String sequenceName) {
        final int threadCount = 3;
        final int valueCount = 107;

        CountDownLatch countDownLatch = new CountDownLatch(threadCount);

        Map<Integer, List<Long>> seqValues = new ConcurrentHashMap<>();
        for (int i = 0; i < threadCount; i++) {
            List<Long> sequenceValues = new ArrayList<>();
            seqValues.put(i, sequenceValues);

            new SequenceThread(countDownLatch, valueCount, ntSequence, sequenceName, sequenceValues).start();
        }

        try {
            countDownLatch.await();
        } catch (Throwable e) {
            e.printStackTrace();
        }

        // 数据检测
        Set<Long> allValues = new HashSet<>();

        Assert.assertEquals(threadCount, seqValues.size());
        for (int i = 0; i < threadCount; i++) {
            allValues.addAll(seqValues.get(i));
            Assert.assertEquals(valueCount, seqValues.get(i).size());
        }

        Assert.assertEquals(threadCount * valueCount, allValues.size());
    }

    private static class SequenceThread extends Thread {
        private final int valueCount;
        private final CountDownLatch countDownLatch;
        private final NTSequence ntSequence;
        private final String sequenceName;
        private final List<Long> sequenceValues;

        public SequenceThread(CountDownLatch countDownLatch, int valueCount, NTSequence ntSequence, String sequenceName, List<Long> sequenceValues) {
            this.countDownLatch = countDownLatch;
            this.valueCount = valueCount;
            this.ntSequence = ntSequence;
            this.sequenceName = sequenceName;
            this.sequenceValues = sequenceValues;
        }

        @Override
        public void run() {
            try {
                for (int i = 0; i < valueCount; i++) {
                    this.sequenceValues.add(ntSequence.next(this.sequenceName));
                }

                // 释放信号
                this.countDownLatch.countDown();
            } catch (Throwable e) {
                e.printStackTrace();
            }
        }
    }
}
