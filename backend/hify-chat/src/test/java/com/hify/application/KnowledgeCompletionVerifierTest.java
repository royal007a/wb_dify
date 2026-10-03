package com.hify.application;

import com.hify.common.*;
import com.hify.knowledge.api.*;
import com.hify.runtime.*;
import com.hify.runtime.plan.*;
import com.hify.runtime.state.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class KnowledgeCompletionVerifierTest {
    final KnowledgeRetrievalPort knowledge=mock(KnowledgeRetrievalPort.class);
    final KnowledgeCitation first=new KnowledgeCitation("old-chunk","doc",1,0,"七天退货","digest",1,1);
    final KnowledgeCompletionVerifier verifier=new KnowledgeCompletionVerifier(knowledge,List.of(first));

    @Test void candidatesAreUnverifiedAndCannotSatisfyRequiredClaim(){
        var initial=verifier.initialState();
        assertThat(initial.evidence()).allMatch(e->e.status()==EvidenceItem.Status.UNVERIFIED);
        assertThat(initial.hasVerifiedCoverageForRequiredClaims()).isFalse();
    }
    @Test void validSourceIsNotProofOfModelAnswerEntailment(){
        // Deliberately false answer: this gate does NOT claim semantic verification.
        var state=verifier.verify("终身免费退货。[K1]",verifier.initialState(),1,ExecutionControl.none());
        verify(knowledge).requireCanonicalChunk("old-chunk","digest");
        assertThat(state.hasVerifiedCoverageForRequiredClaims()).isTrue();
        assertThat(state.claims()).filteredOn(c->c.kind()==ClaimState.Kind.INFERENCE)
                .singleElement().satisfies(c->{assertThat(c.status()).isEqualTo(ClaimState.Status.UNVERIFIED);assertThat(c.requiredForCompletion()).isFalse();});
        assertThat(state.evidence()).filteredOn(e->e.type()==EvidenceItem.Type.MODEL_OUTPUT)
                .allMatch(e->e.status()==EvidenceItem.Status.UNVERIFIED);
    }
    @Test void missingUnknownAndMixedReferencesOpenGapWithoutReadingSource(){
        for(String answer:List.of("无引用","[K999]","[K1] 和 [K2]","[K999999999999999999999999999999999]")){
            var result=verifier.verify(answer,verifier.initialState(),1,ExecutionControl.none());
            assertThat(result.openBlockingGaps()).singleElement().satisfies(g->assertThat(g.resolutionKey()).isEqualTo("knowledge:references"));
        }
        verifyNoInteractions(knowledge);
    }
    @Test void canonicalFailureIsGapAndDoesNotLeakException(){
        when(knowledge.requireCanonicalChunk("old-chunk","digest")).thenThrow(new IllegalStateException("private upstream secret"));
        var state=verifier.verify("[K1]",verifier.initialState(),1,ExecutionControl.none());
        assertThat(state.hasVerifiedCoverageForRequiredClaims()).isFalse();
        assertThat(state.openBlockingGaps()).singleElement().satisfies(g->assertThat(g.description()).doesNotContain("private upstream secret"));
    }
    @Test void oldCheckpointWithoutCandidateMappingCannotFinish(){
        var state=verifier.verify("[K1]",ExecutionContextState.empty(),1,ExecutionControl.none());
        assertThat(state.openBlockingGaps()).hasSize(1);verifyNoInteractions(knowledge);
    }
    @Test void resolvingUserGapCannotBypassFinalReferenceCheck(){
        var failed=verifier.verify("无引用",verifier.initialState(),1,ExecutionControl.none());
        var resolved=failed.resolveGaps("knowledge:references");
        assertThat(resolved.openBlockingGaps()).isEmpty();
        assertThat(verifier.verify("依然无引用",resolved,1,ExecutionControl.none()).openBlockingGaps()).hasSize(1);
    }
    @Test void restoredLoopUsesCheckpointKMappingNotNewCandidateRanking(){
        var tools=new ToolRuntime();var capability=tools.snapshot("a",Set.of());
        var replacement=new KnowledgeCompletionVerifier(knowledge,List.of(new KnowledgeCitation("new-chunk","new-doc",2,0,"new","new-digest",1,1)));
        var checkpoint=ExecutionCheckpoint.capture(0,0,ExecutionPlan.initial("return policy"),verifier.initialState(),List.of(RuntimeMessage.user("return policy")));
        var identity=new RunRuntimeIdentity("r",capability.revision(),capability.toolSchemaDigest(),null,null,replacement);
        var result=new QueryLoop(tools).resume(checkpoint,r->RuntimeMessage.assistant("七天。[K1]"),"mock",0,capability,
                new QueryLoop.RunPolicy(3,3,4096,Duration.ofSeconds(5),()->false),QueryLoop.RunObserver.NOOP,identity);
        assertThat(result.reason()).isEqualTo(TerminalReason.COMPLETED);
        verify(knowledge).requireCanonicalChunk("old-chunk","digest");
        verifyNoMoreInteractions(knowledge);
    }
    @Test void referenceFailurePersistsGapAndNeverStreamsUnverifiedAnswer(){
        var tools=new ToolRuntime();var capability=tools.snapshot("a",Set.of());
        List<ExecutionCheckpoint> saved=new ArrayList<>();List<String> deltas=new ArrayList<>();
        var result=new QueryLoop(tools).run(List.of(RuntimeMessage.user("return policy")),r->RuntimeMessage.assistant("[K999]"),"mock",0,capability,
                new QueryLoop.RunPolicy(3,3,4096,Duration.ofSeconds(5),()->false),new QueryLoop.RunObserver(){
                    @Override public void onCheckpointCreated(ExecutionCheckpoint cp){saved.add(cp);}
                    @Override public void onModelDelta(int turn,String delta){deltas.add(delta);}
                },new RunRuntimeIdentity("r",capability.revision(),capability.toolSchemaDigest(),null,null,verifier));
        assertThat(result.reason()).isEqualTo(TerminalReason.HUMAN_INPUT_REQUIRED);
        assertThat(saved).hasSize(2);assertThat(saved.get(1).contextState().openBlockingGapIds()).isEqualTo(result.finalDecision().gapIds());
        assertThat(deltas).isEmpty();
    }
    @Test void cancellationAndExpiryStopCanonicalReads(){
        assertThatThrownBy(()->verifier.verify("[K1]",verifier.initialState(),1,ExecutionControl.withTimeout(Duration.ofSeconds(1),()->true)))
                .isInstanceOf(ExecutionCancelledException.class);
        ExecutionControl expired=mock(ExecutionControl.class);when(expired.isExpired()).thenReturn(true);
        var state=verifier.initialState();assertThat(verifier.verify("[K1]",state,1,expired)).isSameAs(state);
        verifyNoInteractions(knowledge);
    }
    @Test void cancellationDuringCanonicalReadCannotBecomeCompleted(){
        AtomicBoolean cancelled=new AtomicBoolean();
        when(knowledge.requireCanonicalChunk("old-chunk","digest")).thenAnswer(call->{cancelled.set(true);return null;});
        var tools=new ToolRuntime();var capability=tools.snapshot("a",Set.of());
        var result=new QueryLoop(tools).run(List.of(RuntimeMessage.user("policy")),r->RuntimeMessage.assistant("[K1]"),"mock",0,capability,
                new QueryLoop.RunPolicy(3,3,4096,Duration.ofSeconds(5),cancelled::get),QueryLoop.RunObserver.NOOP,
                new RunRuntimeIdentity("r",capability.revision(),capability.toolSchemaDigest(),null,null,verifier));
        assertThat(result.reason()).isEqualTo(TerminalReason.CANCELLED);
    }
}
