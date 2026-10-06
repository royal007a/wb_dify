package com.hify.common;

import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.assertj.core.api.Assertions.*;

class ExecutionControlTest {
    @Test void childCannotExtendParentAndCanTightenItsOwnDeadline() {
        var parent=ExecutionControl.withTimeout(Duration.ofDays(1),()->false);
        var before=parent.remaining(Duration.ofDays(3));
        var longer=parent.boundedBy(Duration.ofDays(2));
        assertThat(longer.remaining(Duration.ofDays(3))).isLessThanOrEqualTo(before);
        var shorter=parent.boundedBy(Duration.ofHours(1));
        assertThat(shorter.remaining(Duration.ofDays(3))).isLessThanOrEqualTo(Duration.ofHours(1));
        assertThat(parent.remaining(Duration.ofDays(3))).isGreaterThan(Duration.ofHours(1));
    }
    @Test void alreadyExpiredParentDoesNotGetAFreshChildBudget() {
        var parent=ExecutionControl.withTimeout(Duration.ofNanos(1),()->false);
        assertThatThrownBy(()->parent.boundedBy(Duration.ofDays(1)).checkActive())
                .isInstanceOf(ExecutionTimedOutException.class);
    }
    @Test void cancellationWinsOverBothDeadlineAndShutdown() {
        var cancelled=new AtomicBoolean();
        var stopping=new AtomicBoolean();
        var child=ExecutionControl.withTimeout(Duration.ofNanos(1),cancelled::get)
                .withShutdown(stopping::get).boundedBy(Duration.ofDays(1));
        stopping.set(true);
        assertThatThrownBy(child::checkActive).isInstanceOf(ExecutionSuspendedException.class);
        cancelled.set(true);
        assertThatThrownBy(child::checkActive).isInstanceOf(ExecutionCancelledException.class);
    }
    @Test void addedCancellationPreservesOriginalSignalAndShutdown() {
        var original=new AtomicBoolean();var added=new AtomicBoolean();var stopping=new AtomicBoolean();
        var child=ExecutionControl.withTimeout(Duration.ofDays(1),original::get)
                .withShutdown(stopping::get).withCancellation(added::get);
        added.set(true);
        assertThatThrownBy(child::checkActive).isInstanceOf(ExecutionCancelledException.class);
        added.set(false);original.set(true);
        assertThatThrownBy(child::checkActive).isInstanceOf(ExecutionCancelledException.class);
        original.set(false);stopping.set(true);
        assertThatThrownBy(child::checkActive).isInstanceOf(ExecutionSuspendedException.class);
    }
    @Test void invalidChildDurationIsRejectedInsteadOfDisablingTimeouts() {
        assertThatThrownBy(()->ExecutionControl.none().boundedBy(Duration.ZERO)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->ExecutionControl.none().boundedBy(Duration.ofSeconds(-1))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->ExecutionControl.none().boundedBy(null)).isInstanceOf(IllegalArgumentException.class);
    }
}
