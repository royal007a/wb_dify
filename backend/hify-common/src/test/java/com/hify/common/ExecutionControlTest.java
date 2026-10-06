package com.hify.common;

import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import static org.assertj.core.api.Assertions.*;

class ExecutionControlTest {
    @Test void negativeClockOriginStillHasAFiniteDeadline() {
        var clock=new AtomicLong(-100);
        var control=ExecutionControl.withTimeout(Duration.ofNanos(20),()->false,clock::get);
        assertThat(control.remaining(Duration.ofNanos(100))).isEqualTo(Duration.ofNanos(20));
        clock.addAndGet(19);
        assertThat(control.isExpired()).isFalse();
        assertThat(control.remaining(Duration.ofNanos(100))).isEqualTo(Duration.ofNanos(1));
        clock.incrementAndGet();
        assertThatThrownBy(control::checkActive).isInstanceOf(ExecutionTimedOutException.class);
        assertThat(control.remaining(Duration.ofNanos(100))).isZero();
    }
    @Test void deadlineSurvivesSignedNanosecondWraparound() {
        var clock=new AtomicLong(Long.MAX_VALUE-10);
        var control=ExecutionControl.withTimeout(Duration.ofNanos(20),()->false,clock::get);
        assertThat(control.remaining(Duration.ofNanos(100))).isEqualTo(Duration.ofNanos(20));
        clock.addAndGet(16);
        assertThat(clock.get()).isNegative();
        assertThat(control.isExpired()).isFalse();
        assertThat(control.remaining(Duration.ofNanos(100))).isEqualTo(Duration.ofNanos(4));
        clock.addAndGet(4);
        assertThatThrownBy(control::checkActive).isInstanceOf(ExecutionTimedOutException.class);
    }
    @Test void deadlineAtMaxValueIsNotTheUnlimitedSentinel() {
        var clock=new AtomicLong(Long.MAX_VALUE-20);
        var control=ExecutionControl.withTimeout(Duration.ofNanos(20),()->false,clock::get);
        assertThat(control.remaining(Duration.ofNanos(100))).isEqualTo(Duration.ofNanos(20));
        clock.addAndGet(20);
        assertThatThrownBy(control::checkActive).isInstanceOf(ExecutionTimedOutException.class);
    }
    @Test void childAcrossWraparoundCannotExtendParentAndKeepsItsClock() {
        var clock=new AtomicLong(Long.MAX_VALUE-100);
        var parent=ExecutionControl.withTimeout(Duration.ofNanos(200),()->false,clock::get);
        clock.addAndGet(50);
        var child=parent.boundedBy(Duration.ofNanos(300)).withShutdown(()->false).withCancellation(()->false);
        assertThat(child.remaining(Duration.ofNanos(400))).isEqualTo(Duration.ofNanos(150));
        clock.addAndGet(149);
        assertThat(child.isExpired()).isFalse();
        clock.incrementAndGet();
        assertThatThrownBy(child::checkActive).isInstanceOf(ExecutionTimedOutException.class);
        assertThatThrownBy(parent.boundedBy(Duration.ofNanos(500))::checkActive)
                .isInstanceOf(ExecutionTimedOutException.class);
    }
    @Test void shorterChildWithNegativeOriginDoesNotChangeParentBudget() {
        var clock=new AtomicLong(-100);
        var parent=ExecutionControl.withTimeout(Duration.ofNanos(200),()->false,clock::get);
        clock.addAndGet(10);
        var child=parent.boundedBy(Duration.ofNanos(20));
        assertThat(child.remaining(Duration.ofNanos(400))).isEqualTo(Duration.ofNanos(20));
        clock.addAndGet(20);
        assertThatThrownBy(child::checkActive).isInstanceOf(ExecutionTimedOutException.class);
        assertThat(parent.isExpired()).isFalse();
        assertThat(parent.remaining(Duration.ofNanos(400))).isEqualTo(Duration.ofNanos(170));
    }
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
