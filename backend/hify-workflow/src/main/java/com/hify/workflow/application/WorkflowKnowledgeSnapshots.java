package com.hify.workflow.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hify.common.BizException;
import com.hify.common.ErrorCode;
import com.hify.knowledge.api.KnowledgeCorpusSnapshot;
import com.hify.knowledge.api.KnowledgeRetrievalPort;
import com.hify.workflow.api.WorkflowDraftRequest;
import com.hify.workflow.api.WorkflowNodeSpec;
import java.util.*;

/** Server-owned publication metadata; never accepted as draft authority. */
final class WorkflowKnowledgeSnapshots {
    private WorkflowKnowledgeSnapshots() {}

    static void rejectDraftSnapshots(WorkflowDraftRequest draft) {
        for(var node:draft.nodes())if(node.config().has("knowledgeSnapshot"))
            throw new BizException(ErrorCode.PARAM_ERROR,"knowledgeSnapshot 由服务端发布时生成，草稿不能指定");
    }

    static WorkflowDraftRequest freeze(WorkflowDraftRequest draft,KnowledgeRetrievalPort knowledge,ObjectMapper json) {
        Map<String,KnowledgeCorpusSnapshot> snapshots=new TreeMap<>();
        // Stable lock order, one corpus per base per publication, even with multiple lookup nodes.
        draft.nodes().stream().filter(WorkflowKnowledgeSnapshots::isKnowledge)
                .map(n->n.config().path("knowledgeBaseId").asText()).distinct().sorted()
                .forEach(id->snapshots.put(id,knowledge.freeze(id)));
        List<WorkflowNodeSpec> nodes=draft.nodes().stream().map(node->{
            if(!isKnowledge(node))return node;
            KnowledgeCorpusSnapshot snapshot=snapshots.get(node.config().path("knowledgeBaseId").asText());
            ObjectNode config=node.config().deepCopy();
            config.set("knowledgeSnapshot",json.createObjectNode().put("corpusVersionId",snapshot.id())
                    .put("manifestDigest",snapshot.manifestDigest()).put("revisionNo",snapshot.revisionNo())
                    .put("chunkCount",snapshot.chunkCount()));
            return new WorkflowNodeSpec(node.nodeKey(),node.type(),node.name(),config);
        }).toList();
        return new WorkflowDraftRequest(draft.name(),draft.description(),draft.schemaVersion(),nodes,draft.edges());
    }

    static KnowledgeCorpusSnapshot require(WorkflowNodeSpec node) {
        JsonNode snapshot=node.config().path("knowledgeSnapshot");
        if(!snapshot.isObject()||!snapshot.path("corpusVersionId").isTextual()||snapshot.path("corpusVersionId").asText().isBlank()
                ||!snapshot.path("manifestDigest").isTextual()||!snapshot.path("manifestDigest").asText().matches("[0-9a-f]{64}")
                ||!snapshot.path("revisionNo").isIntegralNumber()||!snapshot.path("revisionNo").canConvertToInt()||snapshot.path("revisionNo").asInt()<1
                ||!snapshot.path("chunkCount").isIntegralNumber()||!snapshot.path("chunkCount").canConvertToInt()||snapshot.path("chunkCount").asInt()<0)
            throw new BizException(ErrorCode.CONFLICT,"发布版本缺少有效知识快照，请先重新发布 Workflow，再发布 Agent 并创建新会话");
        return new KnowledgeCorpusSnapshot(snapshot.path("corpusVersionId").asText(),node.config().path("knowledgeBaseId").asText(),
                snapshot.path("revisionNo").asInt(),snapshot.path("manifestDigest").asText(),snapshot.path("chunkCount").asInt());
    }

    static boolean isKnowledge(WorkflowNodeSpec node){return "KNOWLEDGE".equalsIgnoreCase(node.type());}
}
