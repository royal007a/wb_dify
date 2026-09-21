package com.hify.mcp.infrastructure;
import com.hify.mcp.domain.McpServer; import org.springframework.data.jpa.repository.JpaRepository; import java.util.List;
public interface McpServerRepository extends JpaRepository<McpServer,String>{boolean existsByName(String name);List<McpServer> findAllByArchivedAtIsNullOrderByCreatedAtDesc();}
