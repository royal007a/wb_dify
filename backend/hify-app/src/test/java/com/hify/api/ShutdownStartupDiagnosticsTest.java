package com.hify.api;

import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import static org.assertj.core.api.Assertions.*;

class ShutdownStartupDiagnosticsTest {
    @Test void disabledDoesNotInstallRecorderOrWriteOutput() {
        var builder=new SpringApplicationBuilder();
        var original=builder.application().getApplicationStartup();
        var output=new ArrayList<String>(); var calls=new AtomicInteger();
        Object expected=new Object();
        var result=ShutdownStartupDiagnostics.capture(false,builder,()->{calls.incrementAndGet();return expected;},output::add);
        assertThat(result).isSameAs(expected);assertThat(calls).hasValue(1);
        assertThat(builder.application().getApplicationStartup()).isSameAs(original);assertThat(output).isEmpty();
    }
    @Test void allowedStepIsRecordedButCredentialsAndUnlistedNamesAreNot() {
        var builder=new SpringApplicationBuilder();var output=new ArrayList<String>();
        ShutdownStartupDiagnostics.capture(true,builder,()->{
            builder.application().getApplicationStartup().start("spring.beans.instantiate")
                .tag("beanName","entityManagerFactory").tag("password","NEVER-EMIT-CREDENTIAL").end();
            builder.application().getApplicationStartup().start("UNLISTED-STEP\nINJECT")
                .tag("beanName","UNLISTED-BEAN").end();
            return 42;
        },output::add);
        String text=String.join("\n",output);
        assertThat(output).hasSize(3);
        assertThat(text).contains("completedEvents=2","stage=spring.beans.instantiate bean=entityManagerFactory","stage=other bean=other")
            .doesNotContain("NEVER-EMIT-CREDENTIAL","UNLISTED","INJECT","password");
    }
    @Test void completedEventOutputIsBounded() {
        var builder=new SpringApplicationBuilder();var output=new ArrayList<String>();
        ShutdownStartupDiagnostics.capture(true,builder,()->{
            for(int i=0;i<30;i++)builder.application().getApplicationStartup().start("spring.beans.instantiate").end();
            return null;
        },output::add);
        assertThat(output).hasSize(13);assertThat(output.get(0)).contains("completedEvents=30");
    }
    @Test void failedSinkCannotReplaceOriginalFailure() {
        var original=new IllegalStateException("original");var calls=new AtomicInteger();
        assertThatThrownBy(()->ShutdownStartupDiagnostics.capture(true,new SpringApplicationBuilder(),
            ()->{calls.incrementAndGet();throw original;},line->{throw new IllegalArgumentException("sink");}))
            .isSameAs(original);
        assertThat(calls).hasValue(1);
    }
    @Test void failedSinkCannotReplaceSuccessfulResult() {
        Object expected=new Object();
        assertThat(ShutdownStartupDiagnostics.capture(true,new SpringApplicationBuilder(),()->expected,
            line->{throw new IllegalArgumentException("sink");})).isSameAs(expected);
    }
    @Test void reportingDoesNotClearCancellationInterrupt() {
        var output=new ArrayList<String>();
        try {
            Thread.currentThread().interrupt();
            assertThat(ShutdownStartupDiagnostics.capture(true,new SpringApplicationBuilder(),()->42,output::add)).isEqualTo(42);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            assertThat(output.get(0)).contains("interrupted=true");
        } finally {Thread.interrupted();}
    }
}
