package com.sparrowwallet.sparrow.net.cormorant.bitcoind;

import com.sparrowwallet.sparrow.SparrowWallet;
import com.sparrowwallet.sparrow.io.Config;
import com.sparrowwallet.sparrow.io.Server;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.net.ssl.HttpsURLConnection;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.net.*;
import java.nio.file.Path;
import java.security.cert.Certificate;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A node closing on a client outside rpcallowip can reset the connection before the connect completes, which surfaces as
 * "Connection reset by peer (connect failed)" from getOutputStream rather than from reading the response. The socket race behind
 * it cannot be forced, so the connection here throws what a CI runner reported at that point.
 */
public class BitcoindTransportResetOnConnectTest {
    private static final String RESET_ON_CONNECT = "Connection reset by peer (connect failed)";
    private static final String ONION = "http://2gzyxa5ihm7nsggfxnu52rck2vv4rvmdlkiu3zzui5du4xyclen53wid.onion:8332";

    @TempDir
    private static Path tempHome;

    @BeforeAll
    public static void setUp() {
        System.setProperty(SparrowWallet.APP_HOME_PROPERTY, tempHome.toString());
    }

    @AfterAll
    public static void tearDown() {
        System.clearProperty(SparrowWallet.APP_HOME_PROPERTY);
    }

    @Test
    public void resetWhileConnectingPointsToRpcAllowIp() throws Exception {
        IOException thrown = assertThrows(IOException.class, () -> transportFailingWith(new SocketException(RESET_ON_CONNECT), "http://127.0.0.1:8332", false).pass(request()));
        assertTrue(thrown.getMessage().contains("rpcallowip"), "Expected the rpcallowip hint, got: " + thrown);
        assertInstanceOf(SocketException.class, thrown.getCause());
    }

    @Test
    public void resetDuringTlsHandshakeKeepsItsOwnError() throws Exception {
        SocketException reset = new SocketException(RESET_ON_CONNECT);
        IOException thrown = assertThrows(IOException.class, () -> transportFailingWith(reset, "https://127.0.0.1:8332", true).pass(request()));
        assertSame(reset, thrown);
    }

    @Test
    public void resetThroughProxyKeepsItsOwnError() throws Exception {
        Config config = Config.get();
        boolean useProxy = config.isUseProxy();
        String proxyServer = config.getProxyServer();
        try {
            config.setProxyServer("127.0.0.1:9050");
            config.setUseProxy(true);
            SocketException reset = new SocketException(RESET_ON_CONNECT);
            IOException thrown = assertThrows(IOException.class, () -> transportFailingWith(reset, ONION, false).pass(request()));
            assertSame(reset, thrown);
        } finally {
            config.setUseProxy(useProxy);
            config.setProxyServer(proxyServer);
        }
    }

    @Test
    public void refusedConnectionKeepsItsOwnError() throws Exception {
        ConnectException refused = new ConnectException("Connection refused");
        IOException thrown = assertThrows(IOException.class, () -> transportFailingWith(refused, "http://127.0.0.1:8332", false).pass(request()));
        assertSame(refused, thrown);
    }

    @Test
    public void otherSocketErrorsKeepTheirOwnError() throws Exception {
        for(SocketException e : new SocketException[] {new NoRouteToHostException("No route to host"), new SocketException("Network is unreachable"), new SocketException()}) {
            IOException thrown = assertThrows(IOException.class, () -> transportFailingWith(e, "http://127.0.0.1:8332", false).pass(request()));
            assertSame(e, thrown);
        }
    }

    private static String request() {
        return "{\"id\":1,\"method\":\"getblockchaininfo\",\"params\":[]}";
    }

    private static BitcoindTransport transportFailingWith(SocketException e, String serverUrl, boolean https) throws Exception {
        BitcoindTransport transport = new BitcoindTransport(new Server(serverUrl), "test", "user:pass");
        URLStreamHandler handler = new URLStreamHandler() {
            @Override
            protected URLConnection openConnection(URL url) {
                return https ? new FailingHttpsConnection(url, e) : new FailingHttpConnection(url, e);
            }

            @Override
            protected URLConnection openConnection(URL url, Proxy proxy) {
                return openConnection(url);
            }
        };
        Field field = BitcoindTransport.class.getDeclaredField("bitcoindUrl");
        field.setAccessible(true);
        @SuppressWarnings("deprecation")
        URL url = new URL(null, serverUrl + "/wallet/test", handler);
        field.set(transport, url);
        return transport;
    }

    private static class FailingHttpConnection extends HttpURLConnection {
        private final SocketException e;

        FailingHttpConnection(URL url, SocketException e) {
            super(url);
            this.e = e;
        }

        @Override
        public OutputStream getOutputStream() throws IOException {
            throw e;
        }

        @Override
        public void connect() throws IOException {
            throw e;
        }

        @Override
        public void disconnect() {
        }

        @Override
        public boolean usingProxy() {
            return false;
        }
    }

    private static class FailingHttpsConnection extends HttpsURLConnection {
        private final SocketException e;

        FailingHttpsConnection(URL url, SocketException e) {
            super(url);
            this.e = e;
        }

        @Override
        public OutputStream getOutputStream() throws IOException {
            throw e;
        }

        @Override
        public void connect() throws IOException {
            throw e;
        }

        @Override
        public void disconnect() {
        }

        @Override
        public boolean usingProxy() {
            return false;
        }

        @Override
        public String getCipherSuite() {
            return null;
        }

        @Override
        public Certificate[] getLocalCertificates() {
            return null;
        }

        @Override
        public Certificate[] getServerCertificates() {
            return null;
        }
    }
}
