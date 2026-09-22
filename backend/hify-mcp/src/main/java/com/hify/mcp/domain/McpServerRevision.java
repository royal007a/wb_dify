package com.hify.mcp.domain;

import jakarta.persistence.*;
import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;

@Entity @Table(name="mcp_server_revisions") @IdClass(McpServerRevision.Key.class)
public class McpServerRevision {
 @Id private String serverId; @Id private long serverRevision; private String endpointUrl; private String credentialRef; private String schemaDigest; private Instant createdAt;
 protected McpServerRevision(){}
 public McpServerRevision(String serverId,long serverRevision,String endpointUrl,String credentialRef,String schemaDigest,Instant createdAt){this.serverId=serverId;this.serverRevision=serverRevision;this.endpointUrl=endpointUrl;this.credentialRef=credentialRef;this.schemaDigest=schemaDigest;this.createdAt=createdAt;}
 public String getServerId(){return serverId;} public long getServerRevision(){return serverRevision;} public String getEndpointUrl(){return endpointUrl;} public String getCredentialRef(){return credentialRef;} public String getSchemaDigest(){return schemaDigest;}
 public static class Key implements Serializable { public String serverId; public long serverRevision; public Key(){} public Key(String s,long r){serverId=s;serverRevision=r;} @Override public boolean equals(Object o){return o instanceof Key k&&serverRevision==k.serverRevision&&Objects.equals(serverId,k.serverId);}@Override public int hashCode(){return Objects.hash(serverId,serverRevision);} }
}
