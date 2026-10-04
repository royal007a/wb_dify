package com.hify.workflow.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hify.common.BizException;
import com.hify.common.ErrorCode;
import com.hify.workflow.api.WorkflowDraftRequest;
import com.hify.workflow.api.WorkflowDefinitionException;
import com.hify.workflow.domain.WorkflowVersion;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/** Publication envelope is not part of the client Draft DTO (including on older writers). */
final class WorkflowPublishedGraph {
    private WorkflowPublishedGraph() {}

    static String write(WorkflowDraftRequest graph,ObjectMapper json) {
        try {
            ObjectNode root=json.valueToTree(graph);
            if(graph.nodes().stream().anyMatch(WorkflowKnowledgeSnapshots::isKnowledge))
                root.putObject("publication").put("knowledgeSnapshotFormat",1);
            if(graph.nodes().stream().anyMatch(WorkflowExternalNodes::isExternal))
                root.withObject("/publication").put("externalNodeFormat",1);
            if(graph.nodes().stream().anyMatch(WorkflowInputs::declared))
                root.withObject("/publication").put("inputSchemaFormat",1);
            return json.writeValueAsString(root);
        } catch(Exception failure){throw new BizException(ErrorCode.PARAM_ERROR,"Workflow JSON 无法序列化");}
    }

    static WorkflowDraftRequest read(WorkflowVersion version,ObjectMapper json) {
        if(!checksum(version.getDslJson()).equals(version.getChecksum()))
            throw new BizException(ErrorCode.CONFLICT,"Workflow 发布版本校验和不匹配，请重新发布 Workflow 和 Agent，并创建新会话");
        try {
            JsonNode tree=com.hify.workflow.api.WorkflowJson.readTree(json,version.getDslJson());
            if(!(tree instanceof ObjectNode root))throw invalid();
            JsonNode stamp=root.remove("publication");
            WorkflowDraftRequest graph=json.treeToValue(root,WorkflowDraftRequest.class);
            if(graph.nodes()!=null&&graph.nodes().stream().anyMatch(WorkflowInputs::declared)
                    && (stamp==null||!stamp.path("inputSchemaFormat").isInt()||stamp.path("inputSchemaFormat").intValue()!=1))
                throw new WorkflowDefinitionException("输入schema缺少服务端发布标记，请重新发布 Workflow 和 Agent");
            if(graph.nodes()!=null&&graph.nodes().stream().anyMatch(WorkflowExternalNodes::isExternal)
                    && (stamp==null||!stamp.path("externalNodeFormat").isInt()||stamp.path("externalNodeFormat").intValue()!=1))
                throw new WorkflowDefinitionException("外部节点缺少服务端发布标记，请重新发布 Workflow 和 Agent");
            if(graph.nodes()!=null&&graph.nodes().stream().anyMatch(WorkflowKnowledgeSnapshots::isKnowledge)){
                if(stamp==null||!stamp.path("knowledgeSnapshotFormat").isInt()
                        ||stamp.path("knowledgeSnapshotFormat").intValue()!=1)throw invalid();
                graph.nodes().stream().filter(WorkflowKnowledgeSnapshots::isKnowledge).forEach(WorkflowKnowledgeSnapshots::require);
            }
            return graph;
        } catch(BizException failure){throw failure;}
        catch(Exception failure){throw invalid();}
    }

    static String checksum(String raw) {
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8)));}
        catch(Exception failure){throw new IllegalStateException("SHA-256 unavailable",failure);}
    }
    private static BizException invalid(){return new BizException(ErrorCode.CONFLICT,
            "发布版本缺少有效服务端知识快照，请先重新发布 Workflow，再发布 Agent 并创建新会话");}
}
