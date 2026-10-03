package com.hify.application;

import com.hify.common.ExecutionControl;
import com.hify.knowledge.api.*;
import com.hify.runtime.CompletionVerifier;
import com.hify.runtime.state.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.regex.Pattern;

/** Verifies source identity and citation integrity, NOT entailment of model-written claims. */
public final class KnowledgeCompletionVerifier implements CompletionVerifier {
    public static final String CLAIM="knowledge-source-references";
    private static final String PREFIX="knowledge:";
    private static final Pattern REF=Pattern.compile("\\[K([0-9]+)]");
    private final KnowledgeRetrievalPort knowledge;
    private final List<KnowledgeCitation> candidates;
    public KnowledgeCompletionVerifier(KnowledgeRetrievalPort knowledge,List<KnowledgeCitation> candidates){
        this.knowledge=knowledge;this.candidates=List.copyOf(candidates);
    }
    @Override public boolean withholdUnverifiedOutput(){return true;}
    @Override public ExecutionContextState initialState(){
        List<EvidenceItem> evidence=new ArrayList<>();int index=0;
        for(var candidate:candidates)evidence.add(new EvidenceItem(UUID.randomUUID().toString(),CLAIM,
                EvidenceItem.Type.SYSTEM_RECORD,EvidenceItem.Status.UNVERIFIED,
                PREFIX+"K"+(++index)+":"+candidate.chunkId(),candidate.digest(),"Knowledge retrieval candidate; not semantic proof",
                1,null,Instant.now()));
        ClaimState claim=sourceClaim(ClaimState.Status.UNVERIFIED,evidence.stream().map(EvidenceItem::id).toList());
        return new ExecutionContextState(1,0,List.of(claim),evidence,List.of(),null,0);
    }
    @Override public ExecutionContextState verify(String answer,ExecutionContextState state,int planVersion,ExecutionControl control){
        // Recover the exact K mapping from the checkpoint, never from a newly ranked result list.
        Map<String,EvidenceItem> byLabel=new HashMap<>();
        for(var item:state.evidence())if(item.claimId().equals(CLAIM)&&item.sourceRef().startsWith(PREFIX)){
            String[] parts=item.sourceRef().split(":",3);if(parts.length==3)byLabel.put(parts[1],item);
        }
        Set<String> used=new LinkedHashSet<>();var matcher=REF.matcher(answer==null?"":answer);
        while(matcher.find())used.add("K"+matcher.group(1));
        if(used.isEmpty()||!byLabel.keySet().containsAll(used))return gap(state,
                "知识回答缺少有效来源编号，或引用了不在本次候选中的来源；请补充问题或来源后重试。");
        try {
            for(String label:used){
                control.throwIfCancelled();if(control.isExpired())return state;
                EvidenceItem item=byLabel.get(label);
                knowledge.requireCanonicalChunk(item.sourceRef().split(":",3)[2],item.valueDigest());
            }
        }catch(RuntimeException unavailable){
            control.throwIfCancelled();return gap(state,"知识引用原文已不可校验，不能将本回答判为来源完整。");
        }
        Set<String> verifiedIds=used.stream().map(label->byLabel.get(label).id()).collect(java.util.stream.Collectors.toSet());
        List<EvidenceItem> evidence=new ArrayList<>();
        for(var item:state.evidence())evidence.add(verifiedIds.contains(item.id())?new EvidenceItem(item.id(),item.claimId(),item.type(),
                EvidenceItem.Status.VERIFIED,item.sourceRef(),item.valueDigest(),"Canonical source identity/digest checked; not answer entailment",
                planVersion,item.attemptId(),item.observedAt()):item);
        List<ClaimState> claims=new ArrayList<>(state.claims().stream().filter(c->!c.id().equals(CLAIM)).toList());
        claims.add(sourceClaim(ClaimState.Status.VERIFIED,new ArrayList<>(verifiedIds)));
        String modelClaim=UUID.randomUUID().toString(), modelEvidence=UUID.randomUUID().toString();
        claims.add(new ClaimState(modelClaim,"Model answer semantic claims have not been independently verified",ClaimState.Kind.INFERENCE,
                ClaimState.Status.UNVERIFIED,false,List.of(modelEvidence)));
        evidence.add(new EvidenceItem(modelEvidence,modelClaim,EvidenceItem.Type.MODEL_OUTPUT,EvidenceItem.Status.UNVERIFIED,
                "model:final",sha(answer),"Source-referenced model output, not verified semantic truth",planVersion,null,Instant.now()));
        return new ExecutionContextState(state.evidenceVersion()+1,state.gapVersion(),claims,evidence,state.gaps(),null,0)
                .resolveGaps("knowledge:references");
    }
    private ClaimState sourceClaim(ClaimState.Status status,List<String> evidence){return new ClaimState(CLAIM,
            "Final knowledge references identify canonical published sources",ClaimState.Kind.FACT,status,true,evidence);}
    private ExecutionContextState gap(ExecutionContextState state,String text){return state.openGap(GapState.open(
            GapState.Kind.UNVERIFIED_CLAIM,text,true,"knowledge:references",List.of("clarify_question","supply_source","start_new_conversation_after_republish"),null));}
    private String sha(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}
        catch(Exception e){throw new IllegalStateException("SHA-256 unavailable",e);}}
}
