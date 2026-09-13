package com.hify.common;

import java.util.List;

public class PageResult<T> extends Result<List<T>> {
    private final long total;
    private final long page;
    private final long size;

    private PageResult(List<T> records, long total, long page, long size) {
        super(ErrorCode.OK.code(), ErrorCode.OK.message(), records);
        this.total = total;
        this.page = page;
        this.size = size;
    }

    public static <T> PageResult<T> of(List<T> records, long total, long page, long size) {
        return new PageResult<>(List.copyOf(records), total, page, size);
    }

    public long getTotal() { return total; }
    public long getPage() { return page; }
    public long getSize() { return size; }
}
