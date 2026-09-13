package com.hify.demo.api;

import com.hify.common.PageResult;

public interface DemoItemService {
    Long create(DemoItemCreateRequest request);
    DemoItemResponse get(Long id);
    PageResult<DemoItemResponse> list(Integer page, Integer pageSize);
    void update(Long id, DemoItemUpdateRequest request);
    void delete(Long id);
}

