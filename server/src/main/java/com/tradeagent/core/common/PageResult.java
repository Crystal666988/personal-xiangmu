package com.tradeagent.core.common;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.Collections;
import java.util.List;

/**
 * 分页数据响应封装类
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PageResult<T> implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 总记录数 */
    private long total;

    /** 当前页码 (从 1 开始) */
    private long pageNum;

    /** 每页大小 */
    private long pageSize;

    /** 总页数 */
    private long pages;

    /** 当前页数据列表 */
    private List<T> records;

    public static <T> PageResult<T> of(long total, long pageNum, long pageSize, List<T> records) {
        long pages = (pageSize > 0) ? (total + pageSize - 1) / pageSize : 0;
        return PageResult.<T>builder()
                .total(total)
                .pageNum(pageNum)
                .pageSize(pageSize)
                .pages(pages)
                .records(records != null ? records : Collections.emptyList())
                .build();
    }
}
