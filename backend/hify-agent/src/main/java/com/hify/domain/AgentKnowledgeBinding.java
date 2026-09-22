package com.hify.domain;

import jakarta.persistence.*;
import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;

@Entity
@Table(name="agent_knowledge_bindings")
@IdClass(AgentKnowledgeBinding.Key.class)
public class AgentKnowledgeBinding {
    @Id private String agentId;
    @Id private String knowledgeBaseId;
    @Column(name="top_k") private int topK;
    private int priority;
    private Instant createdAt;
    protected AgentKnowledgeBinding() {}
    public AgentKnowledgeBinding(String agentId,String knowledgeBaseId,int topK,int priority,Instant createdAt){this.agentId=agentId;this.knowledgeBaseId=knowledgeBaseId;this.topK=topK;this.priority=priority;this.createdAt=createdAt;}
    public String getAgentId(){return agentId;} public String getKnowledgeBaseId(){return knowledgeBaseId;}
    public int getTopK(){return topK;} public int getPriority(){return priority;} public Instant getCreatedAt(){return createdAt;}
    public static class Key implements Serializable {
        private String agentId; private String knowledgeBaseId;
        public Key(){} public Key(String agentId,String knowledgeBaseId){this.agentId=agentId;this.knowledgeBaseId=knowledgeBaseId;}
        @Override public boolean equals(Object value){return this==value||value instanceof Key key&&Objects.equals(agentId,key.agentId)&&Objects.equals(knowledgeBaseId,key.knowledgeBaseId);}
        @Override public int hashCode(){return Objects.hash(agentId,knowledgeBaseId);}
    }
}
