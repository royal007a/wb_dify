import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.cert.X509Certificate;
import java.util.*;
import javax.net.ssl.*;

/** No credentials, response bodies, private keys or permissive trust manager. */
public class TrustProbe {
    static final String IP = "118.196.123.132";
    static final String PIN = "e1bb50bd7d35af61eb0bd9f921f8fab8ad61c7b521c67034e875b22d0e491ab5";
    static KeyStore store(String path) throws Exception {
        return KeyStore.getInstance(new File(path), "changeit".toCharArray());
    }
    static String digest(java.security.cert.Certificate cert) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(cert.getEncoded()));
    }
    static Set<String> fingerprints(KeyStore store) throws Exception {
        var result = new TreeSet<String>();
        for (var names = store.aliases(); names.hasMoreElements();) {
            String alias = names.nextElement();
            if (!store.isCertificateEntry(alias)) throw new IllegalStateException("Non-certificate entry");
            result.add(digest(store.getCertificate(alias)));
        }
        return result;
    }
    public static void main(String[] args) throws Exception {
        if (args[0].equals("audit")) {
            KeyStore original = store(args[1]), custom = store(args[2]);
            Set<String> before = fingerprints(original), after = fingerprints(custom);
            if (!after.containsAll(before)) throw new IllegalStateException("Public CA removed");
            Set<String> added = new TreeSet<>(after); added.removeAll(before);
            if (!added.equals(Set.of(PIN))) throw new IllegalStateException("Unexpected added certificate");
            if (!custom.getType().equalsIgnoreCase("JKS")) throw new IllegalStateException("Expected JKS");
            System.out.printf("{\"publicCaRetained\":true,\"originalCertificates\":%d,\"newCertificates\":%d,\"addedSha256\":\"%s\",\"storeType\":\"JKS\"}%n", before.size(), after.size(), PIN);
            return;
        }
        String mode = args[0];
        SSLSocketFactory factory;
        if (mode.equals("default")) factory = (SSLSocketFactory) SSLSocketFactory.getDefault();
        else {
            var manager = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            manager.init(store(args[1]));
            var ssl = SSLContext.getInstance("TLS"); ssl.init(null, manager.getTrustManagers(), null);
            factory = ssl.getSocketFactory();
        }
        boolean expectSuccess = mode.equals("trusted");
        String peer = mode.equals("wrong-host") ? "invalid.hify.example" : IP;
        try (Socket raw = new Socket()) {
            raw.connect(new InetSocketAddress(IP, 443), 5000); raw.setSoTimeout(5000);
            try (SSLSocket socket = (SSLSocket) factory.createSocket(raw, peer, 443, true)) {
                socket.setSoTimeout(5000);
                SSLParameters parameters = socket.getSSLParameters();
                parameters.setEndpointIdentificationAlgorithm("HTTPS"); socket.setSSLParameters(parameters);
                try { socket.startHandshake(); }
                catch (SSLHandshakeException rejected) {
                    if (expectSuccess) throw rejected;
                    System.out.printf("{\"probe\":\"%s\",\"handshakeRejected\":true,\"endpointIdentification\":\"HTTPS\"}%n", mode);
                    return;
                }
                if (!expectSuccess) throw new IllegalStateException("Expected handshake rejection");
                var leaf = (X509Certificate) socket.getSession().getPeerCertificates()[0];
                if (!digest(leaf).equals(PIN)) throw new IllegalStateException("Live certificate changed");
                socket.getOutputStream().write(("GET /api/v1/mcp HTTP/1.1\r\nHost: "+IP+"\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                socket.getOutputStream().flush();
                String status = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII)).readLine();
                if (status == null || !status.matches("HTTP/1\\.[01] 401(?: .*)?")) throw new IllegalStateException("Expected unauthenticated HTTP 401");
                System.out.println("{\"probe\":\"trusted\",\"handshakeAccepted\":true,\"httpStatus\":401,\"endpointIdentification\":\"HTTPS\",\"liveCertificateMatches\":true}");
            }
        }
    }
}
