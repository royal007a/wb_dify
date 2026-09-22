package com.hify.mcp.infrastructure;
import com.hify.mcp.domain.McpToolSnapshot; import org.springframework.data.jpa.repository.JpaRepository; import java.util.*;
public interface McpToolSnapshotRepository extends JpaRepository<McpToolSnapshot,String>{List<McpToolSnapshot> findAllByServerIdAndServerRevisionOrderByToolName(String serverId,long revision);List<McpToolSnapshot> findAllByServerIdIn(Collection<String> serverIds);Optional<McpToolSnapshot> findByServerIdAndServerRevisionAndToolName(String serverId,long revision,String name);}
