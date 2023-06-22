# 通用高性能的分布式id序列服务
+ `通用性：`仅依赖一张序列数据表，JDBC支持的数据库均可使用，包括SQLite、MySQL、Oracle、OceanBase等。
+ `高性能：`本地缓存一个序列区间，缓存使用完之前无DB交互；缓存的区间可设置，区间越大，DB访问越少，性能越高。
+ `分布式：`受益于集中式的序列数据表，保证了序列全局唯一。

# 使用步骤

+ 个人博客-详细介绍了组件的设计思路：[https://ntopic.cn/p/2023062101/](https://ntopic.cn/p/2023062101/)

## 设置仓库
本JAR使用了Gitee仓库托管，在项目的根`pom.xml`中，设置仓库地址：
```xml
<repositories>
   <repository>
      <id>Gitee-obullxl</id>
      <url>https://gitee.com/obullxl/maven-repository/raw/sequence-jdbc</url>
   </repository>
</repositories>
```

## JAR包引用
仅需要依赖本JAR包，无其他JAR包依赖：
```xml
<dependency>
    <groupId>cn.ntopic</groupId>
    <artifactId>sequence-jdbc</artifactId>
    <version>1.0.1</version>
</dependency>
```

## 创建数据表（可选）
+ 项目根目录有测试的SQLite数据库（`SequenceJDBC.sqlite`），可直接用于测试；其他的数据库，可提前创建数据表。
+ 序列数据表名可自定义（默认为`nt_sequence`），但表的2个字段（`name`和`value`）名称不可修改。
+ `可选：`默认情况下，序列服务可尝试创建数据表，若当前用户无建表权限，则需要手工创建以下序列数据表：
```sql
CREATE TABLE nt_sequence
(
    name  VARCHAR(64) NOT NULL COMMENT '序列名称',
    value bigint      NOT NULL COMMENT '序列值',
    PRIMARY KEY (name)
) COMMENT='序列数据表'
;
```

## 实例化
```java
// 获取数据源，业务代码提供
DruidDataSource dataSource = new DruidDataSource();
dataSource.setUrl("jdbc:sqlite:./sequence-jdbc/SequenceJDBC.sqlite");
dataSource.setDriverClassName("org.sqlite.JDBC");
dataSource.setPoolPreparedStatements(false);
dataSource.setMaxPoolPreparedStatementPerConnectionSize(-1);
dataSource.setTestOnBorrow(true);
dataSource.setTestOnReturn(false);
dataSource.setTestWhileIdle(true);
dataSource.setValidationQuery("SELECT '1' FROM sqlite_master LIMIT 1");

// 实例化序列
@Bean("ntSequence")
public NTSequence ntSequence(DataSource dataSource) {
    NTSequenceImpl impl = new NTSequenceImpl(dataSource);
    
    // 可选：若自定义序列表名，才需要设置
    impl.setTableName("nt_sequence"); // 默认为：nt_sequence
    
    // 可选：尝试创建数据表，若当前用户无建表权限，则需要人工创建；若表已经创建，则忽略建表
    impl.createTable();
    
    // 可选：以下参数为默认值，若无特殊要求，可无需设置
    impl.setRetryTimes(10);
    impl.setStep(1000L); // 值越大，访问DB次数越少，性能越好
    impl.setMinValue(1L);
    impl.setMaxValue(99999999L); // 序列值最大值，当超过该值，则循环从`minValue`开始
    
    // 序列初始化
    impl.init();
    
    return impl;
}
```

## 序列使用
+ 第1种方式：无任何参数，使用默认的序列名称。
+ 第2中方式：指定序列名称（如：`USER`、`ORDER`等），每个业务序列独立。
```java
// 获取`DEFAULT`默认序列ID
long newId1 = ntSequence.next();
long newId2 = ntSequence.next();
long newId3 = ntSequence.next();

// 获取`USER`用户ID：
long newUserId1 = ntSequence.next("USER");
long newUserId2 = ntSequence.next("USER");
long newUserId3 = ntSequence.next("USER");

// 获取`ORDER`订单ID：
long newOrderId1 = ntSequence.next("ORDER");
long newOrderId2 = ntSequence.next("ORDER");
long newOrderId3 = ntSequence.next("ORDER");
```

# 测试用例
使用本项目根目录SQLite数据库进行测试：
```java
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
        dataSource.setUrl("jdbc:sqlite:./sequence-jdbc/SequenceJDBC.sqlite");
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
                // ignore
            }
        }
    }
}
```