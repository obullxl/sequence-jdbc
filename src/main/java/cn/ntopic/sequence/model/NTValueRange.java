/**
 * Author: obullxl@163.com
 * Copyright (c) 2020-2023 All Rights Reserved.
 */
package cn.ntopic.sequence.model;

import java.io.Serializable;
import java.text.MessageFormat;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 值区间，[start,finish] 左闭右闭区间
 *
 * @author obullxl 2023年06月21日: 新增
 */
public class NTValueRange implements Serializable {

    /**
     * 起始值
     */
    private final long start;

    /**
     * 终止值
     */
    private final long finish;

    /**
     * 当前序列值
     */
    private final AtomicLong current;

    private NTValueRange(long start, long finish) {
        if (start <= 0 || start > finish) {
            throw new IllegalArgumentException(MessageFormat.format("值区间非法[{0},{1}]",
                    Long.toString(start), Long.toString(finish)));
        }

        this.start = start;
        this.finish = finish;
        this.current = new AtomicLong(start - 1L);
    }

    /**
     * 构建值区间
     */
    public static NTValueRange from(long start, long finish) {
        return new NTValueRange(start, finish);
    }

    public long getStart() {
        return start;
    }

    public long getFinish() {
        return finish;
    }

    /**
     * 获取下一个值
     */
    public final Optional<Long> nextValue() {
        long value = this.current.incrementAndGet();
        if (value <= this.finish) {
            return Optional.of(value);
        }

        return Optional.empty();
    }

    @Override
    public String toString() {
        return "NTValueRange[start=" + this.start + "; finish=" + this.finish + "; current=AtomicLong(" + this.current.get() + ")]";
    }
}
