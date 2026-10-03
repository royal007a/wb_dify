package com.hify.mcp.infrastructure;
import com.hify.mcp.domain.McpCredential;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
public interface McpCredentialRepository extends JpaRepository<McpCredential, String> {
    Optional<McpCredential> findByIdAndServerId(String id, String serverId);
}
