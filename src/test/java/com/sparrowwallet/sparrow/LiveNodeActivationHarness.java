package com.sparrowwallet.sparrow;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * The decision driven with the numbers a live node actually reported, rather than round literals.
 *
 * The numbers are the testnet4 schedule this build ships and a chain height past it, rather than round
 * literals, so the arithmetic is exercised on values a node actually reports.
 */
public class LiveNodeActivationHarness {
    private static final int WALLET_HEIGHT = 150027;
    private static final int NODE_HEIGHT = 150027;
    private static final int TIP = 161442;

    @Test
    public void testAgreementWithTheLiveNodeOptsInSilently() {
        AppServices.clearNodeHardforkHeight();
        Assertions.assertTrue(AppServices.isUnifiedSigHashActive(WALLET_HEIGHT, NODE_HEIGHT, TIP));
        Assertions.assertNull(AppServices.getLastActivationHeightReport(),
                "A correctly configured connection must not warn");
    }

    @Test
    public void testASupersededHeightIsRefused() {
        AppServices.clearNodeHardforkHeight();
        Assertions.assertFalse(AppServices.isUnifiedSigHashActive(WALLET_HEIGHT, 149537, TIP),
                "A node on an older schedule is a disagreement, not something to follow");
        Assertions.assertEquals("150027/149537", AppServices.getLastActivationHeightReport());
    }

    @Test
    public void testANetworkWithNoShippedHeightDeclinesAndReports() {
        AppServices.clearNodeHardforkHeight();
        Assertions.assertFalse(AppServices.isUnifiedSigHashActive(null, NODE_HEIGHT, TIP));
        Assertions.assertEquals("unknown/150027", AppServices.getLastActivationHeightReport());
    }
}
