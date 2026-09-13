package com.hify.common;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;

import java.util.function.Function;

public final class PageHelper {
    public static final long DEFAULT_PAGE = 1;
    public static final long DEFAULT_PAGE_SIZE = 20;
    public static final long MAX_PAGE_SIZE = 100;

    private PageHelper() {}

    public static <T> Page<T> toPage(Integer page, Integer pageSize) {
        long safePage = page == null || page < 1 ? DEFAULT_PAGE : page;
        long safeSize = pageSize == null || pageSize < 1
                ? DEFAULT_PAGE_SIZE : Math.min(pageSize, MAX_PAGE_SIZE);
        return Page.of(safePage, safeSize);
    }

    public static <T> PageResult<T> toPageResult(IPage<T> page) {
        return PageResult.of(page.getRecords(), page.getTotal(), page.getCurrent(), page.getSize());
    }

    public static <S, T> PageResult<T> toPageResult(IPage<S> page, Function<S, T> mapper) {
        return PageResult.of(page.getRecords().stream().map(mapper).toList(),
                page.getTotal(), page.getCurrent(), page.getSize());
    }
}

