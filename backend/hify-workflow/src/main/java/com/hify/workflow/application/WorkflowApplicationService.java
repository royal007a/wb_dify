package com.hify.workflow.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hify.common.BizException;
import com.hify.common.ErrorCode;
import com.hify.common.PageResult;
import com.hify.workflow.api.*;
import com.hify.workflow.domain.*;
import com.hify.workflow.infrastructure.*;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;

@Service
public class WorkflowApplicationService {
 private final WorkflowDefinitionRepository workflows; private final WorkflowNodeRepository nodes; private final WorkflowEdgeRepository edges; private final WorkflowVersionRepository versions; private final WorkflowGraphValidator validator; private final ObjectMapper json;
 public WorkflowApplicationService(WorkflowDefinitionRepository workflows,WorkflowNodeRepository nodes,WorkflowEdgeRepository edges,WorkflowVersionRepository versions,WorkflowGraphValidator validator,ObjectMapper json){this.workflows=workflows;this.nodes=nodes;this.edges=edges;this.versions=versions;this.validator=validator;this.json=json;}

 @Transactional public String create(WorkflowDraftRequest request){validator.validate(request);String name=request.name().trim();if(workflows.existsByNameAndArchivedAtIsNull(name))throw duplicate();String id=UUID.randomUUID().toString();Instant now=Instant.now();WorkflowDefinition definition=new WorkflowDefinition(id,name,clean(request.description()),version(request),now);try{workflows.saveAndFlush(definition);}catch(DataIntegrityViolationException e){throw duplicate();}replaceGraph(id,request,now);return id;}
 @Transactional public void update(String id,WorkflowDraftRequest request){validator.validate(request);WorkflowDefinition definition=require(id);String name=request.name().trim();if(workflows.existsByNameAndIdNotAndArchivedAtIsNull(name,id))throw duplicate();definition.update(name,clean(request.description()),version(request));edges.deleteByWorkflowId(id);nodes.deleteByWorkflowId(id);edges.flush();nodes.flush();replaceGraph(id,request,Instant.now());workflows.save(definition);}
 @Transactional(readOnly=true) public WorkflowResponse get(String id){return response(require(id));}
 @Transactional(readOnly=true) public PageResult<WorkflowResponse> list(Integer page,Integer size){int p=page==null?1:Math.max(1,page),s=size==null?20:Math.min(100,Math.max(1,size));var result=workflows.findByArchivedAtIsNull(PageRequest.of(p-1,s,Sort.by(Sort.Direction.DESC,"updatedAt")));return PageResult.of(result.getContent().stream().map(this::response).toList(),result.getTotalElements(),p,s);}
 @Transactional public void archive(String id){require(id).archive();}
 @Transactional(readOnly=true) public void validate(String id){validator.validate(draft(require(id)));}
 @Transactional public WorkflowVersionResponse publish(String id){WorkflowDefinition workflow=require(id);WorkflowDraftRequest draft=draft(workflow);validator.validate(draft);String dsl=write(draft);String checksum=digest(dsl);int no=Math.toIntExact(versions.countByWorkflowId(id)+1);WorkflowVersion entity=new WorkflowVersion(UUID.randomUUID().toString(),id,no,workflow.getSchemaVersion(),dsl,checksum,Instant.now());versions.save(entity);workflow.published(entity.getId());workflows.save(workflow);return versionResponse(entity);}
 @Transactional(readOnly=true) public List<WorkflowVersionResponse> versions(String id){require(id);return versions.findByWorkflowIdOrderByVersionNoDesc(id).stream().map(this::versionResponse).toList();}
 WorkflowVersion requireVersion(String id){return versions.findById(id).orElseThrow(()->new BizException(ErrorCode.NOT_FOUND,"Workflow 版本不存在"));}

 private void replaceGraph(String id,WorkflowDraftRequest request,Instant now){nodes.saveAll(request.nodes().stream().map(n->new WorkflowNodeEntity(UUID.randomUUID().toString(),id,n.nodeKey().trim(),n.type().toUpperCase(Locale.ROOT),n.name().trim(),write(n.config()),now)).toList());edges.saveAll((request.edges()==null?List.<WorkflowEdgeSpec>of():request.edges()).stream().map(e->new WorkflowEdgeEntity(UUID.randomUUID().toString(),id,e.edgeKey().trim(),e.sourceNodeKey().trim(),e.targetNodeKey().trim(),e.condition(),e.defaultBranch(),now)).toList());}
 private WorkflowDraftRequest draft(WorkflowDefinition w){List<WorkflowNodeSpec> ns=nodes.findByWorkflowIdOrderByNodeKey(w.getId()).stream().map(n->new WorkflowNodeSpec(n.getNodeKey(),n.getNodeType(),n.getName(),readTree(n.getConfigJson()))).toList();List<WorkflowEdgeSpec> es=edges.findByWorkflowIdOrderByEdgeKey(w.getId()).stream().map(e->new WorkflowEdgeSpec(e.getEdgeKey(),e.getSourceNodeKey(),e.getTargetNodeKey(),e.getConditionValue(),e.isDefaultBranch())).toList();return new WorkflowDraftRequest(w.getName(),w.getDescription(),w.getSchemaVersion(),ns,es);}
 private WorkflowResponse response(WorkflowDefinition w){WorkflowDraftRequest d=draft(w);return new WorkflowResponse(w.getId(),w.getName(),w.getDescription(),w.getSchemaVersion(),w.getDraftRevision(),w.getPublishedVersionId(),d.nodes(),d.edges(),w.getCreatedAt(),w.getUpdatedAt());}
 private WorkflowVersionResponse versionResponse(WorkflowVersion v){return new WorkflowVersionResponse(v.getId(),v.getWorkflowId(),v.getVersionNo(),v.getSchemaVersion(),v.getChecksum(),v.getCreatedAt());}
 private WorkflowDefinition require(String id){return workflows.findByIdAndArchivedAtIsNull(id).orElseThrow(()->new BizException(ErrorCode.NOT_FOUND,"Workflow 不存在"));}
 private int version(WorkflowDraftRequest d){return d.schemaVersion()==null?1:d.schemaVersion();} private String clean(String v){return v==null?"":v.trim();}
 private BizException duplicate(){return new BizException(ErrorCode.CONFLICT,"Workflow 名称已存在");}
 private String write(Object value){try{return json.writeValueAsString(value);}catch(Exception e){throw new BizException(ErrorCode.PARAM_ERROR,"Workflow JSON 无法序列化");}}
 private com.fasterxml.jackson.databind.JsonNode readTree(String value){try{return json.readTree(value);}catch(Exception e){throw new IllegalStateException(e);}}
 private String digest(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
}
