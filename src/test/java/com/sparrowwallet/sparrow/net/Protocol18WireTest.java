package com.sparrowwallet.sparrow.net;

import com.google.common.net.HostAndPort;
import com.sparrowwallet.drongo.Utils;
import com.sparrowwallet.drongo.protocol.BlockHeader;
import com.sparrowwallet.sparrow.SparrowWallet;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

/**
 * Over a socket, against a server that behaves the way a server on a chain with v2 headers is specified to.
 *
 * The two halves this exercises cannot be reached below the JSON layer, which is where the rest of the header tests fake their answers: whether the
 * version this client asks for is one such a server will serve at all, and whether the list form that asking for it produces can be read.
 */
public class Protocol18WireTest {
    @TempDir
    private static Path tempHome;

    private static final String V1 = "010000006fe28c0ab6f1b372c1a6a246ae63f74f931e8365e15a089c68d6190000000000982051fd1e4ba744bbbe680e1fee14677ba1a3c3540bf7b1cdb606e857233e0e61bc6649ffff001d01e36299";
    private static final String V2 = "000000a01f1e1d1c1b1a191817161514131211100f0e0d0c0b0a0908070605040302010000112233445566778899aabbccddeeff00102030405060708090a0b0c0d0e0f0a8913577ffff00200df0ad0b3a000000efcdab89ffeeddccbbaa998877665544332211005802000003005c000000000000000000000000000000000040d10c008967452301efcdab8967452301efcdab8967452301efcdab8967452301efcdab";

    @BeforeAll
    public static void setUp() {
        System.setProperty(SparrowWallet.APP_HOME_PROPERTY, tempHome.toString());
    }

    @AfterAll
    public static void tearDown() {
        System.clearProperty(SparrowWallet.APP_HOME_PROPERTY);
    }

    /** Refuses below 1.8 and answers blockchain.block.headers with the list form, as protocol 1.6 and later specify. */
    private Thread serve(ServerSocket serverSocket, StringBuilder negotiated) {
        Thread t = new Thread(() -> {
            try(Socket socket = serverSocket.accept();
                BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                PrintWriter out = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true)) {
                String line;
                while((line = in.readLine()) != null) {
                    int id = Integer.parseInt(line.replaceAll(".*\"id\"\\s*:\\s*(\\d+).*", "$1"));
                    if(line.contains("server.version")) {
                        negotiated.append(line);
                        if(!line.contains("\"1.8\"")) {
                            out.println("{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"error\":{\"code\":1,\"message\":"
                                    + "\"unsupported request: this chain uses 164-byte block headers with a BLAKE2b block hash\"}}");
                        } else {
                            out.println("{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"result\":[\"ElectrumStub 1.0\",\"1.8\"]}");
                        }
                    } else if(line.contains("blockchain.block.headers")) {
                        //The list form, one entry per header, mixing the two lengths
                        out.println("{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"result\":{\"count\":2,\"max\":2016,"
                                + "\"hex\":[\"" + V1 + "\",\"" + V2 + "\"]}}");
                    } else {
                        out.println("{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"result\":null}");
                    }
                }
            } catch(Exception e) {
                //the socket closing at the end of a test is expected
            }
        });
        t.setDaemon(true);
        t.start();
        return t;
    }

    /** As the wallet does it: open the socket, then read replies on a thread of their own, which is what pass() waits on. */
    private TcpTransport connected(ServerSocket serverSocket) throws Exception {
        TcpTransport transport = new TcpTransport(HostAndPort.fromParts("127.0.0.1", serverSocket.getLocalPort()));
        transport.connect();
        Thread reader = new Thread(() -> {
            try {
                transport.readInputLoop();
            } catch(Exception e) {
                //the transport closing at the end of a test is expected
            }
        });
        reader.setDaemon(true);
        reader.start();
        return transport;
    }

    @Test
    public void the_version_asked_for_is_one_such_a_server_serves() throws Exception {
        try(ServerSocket serverSocket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            StringBuilder negotiated = new StringBuilder();
            serve(serverSocket, negotiated);
            TcpTransport transport = connected(serverSocket);
            try {
                List<String> version = new SimpleElectrumServerRpc().getServerVersion(transport, SparrowWallet.APP_NAME, ElectrumServer.SUPPORTED_VERSIONS);
                Assertions.assertEquals("1.8", version.get(1), "the server agreed a version below 1.8, which it would refuse to serve headers at");
                Assertions.assertTrue(negotiated.toString().contains(SparrowWallet.APP_NAME),
                        "the client named itself something other than this wallet");
            } finally {
                transport.close();
            }
        }
    }

    @Test
    public void the_list_form_that_asking_for_it_produces_is_read() throws Exception {
        try(ServerSocket serverSocket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            serve(serverSocket, new StringBuilder());
            TcpTransport transport = connected(serverSocket);
            try {
                SimpleElectrumServerRpc rpc = new SimpleElectrumServerRpc();
                rpc.getServerVersion(transport, SparrowWallet.APP_NAME, ElectrumServer.SUPPORTED_VERSIONS);
                BlockHeaders headers = rpc.getBlockHeadersChunk(transport, 0, 2);

                List<BlockHeader> parsed = headers.getHeaders(2);
                Assertions.assertNotNull(parsed, "the list form did not split into headers");
                Assertions.assertEquals(2, parsed.size());
                Assertions.assertFalse(parsed.get(0).isHeaderV2());
                Assertions.assertTrue(parsed.get(1).isHeaderV2(), "the v2 header was not recognised over the wire");
                Assertions.assertEquals(BlockHeader.V2_LENGTH, parsed.get(1).getLength());
                Assertions.assertArrayEquals(Utils.hexToBytes(V2), parsed.get(1).bitcoinSerialize());
            } finally {
                transport.close();
            }
        }
    }
}
