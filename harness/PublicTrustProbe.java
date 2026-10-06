import java.net.*;
import java.security.*;
import java.util.HexFormat;
import javax.net.ssl.*;

/** Real public-CA handshake with JVM-default trust settings, no HTTP/auth/model call. */
public class PublicTrustProbe {
    public static void main(String[] args) throws Exception {
        String host = "ark.cn-beijing.volces.com";
        try (Socket raw = new Socket()) {
            raw.connect(new InetSocketAddress(host, 443), 5000);
            try (SSLSocket socket = (SSLSocket) ((SSLSocketFactory) SSLSocketFactory.getDefault())
                    .createSocket(raw, host, 443, true)) {
                socket.setSoTimeout(5000);
                SSLParameters parameters = socket.getSSLParameters();
                parameters.setEndpointIdentificationAlgorithm("HTTPS");
                socket.setSSLParameters(parameters);
                socket.startHandshake();
                String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                        .digest(socket.getSession().getPeerCertificates()[0].getEncoded()));
                System.out.println("{\"host\":\""+host+"\",\"handshakeAccepted\":true,\"endpointIdentification\":\"HTTPS\",\"jvmDefaultSslContext\":true,\"httpRequestSent\":false,\"peerCertificateSha256\":\""+digest+"\"}");
            }
        }
    }
}
