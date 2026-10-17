package com.sparrowwallet.sparrow.io;

import com.sparrowwallet.sparrow.net.ExchangeSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

/**
 * Neoxa is the only source that quotes this chain's coin, and is opt-in. Every other exchange source quotes the
 * SHA256d chain, which is not what this wallet holds, and using one of them overstated every fiat value by a couple
 * of hundred times. Every display site and the rates service decide which source to use by reading
 * Config.getExchangeSource(), so this pins the one place the decision is made.
 */
public class ExchangeSourceGateTest {
    private ExchangeSource storedExchangeSource;

    @BeforeEach
    public void saveConfig() throws ReflectiveOperationException {
        Field field = Config.class.getDeclaredField("exchangeSource");
        field.setAccessible(true);
        storedExchangeSource = (ExchangeSource)field.get(Config.get());
    }

    @AfterEach
    public void restoreConfig() {
        Config.get().setExchangeSource(storedExchangeSource);
    }

    @Test
    public void testNoExchangeSourceByDefault() {
        Config.get().setExchangeSource(null);

        Assertions.assertEquals(ExchangeSource.NONE, Config.get().getExchangeSource());
        Assertions.assertFalse(Config.get().isFetchRates(), "rates would be fetched and displayed");
    }

    @Test
    public void testAStoredSha256dSourceDoesNotReopenIt() {
        //A config carried over from before the split, or from Sparrow, must not bring the wrong rate back with it
        for(ExchangeSource stored : new ExchangeSource[] {ExchangeSource.COINBASE, ExchangeSource.COINGECKO, ExchangeSource.MEMPOOL_SPACE}) {
            Config.get().setExchangeSource(stored);
            Assertions.assertEquals(ExchangeSource.NONE, Config.get().getExchangeSource(), stored + " was used for the rate");
            Assertions.assertFalse(Config.get().isFetchRates());
        }
    }

    @Test
    public void testNeoxaIsUsedWhenChosen() {
        Config.get().setExchangeSource(ExchangeSource.NEOXA);

        Assertions.assertEquals(ExchangeSource.NEOXA, Config.get().getExchangeSource());
        Assertions.assertTrue(Config.get().isFetchRates());
    }
}
