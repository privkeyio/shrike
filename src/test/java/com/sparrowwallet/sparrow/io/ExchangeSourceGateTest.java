package com.sparrowwallet.sparrow.io;

import com.sparrowwallet.sparrow.net.ExchangeSource;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Every exchange source quotes the SHA256d chain, which is not what this wallet holds, so no fiat
 * value is shown at all. Every display site and the rates service decide that by reading
 * Config.getExchangeSource(), so this pins the one place the decision is made.
 */
public class ExchangeSourceGateTest {
    @Test
    public void testNoExchangeSourceIsUsed() {
        Assertions.assertEquals(ExchangeSource.NONE, Config.get().getExchangeSource());
        Assertions.assertFalse(Config.get().isFetchRates(), "rates would be fetched and displayed");
    }

    @Test
    public void testAStoredPreferenceDoesNotReopenIt() {
        //A config carried over from before this change, or written by a future build that has a
        //source worth using, must not bring the wrong rate back with it.
        ExchangeSource stored = ExchangeSource.COINGECKO;
        Config.get().setExchangeSource(stored);

        Assertions.assertEquals(ExchangeSource.NONE, Config.get().getExchangeSource());
        Assertions.assertFalse(Config.get().isFetchRates());
    }
}
