package com.sparrowwallet.sparrow.io;

import com.sparrowwallet.sparrow.net.ServerType;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;

/**
 * A config written before public servers were withdrawn must not connect to one.
 *
 * Nothing sanitized the stored type at load. Config.getServer reads it and returns the stored public server,
 * and the connection is made from AppServices.start, which runs before any settings screen could correct it.
 * So an upgrading user who was on a public server would have silently connected to one that has not adopted
 * the fork, which is the whole wallet reading the wrong history rather than one stale number.
 */
public class StoredServerTypeTest {
    private Config configWith(ServerType stored, Server core, Server electrum) throws Exception {
        Constructor<Config> constructor = Config.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        Config config = constructor.newInstance();
        set(config, "serverType", stored);
        set(config, "coreServer", core);
        set(config, "electrumServer", electrum);
        return config;
    }

    private void set(Config config, String name, Object value) throws Exception {
        Field field = Config.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(config, value);
    }

    private ServerType migrated(Config config) throws Exception {
        java.lang.reflect.Method method = Config.class.getDeclaredMethod("migrateServerType");
        method.setAccessible(true);
        method.invoke(config);
        return config.getServerType();
    }

    @Test
    public void testAStoredPublicServerTypeIsNotUsed() throws Exception {
        Server core = new Server("http://127.0.0.1:8332");
        Assertions.assertEquals(ServerType.BITCOIN_CORE,
                migrated(configWith(ServerType.PUBLIC_ELECTRUM_SERVER, core, null)),
                "a node is configured, so use it rather than a public server that has not adopted the fork");

        Server electrum = new Server("ssl://127.0.0.1:50002");
        Assertions.assertEquals(ServerType.ELECTRUM_SERVER,
                migrated(configWith(ServerType.PUBLIC_ELECTRUM_SERVER, null, electrum)),
                "a private server is configured, so use that");

        Assertions.assertNull(migrated(configWith(ServerType.PUBLIC_ELECTRUM_SERVER, null, null)),
                "nothing else is configured, so report no server and let the user choose one");
    }

    @Test
    public void testTheOtherStoredTypesAreLeftAlone() throws Exception {
        Server core = new Server("http://127.0.0.1:8332");
        Server electrum = new Server("ssl://127.0.0.1:50002");
        Assertions.assertEquals(ServerType.BITCOIN_CORE,
                migrated(configWith(ServerType.BITCOIN_CORE, core, electrum)));
        Assertions.assertEquals(ServerType.ELECTRUM_SERVER,
                migrated(configWith(ServerType.ELECTRUM_SERVER, core, electrum)));
        Assertions.assertNull(migrated(configWith(null, core, electrum)));
    }
}
