package com.hify.demo.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record DemoItemUpdateRequest(
        @NotBlank(message = "name 不能为空")
        @Size(max = 255, message = "name 不能超过 255 个字符")
        String name,
        @NotNull(message = "status 不能为空")
        Integer status
) {}

