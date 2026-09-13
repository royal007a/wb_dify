package com.hify.api;

import com.hify.common.PageResult;
import com.hify.common.Result;
import com.hify.demo.api.DemoItemCreateRequest;
import com.hify.demo.api.DemoItemResponse;
import com.hify.demo.api.DemoItemService;
import com.hify.demo.api.DemoItemUpdateRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/v1/demo-items")
public class DemoItemController {
    private final DemoItemService service;

    public DemoItemController(DemoItemService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Result<Long> create(@Valid @RequestBody DemoItemCreateRequest request) {
        return Result.ok(service.create(request));
    }

    @GetMapping("/{id}")
    public Result<DemoItemResponse> get(@PathVariable Long id) {
        return Result.ok(service.get(id));
    }

    @GetMapping
    public PageResult<DemoItemResponse> list(
            @RequestParam(required = false) @Min(1) Integer page,
            @RequestParam(required = false) @Min(1) @Max(100) Integer pageSize) {
        return service.list(page, pageSize);
    }

    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable Long id, @Valid @RequestBody DemoItemUpdateRequest request) {
        service.update(id, request);
        return Result.ok();
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return Result.ok();
    }
}

