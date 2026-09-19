package com.hify.api;

import com.hify.common.Result;
import com.hify.tool.api.ToolCatalog;
import com.hify.tool.api.ToolCatalogItem;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/tools")
public class ToolCatalogController {
    private final ToolCatalog tools;

    public ToolCatalogController(ToolCatalog tools) {
        this.tools = tools;
    }

    @GetMapping
    public Result<List<ToolCatalogItem>> list() {
        return Result.ok(tools.items());
    }
}
