package com.hify.knowledge.application;

import com.hify.common.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.test.util.ReflectionTestUtils;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class KnowledgeRetrievalControlTest {
    @Test void alreadyCancelledOrExpiredHasNoDatabaseOrEmbeddingCalls() {
        var jdbc=mock(JdbcTemplate.class);
        var knowledge=mock(KnowledgeApplicationService.class);
        var semantic=mock(SemanticEmbeddings.class);
        var retrieval=new KnowledgeRetrievalService(jdbc,knowledge,.5);
        ReflectionTestUtils.setField(retrieval,"semantic",semantic);
        assertThatThrownBy(()->retrieval.searchRevision("version","q",3,
                ExecutionControl.withTimeout(Duration.ofMinutes(1),()->true)))
                .isInstanceOf(ExecutionCancelledException.class);
        assertThatThrownBy(()->retrieval.searchRevision("version","q",3,
                ExecutionControl.withTimeout(Duration.ofNanos(1),()->false)))
                .isInstanceOf(ExecutionTimedOutException.class);
        verifyNoInteractions(jdbc,knowledge,semantic);
    }
    @SuppressWarnings("unchecked")
    @Test void cancellationAfterFirstProfilePreventsSecondEmbeddingAndRankingTransaction() {
        var jdbc=mock(JdbcTemplate.class);
        var cancelled=new AtomicBoolean();
        var observed=new java.util.concurrent.atomic.AtomicReference<ExecutionControl>();
        var semantic=mock(SemanticEmbeddings.class,call->{
            if(call.getMethod().getName().equals("embed")){
                observed.set(call.getArguments().length==3?call.getArgument(2):null);
                cancelled.set(true);return List.of(new float[]{1,0});
            }
            return org.mockito.Answers.RETURNS_DEFAULTS.answer(call);
        });
        var retrieval=new KnowledgeRetrievalService(jdbc,mock(KnowledgeApplicationService.class),.5);
        ReflectionTestUtils.setField(retrieval,"semantic",semantic);
        var control=ExecutionControl.withTimeout(Duration.ofMinutes(1),cancelled::get);
        when(jdbc.queryForObject(anyString(),any(RowMapper.class),eq("version")))
                .thenReturn(new long[]{2,100});
        when(jdbc.queryForList(anyString(),eq(String.class),eq("version"))).thenReturn(List.of("a","b"));
        assertThatThrownBy(()->retrieval.searchRevision("version","q",3,control))
                .isInstanceOf(ExecutionCancelledException.class);
        assertThat(observed.get()).isNotNull().isSameAs(control);
        verify(semantic).embed(eq("a"),eq(List.of("q")),same(control));
        verifyNoMoreInteractions(semantic);
        // The transaction manager is intentionally absent; reaching ranking would fail differently.
    }
}
