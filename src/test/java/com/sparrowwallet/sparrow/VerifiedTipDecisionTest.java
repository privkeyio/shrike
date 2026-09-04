package com.sparrowwallet.sparrow;

import com.sparrowwallet.drongo.Network;
import com.sparrowwallet.drongo.Utils;
import com.sparrowwallet.drongo.protocol.BlockHeader;
import com.sparrowwallet.sparrow.net.ElectrumServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Which tip the opt-in decision is taken from.
 *
 * A server's announcement is unauthenticated, so a forged v2 header at or past the activation height could
 * bring the opt-in forward and every transaction signed under it would fail to verify. The header store is
 * re-verified from a pinned anchor, so where it has reached the announcement its answer is used instead.
 *
 * The fallback is the half worth pinning hardest: where the store is behind, the announcement still decides.
 * Declining there would sign the legacy way, and a signature carrying no replay protection is the worse of
 * the two failures, so this must never become more strict than the announcement alone.
 */
public class VerifiedTipDecisionTest {
    private static final int ACTIVATION = AppServices.getUnifiedSigHashActivationHeight(Network.TESTNET4);

    private static final String V2_HEADER_HEX = "000000a01f1e1d1c1b1a191817161514131211100f0e0d0c0b0a0908070605040302010000112233445566778899aabbccddeeff00102030405060708090a0b0c0d0e0f0a8913577ffff00200df0ad0b3a000000efcdab89ffeeddccbbaa998877665544332211005802000003005c000000000000000000000000000000000040d10c008967452301efcdab8967452301efcdab8967452301efcdab8967452301efcdab";
    private static final String V1_HEADER_HEX = "010000006fe28c0ab6f1b372c1a6a246ae63f74f931e8365e15a089c68d6190000000000982051fd1e4ba744bbbe680e1fee14677ba1a3c3540bf7b1cdb606e857233e0e61bc6649ffff001d01e36299";

    @BeforeEach
    @AfterEach
    public void resetNodeHardforkHeight() {
        AppServices.clearNodeHardforkHeight();
    }

    private BlockHeader header(String hex) {
        return new BlockHeader(Utils.hexToBytes(hex), 0);
    }

    private ChainTip v2(int height) {
        return new ChainTip(height, header(V2_HEADER_HEX));
    }

    private ChainTip v1(int height) {
        return new ChainTip(height, header(V1_HEADER_HEX));
    }

    @Test
    public void nothing_heard_from_a_chain_stays_nothing() {
        Assertions.assertNull(AppServices.decisionTip(null, null));
        Assertions.assertNull(AppServices.decisionTip(null, v2(ACTIVATION)));
    }

    @Test
    public void a_store_level_with_the_announcement_decides() {
        ChainTip verified = v2(ACTIVATION);
        Assertions.assertSame(verified, AppServices.decisionTip(v2(ACTIVATION), verified));
    }

    @Test
    public void a_store_ahead_of_the_announcement_decides() {
        ChainTip verified = v2(ACTIVATION + 10);
        Assertions.assertSame(verified, AppServices.decisionTip(v2(ACTIVATION), verified));
    }

    @Test
    public void a_store_behind_the_announcement_does_not_decide() {
        ChainTip announced = v2(ACTIVATION);
        Assertions.assertSame(announced, AppServices.decisionTip(announced, v2(ACTIVATION - 1)));
    }

    @Test
    public void no_store_leaves_the_announcement_deciding() {
        ChainTip announced = v2(ACTIVATION);
        Assertions.assertSame(announced, AppServices.decisionTip(announced, null));
    }

    /**
     * The reason this exists: a forged v2 announcement cannot bring the opt-in forward once the store has
     * verified that height for itself.
     */
    @Test
    public void a_forged_announcement_is_overruled_by_the_verified_chain() {
        ChainTip forged = v2(ACTIVATION);
        ChainTip verified = v1(ACTIVATION);

        ChainTip decided = AppServices.decisionTip(forged, verified);
        UnifiedSigHashDecision decision = AppServices.chainDecision(
                Network.TESTNET4, decided.height(), decided.header());

        Assertions.assertFalse(decision.isOptedIn());
        Assertions.assertEquals(UnifiedSigHashDecision.TIP_CONTRADICTS_SCHEDULE, decision);

        //Taken from the announcement alone, as it was before, the same pair opts in
        Assertions.assertTrue(AppServices.chainDecision(
                Network.TESTNET4, forged.height(), forged.header()).isOptedIn());
    }

    /**
     * A store that has caught up must not make this stricter than the announcement alone: where both agree
     * the chain is past activation, the answer is still to opt in.
     */
    @Test
    public void a_verified_chain_past_activation_still_opts_in() {
        ChainTip decided = AppServices.decisionTip(v2(ACTIVATION), v2(ACTIVATION));
        Assertions.assertTrue(AppServices.chainDecision(
                Network.TESTNET4, decided.height(), decided.header()).isOptedIn());
    }

    /**
     * Asking what has been verified must not throw or start a load where no store is open, because it is
     * read on the signing decision's path.
     */
    @Test
    public void asking_an_unloaded_store_answers_rather_than_throwing() {
        Assertions.assertDoesNotThrow(ElectrumServer::getVerifiedTip);
    }
}
