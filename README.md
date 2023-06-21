JDBC高性能分布式序列（High performance distributed sequence with JDBC）

# 使用步骤
## JAR包依赖
```xml
<dependency>
    <groupId>cn.ntopic</groupId>
    <artifactId>sequence-jdbc</artifactId>
    <version>1.0.1</version>
</dependency>
```

## 创建数据表
+ 项目根目录有测试的SQLite数据库，可直接用户测试
+ 序列数据表名可以自定义（默认为`nt_sequence`），但是字段名（`name`和`value`）不可修改！
```sql
CREATE TABLE nt_sequence {
    name VARCHAR(64) NOT NULL COMMENT '序列名称',
    value bigint NOT NULL COMMENT '序列值',
    PRIMARY KEY(name)
} COMMENT '序列数据表'
;
```

## 实例化
```java
// 获取数据源，业务代码提供
// private DataSource dataSource;

// 实例化序列
@Bean("ntSequence")
public NTSequence ntSequence(DataSource dataSource) {
    NTSequenceImpl impl = new NTSequenceImpl(dataSource);
    impl.setTableName("nt_sequence");
    
    // 以下参数为默认值，可无需设置
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