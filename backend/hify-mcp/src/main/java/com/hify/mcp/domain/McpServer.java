package com.hify.mcp.domain;
import jakarta.persistence.*; import java.time.Instant;
@Entity @Table(name="mcp_servers")
public class McpServer {
 @Id private String id; private String name; private String transport; private String endpointUrl; private String credentialRef; private boolean enabled; private long serverRevision; private String schemaDigest; private String status; private String lastError; private Instant archivedAt; private Instant createdAt; private Instant updatedAt; @Version private long rowVersion;
 protected McpServer(){}
 public McpServer(String id,String name,String endpointUrl,String credentialRef,boolean enabled,Instant now){this.id=id;this.name=name;this.transport="STREAMABLE_HTTP";this.endpointUrl=endpointUrl;this.credentialRef=credentialRef;this.enabled=enabled;this.serverRevision=0;this.status="NEW";this.createdAt=now;this.updatedAt=now;}
 public void update(String name,String endpointUrl,String credentialRef,boolean enabled){this.name=name;this.endpointUrl=endpointUrl;this.credentialRef=credentialRef;this.enabled=enabled;this.updatedAt=Instant.now();}
 public void discovered(long revision,String digest){this.serverRevision=revision;this.schemaDigest=digest;this.status="READY";this.lastError=null;this.updatedAt=Instant.now();}
 public void failed(String error){this.status="FAILED";this.lastError=error==null?"MCP discovery failed":error.substring(0,Math.min(1000,error.length()));this.updatedAt=Instant.now();}
 public void archive(){this.archivedAt=Instant.now();this.enabled=false;this.status="ARCHIVED";this.updatedAt=this.archivedAt;}
 public String getId(){return id;} public String getName(){return name;} public String getTransport(){return transport;} public String getEndpointUrl(){return endpointUrl;} public String getCredentialRef(){return credentialRef;} public boolean isEnabled(){return enabled;} public long getServerRevision(){return serverRevision;} public String getSchemaDigest(){return schemaDigest;} public String getStatus(){return status;} public String getLastError(){return lastError;} public Instant getArchivedAt(){return archivedAt;} public Instant getCreatedAt(){return createdAt;} public Instant getUpdatedAt(){return updatedAt;}
}
