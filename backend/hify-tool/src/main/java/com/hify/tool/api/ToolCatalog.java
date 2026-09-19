package com.hify.tool.api;

import java.util.Set;
import java.util.List;

/** Public tool-module port used by configuration modules to validate bindings. */
public interface ToolCatalog {
    List<ToolCatalogItem> items();

    default Set<String> availableToolNames() {
        return items().stream()
                .filter(ToolCatalogItem::available)
                .map(ToolCatalogItem::id)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }
}
