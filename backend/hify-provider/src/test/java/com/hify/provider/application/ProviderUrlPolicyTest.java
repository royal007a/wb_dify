package com.hify.provider.application;

import com.hify.common.BizException;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProviderUrlPolicyTest {
    @Test
    void rejectsEveryPrivateOrLocalDnsAnswer() throws Exception {
        ProviderUrlPolicy policy = new ProviderUrlPolicy(false, "",
                host -> new InetAddress[]{
                        InetAddress.getByAddress(host, new byte[]{93, (byte) 184, (byte) 216, 34}),
                        InetAddress.getByAddress(host, new byte[]{127, 0, 0, 1})
                });

        assertThatThrownBy(() -> policy.validate("https://api.example.test/v1"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("内网、本机或保留地址");
    }

    @Test
    void revalidatesDnsAtRuntimeAndRejectsRebinding() throws Exception {
        AtomicInteger lookups = new AtomicInteger();
        ProviderUrlPolicy policy = new ProviderUrlPolicy(false, "", host ->
                lookups.getAndIncrement() == 0
                        ? new InetAddress[]{InetAddress.getByAddress(host,
                                new byte[]{93, (byte) 184, (byte) 216, 34})}
                        : new InetAddress[]{InetAddress.getByAddress(host, new byte[]{10, 0, 0, 8})});

        assertThat(policy.validate("https://gateway.example.test/v1/"))
                .isEqualTo("https://gateway.example.test/v1");
        assertThatThrownBy(() -> policy.validate("https://gateway.example.test/v1"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("内网、本机或保留地址");
        assertThat(lookups).hasValue(2);
    }

    @Test
    void exactAndWildcardAllowlistKeepPrivateEnterpriseGatewaysCompatible() {
        ProviderUrlPolicy policy = new ProviderUrlPolicy(false,
                "gateway.internal,*.corp.example", host ->
                new InetAddress[]{InetAddress.getByAddress(host, new byte[]{10, 0, 0, 8})});

        assertThat(policy.validate("http://gateway.internal:8080/v1"))
                .isEqualTo("http://gateway.internal:8080/v1");
        assertThat(policy.validate("https://llm.corp.example/v1"))
                .isEqualTo("https://llm.corp.example/v1");
        assertThatThrownBy(() -> policy.validate("https://corp.example.evil.test/v1"))
                .isInstanceOf(BizException.class);
    }
}
