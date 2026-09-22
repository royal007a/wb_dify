package com.hify.mcp.infrastructure;

import com.hify.mcp.domain.McpServerRevision;
import org.springframework.data.jpa.repository.JpaRepository;

public interface McpServerRevisionRepository extends JpaRepository<McpServerRevision,McpServerRevision.Key> {}
