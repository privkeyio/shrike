package com.sparrowwallet.sparrow;

import com.sparrowwallet.drongo.Network;
import com.sparrowwallet.drongo.Utils;
import com.sparrowwallet.drongo.policy.PolicyType;
import com.sparrowwallet.drongo.protocol.BlockHeader;
import com.sparrowwallet.drongo.protocol.ScriptType;
import com.sparrowwallet.drongo.wallet.Keystore;
import com.sparrowwallet.drongo.wallet.KeystoreSource;
import com.sparrowwallet.drongo.wallet.Wallet;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;

/**
 * The reason a transaction did not opt in, which the send screen shows and the transaction view does not.
 *
 * The decision itself is UnifiedSigHashPolicyTest's subject; what is pinned here is that each way of
 * declining is told apart from the others, and that the boolean the wallet has always signed by is the
 * same value as the decision reporting OPTED_IN. Both directions matter: a reason that disagrees with the
 * decision it explains would be worse than none.
 *
 * No JavaFX control appears here because none can be built without a toolkit — Label, ComboBox and
 * Tooltip all throw on construction in a headless JVM, verified by running it. The two view surfaces are
 * therefore untested: HeadersController.updateOptInStatus and SendController.updateOptInStatus are not
 * reachable, and are not restated by a test here that would pass whatever they did.
 */
public class UnifiedSigHashDecisionTest {
    //Taken from the shipped schedule rather than restated, so a height move breaks the one test that
    //pins the constant (UnifiedSigHashPolicyTest) rather than every test that happens to use it.
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

    private Wallet walletWith(KeystoreSource... sources) {
        Wallet wallet = new Wallet();
        wallet.setPolicyType(PolicyType.SINGLE_HD);
        wallet.setScriptType(ScriptType.P2WPKH);
        for(KeystoreSource source : sources) {
            Keystore keystore = new Keystore();
            keystore.setSource(source);
            wallet.getKeystores().add(keystore);
        }
        return wallet;
    }

    @Test
    public void testAV1TipIsReportedAsTheChainNotHavingActivated() {
        Assertions.assertEquals(UnifiedSigHashDecision.CHAIN_NOT_ACTIVATED,
                AppServices.chainDecision(Network.TESTNET4, ACTIVATION, header(V1_HEADER_HEX)));
    }

    /**
     * No tip is its own reason, as an unknown height already is. Reporting the chain as not having activated would
     * state something about a chain this wallet has not seen, which is what an offline session always is.
     */
    @Test
    public void testNoTipAtAllIsItsOwnReason() {
        Assertions.assertEquals(UnifiedSigHashDecision.CHAIN_UNSEEN,
                AppServices.chainDecision(Network.TESTNET4, ACTIVATION, null));
    }

    @Test
    public void testAV2TipPastTheHeightOptsIn() {
        AppServices.clearNodeHardforkHeight();
        UnifiedSigHashDecision decision = AppServices.chainDecision(Network.TESTNET4, ACTIVATION, header(V2_HEADER_HEX));
        Assertions.assertTrue(decision.isOptedIn());
        //No node has reported a height here, so this is the form that says the cross check did not run
        Assertions.assertEquals(UnifiedSigHashDecision.OPTED_IN_UNCORROBORATED, decision);
    }

    /**
     * An Electrum server has no getdeploymentinfo to ask, so it reports no activation height and the cross check
     * cannot run. Opting in is still right, since declining because a server cannot answer would forgo the
     * protection on every Electrum connection, but it rests on the shipped height alone and says so.
     */
    @Test
    public void testAnOptInWithNothingToCorroborateItSaysSo() {
        AppServices.clearNodeHardforkHeight();
        Assertions.assertEquals(UnifiedSigHashDecision.OPTED_IN_UNCORROBORATED,
                AppServices.heightDecision(ACTIVATION, null, ACTIVATION));
    }

    @Test
    public void testANodeAgreeingMakesTheOptInCorroborated() {
        Assertions.assertEquals(UnifiedSigHashDecision.OPTED_IN,
                AppServices.heightDecision(ACTIVATION, ACTIVATION, ACTIVATION));
    }

    /**
     * Both forms produce the same signature, so nothing deciding how to sign may tell them apart. Only what is
     * reported differs, which is why the summary has to match too.
     */
    @Test
    public void testBothOptedInFormsSignTheSameWay() {
        for(UnifiedSigHashDecision decision : List.of(UnifiedSigHashDecision.OPTED_IN, UnifiedSigHashDecision.OPTED_IN_UNCORROBORATED)) {
            Assertions.assertTrue(decision.isOptedIn(), decision.toString());
            Assertions.assertNull(decision.getReason(), decision + " did not decline, so it has no reason");
            Assertions.assertEquals(UnifiedSigHashDecision.summaryFor(true), decision.getSummary(), decision.toString());
        }
    }

