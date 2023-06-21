/**
 * Author: obullxl@163.com
 * Copyright (c) 2020-2023 All Rights Reserved.
 */
package cn.ntopic.sequence;

/**
 * 分布式序列服务
 *
 * @author obullxl 2023年06月21日: 新增
 */
public interface NTSequence {

    /**
     * 默认序列名称
     */
    String DEFAULT_SEQUENCE_NAME = "DEFAULT";

    /**
     * 序列名称最大长度
     */
    int MAX_SEQUENCE_NAME_LENGTH = 64;

    /**
     * 获取下一个序列值
     *
     * @return 获取默认序列的新的唯一的序列值 {@link #DEFAULT_SEQUENCE_NAME}
     * @throws IllegalArgumentException 参数非法
     */
    default long next() {
        return this.next(DEFAULT_SEQUENCE_NAME);
    }


    /**
     * 获取下一个序列值
     *
     * @param sequenceName 序列名称，非空，1~64字符，业务可随意指定（如：用户模块为`USER`，订单模块为`ORDER`等）
     * @return 新的唯一的序列值
     * @throws IllegalArgumentException 参数非法
     */
    long next(String sequenceName);
}
