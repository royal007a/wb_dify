package com.hify.tool.api;

/** Stable management-plane projection. Runtime schemas remain internal to the tool module. */
public record ToolCatalogItem(
        String id,
        String displayName,
        String description,
        String source,
        String risk,
        boolean available
) {}