    /**
     * Corroboration is decided by the chain and the keystores know nothing about it, so combining the two must
     * not quietly promote an uncorroborated opt-in into a confirmed one.
     */
    @Test
    public void testCorroborationSurvivesTheKeystoreCombine() {
        Assertions.assertEquals(UnifiedSigHashDecision.OPTED_IN_UNCORROBORATED,
                AppServices.combinedDecision(UnifiedSigHashDecision.OPTED_IN_UNCORROBORATED, walletWith(KeystoreSource.SW_SEED)));
        Assertions.assertEquals(UnifiedSigHashDecision.OPTED_IN,
                AppServices.combinedDecision(UnifiedSigHashDecision.OPTED_IN, walletWith(KeystoreSource.SW_SEED)));
        //A keystore that cannot opt in still decides, whatever the chain answered
        Assertions.assertEquals(UnifiedSigHashDecision.EXTERNAL_SIGNER,
                AppServices.combinedDecision(UnifiedSigHashDecision.OPTED_IN_UNCORROBORATED, walletWith(KeystoreSource.HW_USB)));
    }

    /**
     * A caveat qualifies an opt-in, a reason explains a decline, and nothing carries both.
     */
    @Test
    public void testOnlyTheUncorroboratedOptInCarriesACaveat() {
        for(UnifiedSigHashDecision decision : UnifiedSigHashDecision.values()) {
            if(decision == UnifiedSigHashDecision.OPTED_IN_UNCORROBORATED) {
                Assertions.assertNotNull(decision.getCaveat(), decision.toString());
            } else {
                Assertions.assertNull(decision.getCaveat(), decision + " has nothing to qualify");
            }
        }
    }

    @Test
    public void testAnUnknownBlockHeightIsItsOwnReason() {
        Assertions.assertEquals(UnifiedSigHashDecision.CHAIN_HEIGHT_UNKNOWN,
                AppServices.heightDecision(ACTIVATION, null, null));
    }

    @Test
    public void testNoShippedScheduleIsItsOwnReason() {
        Assertions.assertEquals(UnifiedSigHashDecision.BUILD_HAS_NO_SCHEDULE,
                AppServices.heightDecision(null, ACTIVATION, ACTIVATION));
    }

    @Test
    public void testDisagreeingSchedulesAreItsOwnReason() {
        Assertions.assertEquals(UnifiedSigHashDecision.SCHEDULE_MISMATCH,
                AppServices.heightDecision(ACTIVATION, 149460, ACTIVATION));
    }

    @Test
    public void testAgreedScheduleNotYetReachedIsItsOwnReason() {
        Assertions.assertEquals(UnifiedSigHashDecision.BEFORE_ACTIVATION_HEIGHT,
                AppServices.heightDecision(ACTIVATION, ACTIVATION, ACTIVATION - 1));
    }

    @Test
    public void testAWalletWithNoKeystoresIsItsOwnReason() {
        Assertions.assertEquals(UnifiedSigHashDecision.NO_SIGNING_KEYS, AppServices.keystoreDecision(walletWith()));
        Assertions.assertEquals(UnifiedSigHashDecision.NO_SIGNING_KEYS, AppServices.keystoreDecision(null));
    }

    @Test
    public void testASoftwareOnlyWalletOptsIn() {
        Assertions.assertEquals(UnifiedSigHashDecision.OPTED_IN,
                AppServices.keystoreDecision(walletWith(KeystoreSource.SW_SEED, KeystoreSource.SW_SEED)));
    }

    /**
     * Watch only counts alongside the hardware sources, since it produces no signature either.
     */
    @Test
    public void testAnySourceOtherThanASoftwareSeedDeclines() {
        for(KeystoreSource source : List.of(KeystoreSource.HW_USB, KeystoreSource.HW_AIRGAPPED)) {
            Assertions.assertEquals(UnifiedSigHashDecision.EXTERNAL_SIGNER,
                    AppServices.keystoreDecision(walletWith(KeystoreSource.SW_SEED, source)), source.toString());
        }

        //Watch only declines too, but for a reason marking cannot fix
        Assertions.assertEquals(UnifiedSigHashDecision.NO_DEVICE_TO_MARK,
                AppServices.keystoreDecision(walletWith(KeystoreSource.SW_SEED, KeystoreSource.SW_WATCH)));
    }

    /**
     * A quorum is decided by its weakest member, because a PSBT declares one hash type for every signer.
     */
    @Test
    public void testOneExternalKeystoreDecidesForTheWholeWallet() {
        Assertions.assertEquals(UnifiedSigHashDecision.EXTERNAL_SIGNER,
                AppServices.keystoreDecision(walletWith(KeystoreSource.SW_SEED, KeystoreSource.SW_SEED, KeystoreSource.HW_USB)));
    }

