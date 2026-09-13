package com.hify.demo.application;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hify.common.BizException;
import com.hify.common.ErrorCode;
import com.hify.common.PageHelper;
import com.hify.common.PageResult;
import com.hify.demo.api.DemoItemCreateRequest;
import com.hify.demo.api.DemoItemResponse;
import com.hify.demo.api.DemoItemService;
import com.hify.demo.api.DemoItemUpdateRequest;
import com.hify.demo.entity.DemoItem;
import com.hify.demo.mapper.DemoItemMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DemoItemServiceImpl implements DemoItemService {
    private final DemoItemMapper mapper;

    public DemoItemServiceImpl(DemoItemMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    @Transactional
    public Long create(DemoItemCreateRequest request) {
        DemoItem item = new DemoItem();
        item.setName(request.name().trim());
        item.setStatus(request.status());
        mapper.insert(item);
        return item.getId();
    }

    @Override
    @Transactional(readOnly = true)
    public DemoItemResponse get(Long id) {
        return response(requireItem(id));
    }

    @Override
    @Transactional(readOnly = true)
    public PageResult<DemoItemResponse> list(Integer page, Integer pageSize) {
        Page<DemoItem> query = PageHelper.toPage(page, pageSize);
        return PageHelper.toPageResult(mapper.selectPage(query, null), DemoItemServiceImpl::response);
    }

    @Override
    @Transactional
    public void update(Long id, DemoItemUpdateRequest request) {
        DemoItem item = requireItem(id);
        item.setName(request.name().trim());
        item.setStatus(request.status());
        mapper.updateById(item);
    }

    @Override
    @Transactional
    public void delete(Long id) {
        if (mapper.deleteById(id) == 0) {
            throw new BizException(ErrorCode.NOT_FOUND, "DemoItem 不存在");
        }
    }

    private DemoItem requireItem(Long id) {
        DemoItem item = mapper.selectById(id);
        if (item == null) throw new BizException(ErrorCode.NOT_FOUND, "DemoItem 不存在");
        return item;
    }

    private static DemoItemResponse response(DemoItem item) {
        return new DemoItemResponse(item.getId(), item.getName(), item.getStatus(),
                item.getCreatedAt(), item.getUpdatedAt());
    }
}

