package com.hify.workflow.application;

import com.hify.common.*;
import com.hify.knowledge.api.KnowledgeRetrievalPort;
import com.hify.workflow.api.WorkflowRunResponse;
import com.hify.workflow.domain.*;
import com.hify.workflow.infrastructure.*;
import org.junit.jupiter.api.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static com.hify.workflow.application.WorkflowFixtures.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class WorkflowControlTest {
    private final ExecutorService caller = Executors.newSingleThreadExecutor();
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final WorkflowApplicationService app = mock(WorkflowApplicationService.class);
    private final WorkflowRunRepository runs = mock(WorkflowRunRepository.class);
    private final WorkflowNodeRunRepository nodes = mock(WorkflowNodeRunRepository.class);
    private final KnowledgeRetrievalPort knowledge = mock(KnowledgeRetrievalPort.class);
    private static final com.hify.knowledge.api.KnowledgeCorpusSnapshot SNAPSHOT =
            new com.hify.knowledge.api.KnowledgeCorpusSnapshot("corpus","kb",1,"a".repeat(64),0);
    private final Map<Integer,WorkflowNodeRun> recorded = new ConcurrentHashMap<>();

    @AfterEach void cleanup() throws Exception {
        caller.shutdownNow(); worker.shutdownNow();
        assertThat(caller.awaitTermination(5,TimeUnit.SECONDS)).isTrue();
        assertThat(worker.awaitTermination(5,TimeUnit.SECONDS)).isTrue();
    }

    @Test void cancelledBlockingNodeInterruptsWorkerAndNeverStartsNextNode() throws Exception {
        var engine = engine(worker);
        var entered = new CountDownLatch(1); var interrupted = new CountDownLatch(1);
        when(knowledge.searchSnapshot(SNAPSHOT,"input",3)).thenAnswer(invocation -> {
            entered.countDown();
            try { new CountDownLatch(1).await(); return List.of(); }
            catch (InterruptedException stopped) { interrupted.countDown(); throw stopped; }
        });
        var cancelled = new AtomicBoolean();
        var control = ExecutionControl.withTimeout(Duration.ofMinutes(1),cancelled::get);
        Future<WorkflowRunResponse> pending = caller.submit(() -> engine.execute("v1","input",control));
        assertThat(entered.await(10,TimeUnit.SECONDS)).isTrue();
        cancelled.set(true);
        var result = pending.get(3,TimeUnit.SECONDS);
        assertThat(interrupted.await(1,TimeUnit.SECONDS)).as("worker interrupted before cleanup").isTrue();
        assertThat(result.status()).isEqualTo("CANCELLED");
        assertThat(result.nodes()).extracting(WorkflowRunResponse.NodeRunResponse::nodeKey).containsExactly("start","lookup");
        assertThat(result.nodes().get(1).status()).isEqualTo("CANCELLED");
        assertThat(result.context()).doesNotContainKey("lookup.citations");
    }

    @Test void expiryDuringBlockedNodeInterruptsWorkerAndDoesNotResetBudget() throws Exception {
        var engine = engine(worker);
        var entered = new CountDownLatch(1); var interrupted = new CountDownLatch(1);
        var expired = new AtomicBoolean();
        ExecutionControl control = mock(ExecutionControl.class);
        when(control.withShutdown(any())).thenReturn(control); // Preserve the deterministic virtual deadline in this fixture.
        when(control.isExpired()).thenAnswer(invocation -> expired.get());
        when(control.remaining(any())).thenReturn(Duration.ofMillis(50));
        when(knowledge.searchSnapshot(SNAPSHOT,"input",3)).thenAnswer(invocation -> {
            entered.countDown();
            try { new CountDownLatch(1).await(); return List.of(); }
            catch (InterruptedException stopped) { interrupted.countDown(); throw stopped; }
        });
        Future<WorkflowRunResponse> pending = caller.submit(() -> engine.execute("v1","input",control));
        assertThat(entered.await(10,TimeUnit.SECONDS)).isTrue();
        expired.set(true);
        var result = pending.get(3,TimeUnit.SECONDS);
        assertThat(interrupted.await(1,TimeUnit.SECONDS)).isTrue();
        assertThat(result.status()).isEqualTo("TIMED_OUT");
        assertThat(result.nodes().get(1).status()).isEqualTo("TIMED_OUT");
        assertThat(result.nodes()).hasSize(2);
    }

    @Test void queuedCancellationPreventsLaterExecution() throws Exception {
        var queued = new AtomicReference<Runnable>(); var enqueued = new CountDownLatch(1);
        Executor queue = action -> { queued.set(action);enqueued.countDown(); };
        var invoked = new AtomicBoolean(); var cancelled = new AtomicBoolean();
        var control = ExecutionControl.withTimeout(Duration.ofMinutes(1),cancelled::get);
        Future<String> pending = caller.submit(() -> WorkflowControl.call(control,queue,()-> { invoked.set(true);return "late"; }));
        assertThat(enqueued.await(10,TimeUnit.SECONDS)).isTrue();
        cancelled.set(true);
        assertThatThrownBy(() -> pending.get(3,TimeUnit.SECONDS)).hasCauseInstanceOf(ExecutionCancelledException.class);
        queued.get().run();
        assertThat(invoked).isFalse();
    }

    @Test void wallClockBudgetIncludesQueueWait() throws Exception {
        var queued = new AtomicReference<Runnable>();
        var control = ExecutionControl.withTimeout(Duration.ofMillis(150),()->false);
        assertThatThrownBy(() -> WorkflowControl.call(control,queued::set,()->"never"))
                .isInstanceOf(WorkflowControl.DeadlineExceeded.class);
        if (queued.get() != null) assertThat((Future<?>) queued.get()).isDone();
    }

    @Test void preCancelledAndExpiredCallsDoNotSubmitAnything() {
        Executor executor = mock(Executor.class);
        assertThatThrownBy(() -> WorkflowControl.call(ExecutionControl.withTimeout(Duration.ofMinutes(1),()->true),executor,()->"no"))
                .isInstanceOf(ExecutionCancelledException.class);
        assertThatThrownBy(() -> WorkflowControl.call(ExecutionControl.withTimeout(Duration.ofNanos(1),()->false),executor,()->"no"))
                .isInstanceOf(WorkflowControl.DeadlineExceeded.class);
        verifyNoInteractions(executor);
    }

    @Test void saturatedPoolFailsWithoutRunningRetrievalOnCaller() throws Exception {
        var engine = engine(task -> { throw new RejectedExecutionException("capacity exhausted"); });
        var result = engine.execute("v1","input");
        assertThat(result.status()).isEqualTo("FAILED");
        verifyNoInteractions(knowledge);
        assertThat(result.nodes()).hasSize(2);
    }

    @Test void cancelAfterEndNodeStillPreventsWorkflowSuccessCommit() throws Exception {
        var cancelled = new AtomicBoolean();
        var engine = engine(worker);
        when(knowledge.searchSnapshot(SNAPSHOT,"input",3)).thenReturn(List.of());
        doAnswer(invocation -> {
            WorkflowNodeRun node = invocation.getArgument(0);recorded.put(node.getSequenceNo(),node);
            if(node.getNodeKey().equals("end") && node.getStatus().equals("SUCCEEDED")) cancelled.set(true);
            return node;
        }).when(nodes).save(any());
        var result = engine.execute("v1","input",ExecutionControl.withTimeout(Duration.ofMinutes(1),cancelled::get));
        assertThat(result.status()).isEqualTo("CANCELLED");
        assertThat(result.output()).isNull();
    }

    @Test void ioPoolIsBoundedAndNeverUsesCallerRuns() {
        ThreadPoolExecutor pool = (ThreadPoolExecutor)new WorkflowExecutionConfiguration().workflowIoExecutor();
        try {
            assertThat(pool.getCorePoolSize()).isEqualTo(2);
            assertThat(pool.getMaximumPoolSize()).isEqualTo(8);
            assertThat(pool.getQueue().remainingCapacity()).isEqualTo(32);
            assertThat(pool.getRejectedExecutionHandler()).isInstanceOf(ThreadPoolExecutor.AbortPolicy.class);
        } finally { pool.shutdownNow(); }
    }

    @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(booleans={false,true})
    void observedBusinessFailureIsNotRewrittenByLaterCancellationOrDeadline(boolean deadline) throws Exception {
        var cancelled=new AtomicBoolean();var expired=new AtomicBoolean();
        ExecutionControl control=mock(ExecutionControl.class);
        when(control.withShutdown(any())).thenReturn(control);
        when(control.isCancelled()).thenAnswer(invocation->cancelled.get());
        when(control.isExpired()).thenAnswer(invocation->expired.get());
        when(control.remaining(any())).thenReturn(Duration.ofMillis(50));
        var engine=engine(Runnable::run);
        when(knowledge.searchSnapshot(SNAPSHOT,"input",3)).thenThrow(new IllegalStateException("real business failure"));
        doAnswer(invocation->{
            WorkflowNodeRun node=invocation.getArgument(0);recorded.put(node.getSequenceNo(),node);
            if(node.getStatus().equals("FAILED")) {if(deadline)expired.set(true);else cancelled.set(true);}
            return node;
        }).when(nodes).save(any());
        var result=engine.execute("v1","input",control);
        assertThat(result.status()).isEqualTo("FAILED");
        assertThat(result.errorMessage()).contains("real business failure");
        assertThat(result.nodes().get(1).status()).isEqualTo("FAILED");
    }

    @Test void alreadyFailedFutureIsObservedBeforeALaterCancelSignal() {
        var cancelled=new AtomicBoolean();var failure=new IllegalStateException("computed failure");
        var control=ExecutionControl.withTimeout(Duration.ofSeconds(10),cancelled::get);
        Executor completesThenCancels=task->{task.run();cancelled.set(true);};
        assertThatThrownBy(()->WorkflowControl.call(control,completesThenCancels,()->{throw failure;})).isSameAs(failure);
    }

    private WorkflowEngine engine(Executor executor) throws Exception {
        when(runs.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        var graph = draft(List.of(node("start","START"),
                node("lookup","KNOWLEDGE","knowledgeBaseId","kb","query","{{start.userMessage}}"),
                node("end","END","output","should-not-run-after-cancel")),edge("start","lookup"),edge("lookup","end"));
        ((com.fasterxml.jackson.databind.node.ObjectNode)graph.nodes().get(1).config()).set("knowledgeSnapshot",
                JSON.createObjectNode().put("corpusVersionId",SNAPSHOT.id()).put("manifestDigest",SNAPSHOT.manifestDigest()).put("revisionNo",1).put("chunkCount",0));
        when(app.requireVersion("v1")).thenReturn(new WorkflowVersion("v1","w1",1,1,JSON.writeValueAsString(graph),"digest",Instant.now()));
        when(nodes.save(any())).thenAnswer(invocation -> {
            WorkflowNodeRun node = invocation.getArgument(0);recorded.put(node.getSequenceNo(),node);return node;
        });
        when(nodes.findByWorkflowRunIdOrderBySequenceNo(anyString())).thenAnswer(invocation -> recorded.values().stream()
                .sorted(Comparator.comparingInt(WorkflowNodeRun::getSequenceNo)).toList());
        return new WorkflowEngine(app,runs,nodes,knowledge,JSON,new WorkflowGraphValidator(),executor,Duration.ofSeconds(60));
    }
}
