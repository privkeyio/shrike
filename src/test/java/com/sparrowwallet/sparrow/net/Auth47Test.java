package com.sparrowwallet.sparrow.net;

import com.sparrowwallet.sparrow.AppServices;
import com.sparrowwallet.sparrow.io.Config;
import org.junit.jupiter.api.Test;

import java.io.Closeable;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

public class Auth47Test {
    private static final String CALLBACK_ONION = "2gzyxa5ihm7nsggfxnu52rck2vv4rvmdlkiu3zzui5du4xyclen53wid.onion";

    @Test
    public void acceptsHttpsCallbacksWithResource() throws Exception {
        Auth47 auth47 = new Auth47(new URI("auth47://nonce?c=https://example.com/auth&r=example"));

        assertEquals("https", auth47.getCallback().getProtocol());
        assertEquals("example.com", auth47.getCallback().getHost());
    }

    @Test
    public void acceptsSrbnCallbacks() throws Exception {
        Auth47 auth47 = new Auth47(new URI("auth47://nonce?c=srbn://alice@relay.example.com"));

        assertEquals("https", auth47.getCallback().getProtocol());
        assertEquals("relay.example.com", auth47.getCallback().getHost());
    }

    @Test
    public void rejectsHttpClearnetCallbacks() {
        assertThrows(IllegalArgumentException.class, () ->
                new Auth47(new URI("auth47://nonce?c=http://example.com/auth&r=example")));
    }

    @Test
    public void rejectsNonHttpCallbacksWithResource() {
        assertThrows(IllegalArgumentException.class, () ->
                new Auth47(new URI("auth47://nonce?c=file:///tmp/auth47&r=example")));
    }

    @Test
    public void callbackRedirectIsNotFollowed() throws Exception {
        try(RedirectingCallback callback = new RedirectingCallback()) {
            Config.get().setProxyServer("127.0.0.1:" + callback.getPort());
            Config.get().setUseProxy(true);
            try {
                Auth47 auth47 = new Auth47(new URI("auth47://nonce?c=http://" + CALLBACK_ONION + "/auth&r=example"));
                //The signed response needs a software wallet, but what is sent does not change where it goes
                Method send = Auth47.class.getDeclaredMethod("send", String.class);
                send.setAccessible(true);
                InvocationTargetException thrown = assertTimeoutPreemptively(Duration.ofSeconds(20),
                        () -> assertThrows(InvocationTargetException.class, () -> send.invoke(auth47, "{}")));
                assertInstanceOf(Auth47.Auth47Exception.class, thrown.getCause());
                assertEquals(List.of(CALLBACK_ONION + ":80"), callback.connectTargets);
            } finally {
                Config.get().setUseProxy(false);
                Config.get().setProxyServer(null);
                //Reconfigures the shared client without the proxy, stopping the clients that used it
                AppServices.getHttpClientService();
            }
        }
    }

    //Stands in for a SOCKS4a proxy and the callback behind it: records each CONNECT target, then redirects the request to a clearnet address
    private static class RedirectingCallback implements Closeable {
        private final ServerSocket serverSocket = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        private final List<String> connectTargets = new CopyOnWriteArrayList<>();

        RedirectingCallback() throws IOException {
            Thread acceptor = new Thread(() -> {
                try {
                    while(true) {
                        Socket socket = serverSocket.accept();
                        Thread handler = new Thread(() -> serve(socket));
                        handler.setDaemon(true);
                        handler.start();
                    }
                } catch(IOException e) {
                    //Closed at the end of the test
                }
            });
            acceptor.setDaemon(true);
            acceptor.start();
        }

        int getPort() {
            return serverSocket.getLocalPort();
        }

        private void serve(Socket socket) {
            try(socket) {
                DataInputStream in = new DataInputStream(socket.getInputStream());
                OutputStream out = socket.getOutputStream();

                //SOCKS4a CONNECT: version, command, port, address 0.0.0.x, user id, hostname. Reply: granted.
                in.readUnsignedByte();
                in.readUnsignedByte();
                int port = in.readUnsignedShort();
                byte[] address = in.readNBytes(4);
                readNullTerminated(in);
                String host = address[0] == 0 && address[1] == 0 && address[2] == 0 && address[3] != 0 ? readNullTerminated(in) : InetAddress.getByAddress(address).getHostAddress();
                connectTargets.add(host + ":" + port);
                out.write(new byte[] {0, 0x5A, 0, 0, 0, 0, 0, 0});

                int contentLength = 0;
                for(String line = readLine(in); line != null && !line.isEmpty(); line = readLine(in)) {
                    if(line.toLowerCase().startsWith("content-length:")) {
                        contentLength = Integer.parseInt(line.substring("content-length:".length()).trim());
                    }
                }
                in.readNBytes(contentLength);
                out.write(("HTTP/1.1 307 Temporary Redirect\r\nLocation: http://127.0.0.1:" + getPort() + "/auth\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
                        .getBytes(StandardCharsets.US_ASCII));
                out.flush();
            } catch(IOException e) {
                //Closed by the client
            }
        }

        private static String readNullTerminated(InputStream in) throws IOException {
            StringBuilder value = new StringBuilder();
            for(int b = in.read(); b > 0; b = in.read()) {
                value.append((char)b);
            }
            return value.toString();
        }

        private static String readLine(InputStream in) throws IOException {
            StringBuilder line = new StringBuilder();
            for(int b = in.read(); b >= 0; b = in.read()) {
                if(b == '\n') {
                    return line.toString().stripTrailing();
                }
                line.append((char)b);
            }
            return line.isEmpty() ? null : line.toString();
        }

        @Override
        public void close() throws IOException {
            serverSocket.close();
        }
    }
}
