package com.hify.knowledge.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hify.common.*;
import com.hify.provider.api.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class SemanticEmbeddingControlTest {
    private final EmbeddingService service=mock(EmbeddingService.class);
    private final ExecutionLifecycle lifecycle=mock(ExecutionLifecycle.class);
    private final ObjectMapper json=new ObjectMapper();
    private final SemanticEmbeddings semantic=new SemanticEmbeddings(service,json,lifecycle);
    private final EmbeddingProfile profile=new EmbeddingProfile("fixture",ProviderType.OPENAI_COMPATIBLE,
            "https://example.invalid",null,"fixture",2);

    @Test void childReceivesParentDeadlineAndPreservesCancellationAfterCreation() throws Exception {
        var cancelled=new AtomicBoolean();
        var parent=ExecutionControl.withTimeout(Duration.ofSeconds(30),cancelled::get);
        when(service.embed(eq(profile),eq(List.of("question")),any())).thenAnswer(call->{
            ExecutionControl child=call.getArgument(2);
            assertThat(child.remaining(Duration.ofMinutes(1))).isLessThanOrEqualTo(Duration.ofSeconds(30));
            cancelled.set(true);
            assertThat(child.isCancelled()).isTrue();
            return List.of(new float[]{1,0});
        });
        assertThatThrownBy(()->semantic.embed(json.writeValueAsString(profile),List.of("question"),parent))
                .isInstanceOf(ExecutionCancelledException.class);
        verify(service).embed(eq(profile),eq(List.of("question")),any());
    }
    @Test void ownAttemptLimitRemainsFortyFiveSecondsForLongParent() throws Exception {
        when(service.embed(eq(profile),anyList(),any())).thenAnswer(call->{
            ExecutionControl child=call.getArgument(2);
            assertThat(child.remaining(Duration.ofDays(1))).isLessThanOrEqualTo(Duration.ofSeconds(45));
            return List.of(new float[]{1,0});
        });
        assertThat(semantic.embed(json.writeValueAsString(profile),List.of("question"),
                ExecutionControl.withTimeout(Duration.ofHours(1),()->false))).hasSize(1);
        verify(service).embed(eq(profile),anyList(),any());
    }
    @Test void alreadyCancelledDoesNotEvenDecodeConfigurationOrCallProvider() {
        assertThatThrownBy(()->semantic.embed("not-json",List.of("q"),
                ExecutionControl.withTimeout(Duration.ofMinutes(1),()->true)))
                .isInstanceOf(ExecutionCancelledException.class);
        verifyNoInteractions(service);
    }
    @Test void expiredParentStopsBeforeProvider() {
        assertThatThrownBy(()->semantic.embed("not-json",List.of("q"),
                ExecutionControl.withTimeout(Duration.ofNanos(1),()->false)))
                .isInstanceOf(ExecutionTimedOutException.class);
        verifyNoInteractions(service);
    }
    @Test void cancellationAfterLateProviderFailureWinsOverSourceFailureAndShutdown() throws Exception {
        var cancelled=new AtomicBoolean();
        when(service.embed(eq(profile),anyList(),any())).thenAnswer(call->{
            cancelled.set(true);when(lifecycle.isStopping()).thenReturn(true);
            throw new IllegalStateException("synthetic invalid response");
        });
        assertThatThrownBy(()->semantic.embed(json.writeValueAsString(profile),List.of("q"),
                ExecutionControl.withTimeout(Duration.ofMinutes(1),cancelled::get)))
                .isInstanceOf(ExecutionCancelledException.class);
    }
    @Test void shutdownWithoutCancellationRemainsRecoverable() {
        when(lifecycle.isStopping()).thenReturn(true);
        assertThatThrownBy(()->semantic.embed("not-json",List.of("q"),ExecutionControl.none()))
                .isInstanceOf(ExecutionSuspendedException.class);
        verifyNoInteractions(service);
    }
    @Test void childOnlyDeadlineIsNotMisreportedAsRunDeadline() {
        var parent=ExecutionControl.withTimeout(Duration.ofDays(1),()->false);
        var child=ExecutionControl.withTimeout(Duration.ofNanos(1),()->false);
        // Classification unit only: not a real 45s network timeout.
        assertThatThrownBy(()->ReflectionTestUtils.invokeMethod(semantic,"check",parent,child))
                .isInstanceOf(LlmApiException.class)
                .satisfies(e->assertThat(((LlmApiException)e).type()).isEqualTo(LlmApiException.Type.TIMEOUT));
        assertThat(parent.isExpired()).isFalse();
    }
}
