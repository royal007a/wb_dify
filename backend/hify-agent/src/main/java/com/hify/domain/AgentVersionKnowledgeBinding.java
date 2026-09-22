package com.hify.domain;

import jakarta.persistence.*;
import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;

@Entity
@Table(name="agent_version_knowledge_bindings")
@IdClass(AgentVersionKnowledgeBinding.Key.class)
public class AgentVersionKnowledgeBinding {
    @Id private String agentVersionId;
    @Id private String knowledgeBaseId;
    private String corpusVersionId;
    private String manifestDigest;
    @Column(name="top_k") private int topK;
    private int priority;
    private Instant createdAt;
    protected AgentVersionKnowledgeBinding() {}
    public AgentVersionKnowledgeBinding(String agentVersionId,String knowledgeBaseId,String corpusVersionId,String manifestDigest,int topK,int priority,Instant createdAt){this.agentVersionId=agentVersionId;this.knowledgeBaseId=knowledgeBaseId;this.corpusVersionId=corpusVersionId;this.manifestDigest=manifestDigest;this.topK=topK;this.priority=priority;this.createdAt=createdAt;}
    public String getAgentVersionId(){return agentVersionId;} public String getKnowledgeBaseId(){return knowledgeBaseId;}
    public String getCorpusVersionId(){return corpusVersionId;} public String getManifestDigest(){return manifestDigest;}
    public int getTopK(){return topK;} public int getPriority(){return priority;} public Instant getCreatedAt(){return createdAt;}
    public static class Key implements Serializable {
        private String agentVersionId; private String knowledgeBaseId;
        public Key(){} public Key(String agentVersionId,String knowledgeBaseId){this.agentVersionId=agentVersionId;this.knowledgeBaseId=knowledgeBaseId;}
        @Override public boolean equals(Object value){return this==value||value instanceof Key key&&Objects.equals(agentVersionId,key.agentVersionId)&&Objects.equals(knowledgeBaseId,key.knowledgeBaseId);}
        @Override public int hashCode(){return Objects.hash(agentVersionId,knowledgeBaseId);}
    }
}
