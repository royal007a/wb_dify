package com.hify.workflow.application;

import okhttp3.Dns;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;

/** Conservative special-use policy, not a general-purpose IANA reachability classifier. */
final class WorkflowAddressPolicy {
    private WorkflowAddressPolicy() {}

    static boolean literal(String host) { return host.contains(":") || host.matches("[0-9.]+"); }

    static void checkLiteral(String host) throws UnknownHostException {
        if (!literal(host)) return;
        // Called only with OkHttp's canonical numeric host: this cannot perform DNS.
        InetAddress address = InetAddress.getByName(host);
        // Java can normalize mapped IPv6 into Inet4Address. Do not lose its provenance.
        if (host.contains(":") && address.getAddress().length != 16) throw denied();
        requireAllowed(address, true);
    }

    static Dns pinnedDns(String expectedHost, Dns resolver) {
        return new Dns() {
            private List<InetAddress> pinned;
            @Override public synchronized List<InetAddress> lookup(String host) throws UnknownHostException {
                if (!expectedHost.equals(host)) throw denied();
                if (pinned == null) {
                    List<InetAddress> resolved = List.copyOf(resolver.lookup(host));
                    if (resolved.isEmpty()) throw denied();
                    // Reject the complete answer, even if its first address is safe.
                    for (InetAddress address : resolved) requireAllowed(address, literal(host));
                    pinned = resolved;
                }
                return pinned;
            }
        };
    }

    static void requireAllowed(InetAddress address, boolean exactLiteralGrant) throws UnknownHostException {
        byte[] bytes = address.getAddress();
        if (bytes.length == 4) {
            int a = bytes[0] & 255, b = bytes[1] & 255, c = bytes[2] & 255;
            boolean privateOrLoopback = a == 10 || a == 127 || (a == 172 && b >= 16 && b <= 31)
                    || (a == 192 && b == 168);
            if (privateOrLoopback) { if (!exactLiteralGrant) throw denied(); return; }
            if (a == 0 || a >= 224 || (a == 100 && b >= 64 && b <= 127)
                    || (a == 169 && b == 254) || (a == 192 && b == 0 && (c == 0 || c == 2))
                    || (a == 192 && b == 88 && c == 99) || (a == 198 && (b == 18 || b == 19))
                    || (a == 198 && b == 51 && c == 100) || (a == 203 && b == 0 && c == 113)) throw denied();
            return;
        }
        if (bytes.length != 16) throw denied();
        if (address.isLoopbackAddress() && exactLiteralGrant) return;
        // Only native global unicast; excludes ULA, link-local, mapped/compatible,
        // NAT64, multicast and unspecified. Also reject protocol/tunnel/documentation ranges.
        int a = bytes[0] & 255, b = bytes[1] & 255, c = bytes[2] & 255, d = bytes[3] & 255;
        if ((a & 0xe0) != 0x20 || (a == 0x20 && b == 1 && c <= 1)
                || (a == 0x20 && b == 1 && c == 0x0d && d == 0xb8)
                || (a == 0x20 && b == 2) || (a == 0x3f && b == 0xff && (c & 0xf0) == 0)) throw denied();
    }

    private static UnknownHostException denied() { return new UnknownHostException("HTTP destination is not allowed"); }
}
