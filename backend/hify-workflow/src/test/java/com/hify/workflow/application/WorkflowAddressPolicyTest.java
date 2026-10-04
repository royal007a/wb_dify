package com.hify.workflow.application;

import org.junit.jupiter.api.Test;
import java.net.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;

class WorkflowAddressPolicyTest {
    @Test void reservedIpv4IsDeniedEvenWithLiteralGrant() throws Exception {
        for (String ip : List.of("0.1.2.3", "100.64.0.0", "100.96.0.96", "100.100.100.200", "100.127.255.255",
                "169.254.169.254", "169.254.0.1", "192.0.0.9", "192.0.2.1", "192.88.99.1", "198.18.0.1",
                "198.19.255.255", "198.51.100.1", "203.0.113.1", "224.0.0.1", "240.1.2.3", "255.255.255.255")) {
            InetAddress address = InetAddress.getByName(ip);
            for (boolean literal : List.of(false, true))
                assertThatThrownBy(() -> WorkflowAddressPolicy.requireAllowed(address, literal)).as(ip)
                        .isInstanceOf(UnknownHostException.class).hasMessage("HTTP destination is not allowed");
        }
    }
    @Test void privateAddressesNeedLiteralGrantsButMetadataCannotBeGranted() throws Exception {
        for (String ip : List.of("10.0.0.1", "172.16.0.1", "172.31.255.255", "192.168.0.1", "127.0.0.1", "::1")) {
            InetAddress address = InetAddress.getByName(ip);
            assertThatThrownBy(() -> WorkflowAddressPolicy.requireAllowed(address, false)).isInstanceOf(UnknownHostException.class);
            assertThatCode(() -> WorkflowAddressPolicy.checkLiteral(ip)).doesNotThrowAnyException();
        }
        assertThatThrownBy(() -> WorkflowAddressPolicy.checkLiteral("100.100.100.200")).isInstanceOf(UnknownHostException.class);
    }
    @Test void ipv6TranslationTunnelsLocalAndSpecialUseAreAlwaysDenied() throws Exception {
        for (String ip : List.of("::", "::7f00:1", "64:ff9b::a9fe:a9fe", "64:ff9b:1::1", "fc00::1", "fd00::1",
                "fe80::1", "fec0::1", "ff02::1", "100::1", "2001::1", "2001:100::1", "2001:db8::1",
                "2002:7f00:1::1", "3fff::1", "3fff:fff::1", "5f00::1")) {
            for (boolean literal : List.of(false, true))
                assertThatThrownBy(() -> WorkflowAddressPolicy.requireAllowed(InetAddress.getByName(ip), literal)).as(ip)
                        .isInstanceOf(UnknownHostException.class);
        }
        for (String ip : List.of("::ffff:127.0.0.1", "::ffff:169.254.169.254", "::ffff:8.8.8.8"))
            assertThatThrownBy(() -> WorkflowAddressPolicy.checkLiteral(ip)).isInstanceOf(UnknownHostException.class);
    }
    @Test void nativePublicAddressesAndSpecialRangeBoundariesRemainUsable() throws Exception {
        for (String ip : List.of("8.8.8.8", "1.1.1.1", "100.63.255.255", "100.128.0.0", "172.15.255.255",
                "172.32.0.0", "198.17.255.255", "198.20.0.0", "2606:4700:4700::1111", "2001:4860:4860::8888"))
            for (boolean literal : List.of(false, true))
                assertThatCode(() -> WorkflowAddressPolicy.requireAllowed(InetAddress.getByName(ip), literal)).as(ip).doesNotThrowAnyException();
    }
    @Test void dnsAnswersArePinnedAndNeverResolvedAgainWithinOneCall() throws Exception {
        AtomicInteger lookups = new AtomicInteger();
        var dns = WorkflowAddressPolicy.pinnedDns("api.example.com", host -> lookups.incrementAndGet() == 1
                ? List.of(InetAddress.getByName("8.8.8.8"), InetAddress.getByName("2606:4700:4700::1111"))
                : List.of(InetAddress.getByName("100.100.100.200")));
        var first = dns.lookup("api.example.com");
        assertThat(first).hasSize(2);
        assertThat(dns.lookup("api.example.com")).isSameAs(first);
        assertThat(lookups).hasValue(1);
        assertThatThrownBy(() -> first.add(InetAddress.getLoopbackAddress())).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> dns.lookup("other.example.com")).isInstanceOf(UnknownHostException.class);
        assertThat(lookups).hasValue(1);
    }
    @Test void mixedAAndAaaaAnswersAreRejectedRegardlessOfOrder() throws Exception {
        for (String forbidden : List.of("127.0.0.1", "169.254.169.254", "100.96.0.96", "fd00::1", "64:ff9b::a9fe:a9fe")) {
            var addresses = List.of(InetAddress.getByName("8.8.8.8"), InetAddress.getByName(forbidden));
            for (List<InetAddress> answer : List.of(addresses, List.of(addresses.get(1), addresses.get(0)))) {
                var dns = WorkflowAddressPolicy.pinnedDns("api.example.com", host -> answer);
                assertThatThrownBy(() -> dns.lookup("api.example.com")).isInstanceOf(UnknownHostException.class);
            }
        }
        assertThatThrownBy(() -> WorkflowAddressPolicy.pinnedDns("api.example.com", host -> List.of()).lookup("api.example.com"))
                .isInstanceOf(UnknownHostException.class);
    }
}
