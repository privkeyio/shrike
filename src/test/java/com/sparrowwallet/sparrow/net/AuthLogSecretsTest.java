package com.sparrowwallet.sparrow.net;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.sparrowwallet.drongo.Network;
import com.sparrowwallet.drongo.policy.PolicyType;
import com.sparrowwallet.drongo.protocol.Bech32;
import com.sparrowwallet.drongo.protocol.ScriptType;
import com.sparrowwallet.drongo.wallet.DeterministicSeed;
import com.sparrowwallet.drongo.wallet.Keystore;
import com.sparrowwallet.drongo.wallet.Wallet;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

public class AuthLogSecretsTest {
    private static final String K1 = "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f";

    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
    private Level auth47Level;
    private Level lnurlLevel;

    @BeforeEach
    public void captureLogs() {
        appender.start();
        auth47Level = logger(Auth47.class).getLevel();
        lnurlLevel = logger(LnurlAuth.class).getLevel();
        for(Class<?> type : new Class<?>[] {Auth47.class, LnurlAuth.class}) {
            logger(type).setLevel(Level.DEBUG);
            logger(type).addAppender(appender);
        }
    }

    @AfterEach
    public void releaseLogs() {
        for(Class<?> type : new Class<?>[] {Auth47.class, LnurlAuth.class}) {
            logger(type).detachAppender(appender);
        }
        logger(Auth47.class).setLevel(auth47Level);
        logger(LnurlAuth.class).setLevel(lnurlLevel);
        Network.set(null);
    }

    @Test
    public void theAuth47ResponseIsNotLogged() throws Exception {
        String secret = "signed-response-marker";
        Auth47 auth47 = new Auth47(new URI("auth47://nonce?c=https://127.0.0.1:" + closedPort() + "/auth&r=example"));
        Method send = Auth47.class.getDeclaredMethod("send", String.class);
        send.setAccessible(true);
        InvocationTargetException thrown = assertThrows(InvocationTargetException.class, () -> send.invoke(auth47, "{\"signature\":\"" + secret + "\"}"));

        assertFalse(appender.list.isEmpty(), "the send is still logged");
        assertNoSecret(secret, thrown.getCause());
    }

    @Test
    public void theLnurlAuthSignatureAndLinkingKeyAreNotLogged() throws Exception {
        Network.set(Network.MAINNET);
        Wallet wallet = new Wallet();
        wallet.setPolicyType(PolicyType.SINGLE_HD);
        wallet.setScriptType(ScriptType.P2WPKH);
        DeterministicSeed seed = new DeterministicSeed("absent essay fox snake vast pumpkin height crouch silent bulb excuse razor", "", 0, DeterministicSeed.Type.BIP39);
        wallet.getKeystores().add(Keystore.fromSeed(seed, PolicyType.SINGLE_HD, ScriptType.P2WPKH.getDefaultDerivation()));

        String url = "https://127.0.0.1:" + closedPort() + "/lnurl-auth?tag=login&k1=" + K1;
        byte[] urlBytes = url.getBytes(StandardCharsets.UTF_8);
        LnurlAuth lnurlAuth = new LnurlAuth(new URI("lightning:" + Bech32.encode("lnurl", Bech32.Encoding.BECH32, Bech32.convertBits(urlBytes, 0, urlBytes.length, 8, 5, true))));
        Exception thrown = assertThrows(Exception.class, () -> lnurlAuth.sendResponse(wallet));

        assertFalse(appender.list.isEmpty(), "the send is still logged");
        assertNoSecret("sig=", thrown);
        assertNoSecret("key=", thrown);
    }

    private void assertNoSecret(String secret, Throwable thrown) {
        for(ILoggingEvent event : appender.list) {
            assertFalse(event.getFormattedMessage().contains(secret), event.getFormattedMessage());
        }

        StringWriter trace = new StringWriter();
        thrown.printStackTrace(new PrintWriter(trace));
        assertFalse(trace.toString().contains(secret), "the failure the caller logs carries it: " + trace);
    }

    private static Logger logger(Class<?> type) {
        return (Logger)LoggerFactory.getLogger(type);
    }

    private static int closedPort() throws Exception {
        try(ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            return socket.getLocalPort();
        }
    }
}