    /**
     * The reason is only carried where the wallet declined, so a caller cannot print one for a
     * transaction that opted in.
     */
    @Test
    public void testOnlyADeclineCarriesAReason() {
        //Driven by isOptedIn rather than a named value, so another opted-in form cannot be added later without
        //this holding it to the same rule
        for(UnifiedSigHashDecision decision : UnifiedSigHashDecision.values()) {
            if(decision.isOptedIn()) {
                Assertions.assertNull(decision.getReason(), decision + " did not decline, so it carries no reason");
            } else {
                Assertions.assertNotNull(decision.getReason(), decision + " declined, so it owes a reason");
            }
        }
    }

    /**
     * The wording is about what the signature can and cannot survive, not the byte that carries it. A
     * hash type constant in the summary would name the mechanism where the consequence is what is meant.
     */
    @Test
    public void testTheSummarySpeaksOfConsequenceNotHashType() {
        for(boolean optedIn : List.of(true, false)) {
            String summary = UnifiedSigHashDecision.summaryFor(optedIn);
            Assertions.assertTrue(summary.toLowerCase(Locale.ROOT).contains("replay"), summary);
            Assertions.assertFalse(summary.toUpperCase(Locale.ROOT).contains("SIGHASH"), summary);
        }
        Assertions.assertNotEquals(UnifiedSigHashDecision.summaryFor(true), UnifiedSigHashDecision.summaryFor(false));
        Assertions.assertEquals(UnifiedSigHashDecision.summaryFor(true), UnifiedSigHashDecision.OPTED_IN.getSummary());
        Assertions.assertEquals(UnifiedSigHashDecision.summaryFor(false), UnifiedSigHashDecision.EXTERNAL_SIGNER.getSummary());
    }

    /**
     * The boolean the wallet signs by and the decision the screen shows have to be the same answer.
     */
    @Test
    public void testTheDecisionAgreesWithTheBooleanItReplaced() {
        Integer[][] cases = {{ACTIVATION, null, ACTIVATION}, {ACTIVATION, 149460, ACTIVATION}, {null, ACTIVATION, ACTIVATION},
                {ACTIVATION, ACTIVATION, ACTIVATION - 1}, {ACTIVATION, ACTIVATION, ACTIVATION}, {ACTIVATION, null, null}};
        for(Integer[] c : cases) {
            AppServices.clearNodeHardforkHeight();
            boolean active = AppServices.isUnifiedSigHashActive(c[0], c[1], c[2]);
            AppServices.clearNodeHardforkHeight();
            UnifiedSigHashDecision decision = AppServices.heightDecision(c[0], c[1], c[2]);
            Assertions.assertEquals(active, decision.isOptedIn(), java.util.Arrays.toString(c));
        }
    }

    /**
     * The chain is asked first: a wallet holding a device on a chain that has not activated reports the
     * chain, since the device is no obstacle until the rules are live. Only once the chain has opted in
     * does a keystore get to decline.
     */
    @Test
    public void testTheChainIsAskedBeforeTheKeystores() {
        Wallet hardware = walletWith(KeystoreSource.SW_SEED, KeystoreSource.HW_USB);
        Wallet software = walletWith(KeystoreSource.SW_SEED);

        Assertions.assertEquals(UnifiedSigHashDecision.CHAIN_NOT_ACTIVATED,
                AppServices.combinedDecision(UnifiedSigHashDecision.CHAIN_NOT_ACTIVATED, hardware));
        Assertions.assertEquals(UnifiedSigHashDecision.SCHEDULE_MISMATCH,
                AppServices.combinedDecision(UnifiedSigHashDecision.SCHEDULE_MISMATCH, hardware));
        Assertions.assertEquals(UnifiedSigHashDecision.EXTERNAL_SIGNER,
                AppServices.combinedDecision(UnifiedSigHashDecision.OPTED_IN, hardware));
        Assertions.assertEquals(UnifiedSigHashDecision.OPTED_IN,
                AppServices.combinedDecision(UnifiedSigHashDecision.OPTED_IN, software));
    }

    /**
     * A chain reason must not be able to reach a wallet that would have declined anyway and come back
     * reading as opted in, which is what dropping the keystore call would do.
     */
    @Test
    public void testAnOptedInChainStillDefersToTheKeystores() {
        Assertions.assertFalse(AppServices.combinedDecision(UnifiedSigHashDecision.OPTED_IN,
                walletWith(KeystoreSource.HW_AIRGAPPED)).isOptedIn());
        Assertions.assertFalse(AppServices.combinedDecision(UnifiedSigHashDecision.OPTED_IN, walletWith()).isOptedIn());
    }

    @Test
    public void testCanSignUnifiedAgreesWithTheKeystoreDecision() {
        for(Wallet wallet : List.of(walletWith(KeystoreSource.SW_SEED), walletWith(KeystoreSource.HW_USB),
                walletWith(KeystoreSource.SW_WATCH), walletWith())) {
            Assertions.assertEquals(AppServices.canSignUnified(wallet), AppServices.keystoreDecision(wallet).isOptedIn());
        }
    }
}
