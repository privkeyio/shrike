package com.sparrowwallet.sparrow.net.cormorant.bitcoind;

import com.sparrowwallet.sparrow.SparrowWallet;
import com.sparrowwallet.sparrow.io.Server;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.net.*;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A node closing on a client outside rpcallowip can reset the connection before the connect completes, which surfaces as
 * "Connection reset by peer (connect failed)" from getOutputStream rather than from reading the response. The socket race behind
 * it cannot be forced, so the connection here throws what a CI runner reported at that point.
 */
public class BitcoindTransportResetOnConnectTest {
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
        IOException thrown = assertThrows(IOException.class, () -> transportFailingWith(new SocketException("Connection reset by peer (connect failed)")).pass(request()));
        assertTrue(thrown.getMessage().contains("rpcallowip"), "Expected the rpcallowip hint, got: " + thrown);
        assertInstanceOf(SocketException.class, thrown.getCause());
    }

    @Test
    public void refusedConnectionKeepsItsOwnError() throws Exception {
        ConnectException refused = new ConnectException("Connection refused");
        IOException thrown = assertThrows(IOException.class, () -> transportFailingWith(refused).pass(request()));
        assertSame(refused, thrown);
    }

    @Test
    public void otherSocketErrorsKeepTheirOwnError() throws Exception {
        for(SocketException e : new SocketException[] {new NoRouteToHostException("No route to host"), new SocketException("Network is unreachable"), new SocketException()}) {
            IOException thrown = assertThrows(IOException.class, () -> transportFailingWith(e).pass(request()));
            assertSame(e, thrown);
        }
    }

    private static String request() {
        return "{\"id\":1,\"method\":\"getblockchaininfo\",\"params\":[]}";
    }

    private static BitcoindTransport transportFailingWith(SocketException e) throws Exception {
        BitcoindTransport transport = new BitcoindTransport(new Server("http://127.0.0.1:8332"), "test", "user:pass");
        URLStreamHandler handler = new URLStreamHandler() {
            @Override
            protected URLConnection openConnection(URL url) {
                return new HttpURLConnection(url) {
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
                };
            }
        };
        Field field = BitcoindTransport.class.getDeclaredField("bitcoindUrl");
        field.setAccessible(true);
        @SuppressWarnings("deprecation")
        URL url = new URL(null, "http://127.0.0.1:8332/wallet/test", handler);
        field.set(transport, url);
        return transport;
    }
}
