package com.hify.mcp.domain;
import jakarta.persistence.*; import java.time.Instant;
@Entity @Table(name="mcp_debug_calls")
public class McpDebugCall {
 @Id private String id; private String serverId; private long serverRevision; private String toolName; private String schemaDigest; private String argumentsDigest; @Column(columnDefinition="TEXT") private String resultJson; private boolean isError; private long elapsedMs; private Instant createdAt;
 protected McpDebugCall(){}
 public McpDebugCall(String id,String serverId,long revision,String toolName,String schemaDigest,String argumentsDigest,String resultJson,boolean error,long elapsedMs,Instant now){this.id=id;this.serverId=serverId;this.serverRevision=revision;this.toolName=toolName;this.schemaDigest=schemaDigest;this.argumentsDigest=argumentsDigest;this.resultJson=resultJson;this.isError=error;this.elapsedMs=elapsedMs;this.createdAt=now;}
 public String getId(){return id;} public String getServerId(){return serverId;} public long getServerRevision(){return serverRevision;} public String getToolName(){return toolName;} public String getSchemaDigest(){return schemaDigest;} public String getArgumentsDigest(){return argumentsDigest;} public String getResultJson(){return resultJson;} public boolean isError(){return isError;} public long getElapsedMs(){return elapsedMs;} public Instant getCreatedAt(){return createdAt;}
}
