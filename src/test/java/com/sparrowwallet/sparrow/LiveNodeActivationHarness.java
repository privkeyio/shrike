package com.sparrowwallet.sparrow;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * The decision driven with the numbers a live node actually reported, rather than round literals.
 *
 * Captured from Bitcoin Knots v29.4.1.knots20260508rc2 on testnet4: getdeploymentinfo returned
 * hardfork {height 149537, active true} at chain height 161442.
 */
public class LiveNodeActivationHarness {
    private static final int WALLET_HEIGHT = 149537;
    private static final int NODE_HEIGHT = 149537;
    private static final int TIP = 161442;

    @Test
    public void testAgreementWithTheLiveNodeOptsInSilently() {
        AppServices.clearNodeHardforkHeight();
        Assertions.assertTrue(AppServices.isUnifiedSigHashActive(WALLET_HEIGHT, NODE_HEIGHT, TIP));
        Assertions.assertNull(AppServices.getLastActivationHeightReport(),
                "A correctly configured connection must not warn");
    }

    @Test
    public void testTheSupersededRc1HeightIsRefused() {
        AppServices.clearNodeHardforkHeight();
        Assertions.assertFalse(AppServices.isUnifiedSigHashActive(WALLET_HEIGHT, 149460, TIP),
                "A node still on the rc1 schedule is a disagreement, not something to follow");
        Assertions.assertEquals("149537/149460", AppServices.getLastActivationHeightReport());
    }

    @Test
    public void testANetworkWithNoShippedHeightDeclinesAndReports() {
        AppServices.clearNodeHardforkHeight();
        Assertions.assertFalse(AppServices.isUnifiedSigHashActive(null, NODE_HEIGHT, TIP));
        Assertions.assertEquals("unknown/149537", AppServices.getLastActivationHeightReport());
    }
}
