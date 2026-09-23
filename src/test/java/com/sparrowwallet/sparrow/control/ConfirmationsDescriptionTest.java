package com.sparrowwallet.sparrow.control;

import com.sparrowwallet.drongo.Network;
import com.sparrowwallet.sparrow.MaturityEstimate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * What the confirmations tooltip says, asked without a display.
 *
 * The case worth testing cannot be produced by hand in the interface either way: it needs a wallet holding mined
 * coins on a chain past a particular height. Outside the cell it is a function of three numbers.
 */
public class ConfirmationsDescriptionTest {
    @AfterEach
    public void tearDown() {
        Network.set(null);
    }

    @Test
    public void an_ordinary_output_says_what_it_always_said() {
        Network.set(Network.MAINNET);
        Assertions.assertEquals("Unconfirmed in mempool", ConfirmationsDescription.get(0, false, 0, 1_000_000));
        Assertions.assertEquals("1 confirmation", ConfirmationsDescription.get(1, false, 0, 1_000_000));
        Assertions.assertEquals("3 confirmations", ConfirmationsDescription.get(3, false, 0, 1_000_000));
        Assertions.assertEquals("100+ confirmations", ConfirmationsDescription.get(150, false, 0, 1_000_000));
    }

    /**
     * The count alone cannot answer this, which is the whole reason the height is asked for.
     *
     * It is frozen at the hundred that fully confirms an ordinary output, so a coinbase reads the same a hundred
     * blocks in as six thousand. The block it comes free at is a fact the reader can check; the duration beside it
     * is the part they wanted, and is hedged because it rests on how fast blocks arrive.
     */
    @Test
    public void an_immature_coinbase_says_when_it_comes_free() {
        Network.set(Network.MAINNET);
        //Mined at 1000, tip at 1099, so a hundred deep and far short of the 6480 the network asks for
        Assertions.assertEquals("100+ confirmations, immature coinbase, spendable from block 7480, in about 44 days",
                ConfirmationsDescription.get(100, true, 1000, 1099));
    }

    /** Once it can be spent there is nothing left to say about it. */
    @Test
    public void a_mature_coinbase_is_not_called_immature() {
        Network.set(Network.MAINNET);
        //Mined at 1000, spendable from 7480, so a tip of 7479 is the first that can build the spend
        Assertions.assertEquals("100+ confirmations", ConfirmationsDescription.get(6480, true, 1000, 7479));
        Assertions.assertEquals("100+ confirmations, immature coinbase, spendable from block 7480, in less than a day",
                ConfirmationsDescription.get(6479, true, 1000, 7478));
    }

    /** Nothing to measure against, so it falls back to the wording that needs only a count. */
    @Test
    public void an_unknown_tip_says_only_what_it_can() {
        Network.set(Network.MAINNET);
        Assertions.assertEquals("100+ confirmations, immature coinbase", ConfirmationsDescription.get(100, true, 1000, null));
        Assertions.assertEquals("5 confirmations, immature coinbase", ConfirmationsDescription.get(5, true, 0, 1_000_000));
    }

    /** A network with no schedule matures a coinbase in sixteen hours, where a block height would say less than a count. */
    @Test
    public void a_network_without_the_schedule_is_untouched() {
        Network.set(Network.REGTEST);
        Assertions.assertEquals(100, Network.get().getCoinbaseMaturity(), "the fixture must be a network with no schedule");
        Assertions.assertEquals("5 confirmations, immature coinbase", ConfirmationsDescription.get(5, true, 1000, 1004));
        Assertions.assertEquals("100+ confirmations", ConfirmationsDescription.get(100, true, 1000, 1099));
    }

    /** The duration is rounded hard and hedged, so nobody reads it as a promise. */
    @Test
    public void the_duration_is_coarse() {
        Assertions.assertEquals("", MaturityEstimate.describe(0));
        Assertions.assertEquals("", MaturityEstimate.describe(-1));
        Assertions.assertEquals("less than a day", MaturityEstimate.describe(1));
        Assertions.assertEquals("less than a day", MaturityEstimate.describe(143));
        Assertions.assertEquals("about 1 day", MaturityEstimate.describe(144));
        Assertions.assertEquals("about 45 days", MaturityEstimate.describe(6479));
    }
}
