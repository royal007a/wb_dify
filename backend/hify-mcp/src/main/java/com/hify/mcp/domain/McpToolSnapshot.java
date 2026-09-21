package com.hify.mcp.domain;
import jakarta.persistence.*; import java.time.Instant;
@Entity @Table(name="mcp_tool_snapshots")
public class McpToolSnapshot {
 @Id private String id; private String serverId; private long serverRevision; private String toolName; private String description; @Column(columnDefinition="TEXT") private String inputSchemaJson; private String risk; private String schemaDigest; private Instant createdAt;
 protected McpToolSnapshot(){}
 public McpToolSnapshot(String id,String serverId,long revision,String name,String description,String schema,String risk,String digest,Instant now){this.id=id;this.serverId=serverId;this.serverRevision=revision;this.toolName=name;this.description=description;this.inputSchemaJson=schema;this.risk=risk;this.schemaDigest=digest;this.createdAt=now;}
 public String getId(){return id;} public String getServerId(){return serverId;} public long getServerRevision(){return serverRevision;} public String getToolName(){return toolName;} public String getDescription(){return description;} public String getInputSchemaJson(){return inputSchemaJson;} public String getRisk(){return risk;} public String getSchemaDigest(){return schemaDigest;} public Instant getCreatedAt(){return createdAt;}
}
