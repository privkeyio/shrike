package com.sparrowwallet.sparrow;

import com.sparrowwallet.drongo.KeyDerivation;
import com.sparrowwallet.drongo.KeyPurpose;
import com.sparrowwallet.drongo.Network;
import com.sparrowwallet.drongo.Utils;
import com.sparrowwallet.drongo.crypto.ECKey;
import com.sparrowwallet.drongo.policy.Miniscript;
import com.sparrowwallet.drongo.policy.Policy;
import com.sparrowwallet.drongo.policy.PolicyType;
import com.sparrowwallet.drongo.protocol.*;
import com.sparrowwallet.drongo.psbt.PSBT;
import com.sparrowwallet.drongo.psbt.PSBTInput;
import com.sparrowwallet.drongo.psbt.PSBTOutput;
import com.sparrowwallet.drongo.wallet.DeterministicSeed;
import com.sparrowwallet.drongo.wallet.Keystore;
import com.sparrowwallet.drongo.wallet.KeystoreSource;
import com.sparrowwallet.drongo.wallet.WalletNode;
import com.sparrowwallet.drongo.wallet.Wallet;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import com.sparrowwallet.sparrow.event.UnifiedSigHashScheduleEvent;
import com.sparrowwallet.sparrow.event.NewBlockEvent;
import com.sparrowwallet.sparrow.wallet.SendController;
import java.util.Arrays;
import java.util.List;

/**
 * Whether a transaction being sent opts in to the unified signature hash.
 *
 * The digest itself is drongo's, and is checked there against the reference vectors. What belongs
 * here is the decision: opt in exactly when the chain says the fork is live, and leave the hash type
 * alone when it is not.
 */
public class UnifiedSigHashPolicyTest {
    @org.junit.jupiter.api.BeforeEach
    @org.junit.jupiter.api.AfterEach
    public void resetNodeHardforkHeight() {
        //Static state, so without this the order tests happen to run in decides the result
        AppServices.clearNodeHardforkHeight();
    }

    /**
     * A v2 header, taken from drongo's BlockHeaderPoWHashTest. Only its version word matters here.
     */
    private static final String V2_HEADER_HEX = "000000a01f1e1d1c1b1a191817161514131211100f0e0d0c0b0a0908070605040302010000112233445566778899aabbccddeeff00102030405060708090a0b0c0d0e0f0a8913577ffff00200df0ad0b3a000000efcdab89ffeeddccbbaa998877665544332211005802000003005c000000000000000000000000000000000040d10c008967452301efcdab8967452301efcdab8967452301efcdab8967452301efcdab";

    /**
     * Bitcoin block 1, a v1 header of the ordinary 80 bytes.
     */
    private static final String V1_HEADER_HEX =
            "010000006fe28c0ab6f1b372c1a6a246ae63f74f931e8365e15a089c68d6190000000000982051fd1e4ba744bbbe680e1fee14677ba1a3c3540bf7b1cdb606e857233e0e61bc6649ffff001d01e36299";

    private BlockHeader header(String hex) {
        return new BlockHeader(Utils.hexToBytes(hex), 0);
    }

    @Test
    public void testNoChainTipDoesNotOptIn() {
        Assertions.assertFalse(AppServices.isUnifiedSigHashActive(Network.REGTEST, 100, null),
                "With no chain tip there is nothing to say the fork is live");
    }

    @Test
    public void testAV1TipDoesNotOptIn() {
        BlockHeader blockHeader = header(V1_HEADER_HEX);
        Assertions.assertFalse(blockHeader.isHeaderV2());
        Assertions.assertFalse(AppServices.isUnifiedSigHashActive(Network.REGTEST, 100, blockHeader));
    }

    @Test
    public void testAV2TipOptsInOnRegtest() {
        BlockHeader blockHeader = header(V2_HEADER_HEX);
        Assertions.assertTrue(blockHeader.isHeaderV2(), "Fixture is not a v2 header");
        Assertions.assertTrue(AppServices.isUnifiedSigHashActive(Network.REGTEST, 100, blockHeader),
                "Regtest chooses its own activation height, so the chain is the only answer available");
    }

    /**
     * The property that matters for mainnet, now that a height ships for it.
     *
     * This used to hold because mainnet was unscheduled, so no header could make a wallet opt in at all. That floor
     * is gone: the shipped height is what a server cannot move, and below it a forged v2 header buys nothing. Above
     * it the wallet does opt in on a v2 tip, which is the point of shipping a height, and the node cross-check is
     * what covers a server lying about the tip.
     */
    @Test
    public void testAForgedV2TipCannotOptInBelowTheMainnetHeight() {
        BlockHeader forged = header(V2_HEADER_HEX);
        Assertions.assertTrue(forged.isHeaderV2());

        int activationHeight = AppServices.getUnifiedSigHashActivationHeight(Network.MAINNET);
        Assertions.assertFalse(AppServices.isUnifiedSigHashActive(Network.MAINNET, activationHeight - 1, forged));
        Assertions.assertFalse(AppServices.isUnifiedSigHashActive(Network.MAINNET, 1, forged));
        Assertions.assertTrue(AppServices.isUnifiedSigHashActive(Network.MAINNET, activationHeight, forged),
                "at the height the chain has activated, so a v2 tip opts in");
    }

    /**
     * A v1 tip never opts in, whatever height is claimed. The proof of work change and this one activate together,
     * so a chain still serving v1 headers has not activated whatever its height says.
     */
    @Test
    public void testAV1TipNeverOptsInOnMainnet() {
        BlockHeader v1 = Network.MAINNET.getGenesisHeader();
        Assertions.assertFalse(v1.isHeaderV2());
        Assertions.assertFalse(AppServices.isUnifiedSigHashActive(Network.MAINNET, Integer.MAX_VALUE, v1));
    }

    /**
     * Where a height is known, it gates the decision, so a server understating or overstating the tip
     * cannot move activation on its own.
     */
    @Test
    public void testAKnownHeightGatesTheDecision() {
        BlockHeader blockHeader = header(V2_HEADER_HEX);
        int activation = AppServices.getUnifiedSigHashActivationHeight(Network.TESTNET4);
        Assertions.assertFalse(AppServices.isUnifiedSigHashActive(Network.TESTNET4, activation - 1, blockHeader));
        Assertions.assertTrue(AppServices.isUnifiedSigHashActive(Network.TESTNET4, activation, blockHeader));
        Assertions.assertFalse(AppServices.isUnifiedSigHashActive(Network.TESTNET4, null, blockHeader));
    }

    @Test
    public void testOptingInSetsTheHashTypeOnEveryInput() {
        PSBT psbt = twoInputPsbt();
        AppServices.applyUnifiedSigHash(psbt, true);
        for(PSBTInput psbtInput : psbt.getPsbtInputs()) {
            Assertions.assertEquals(SigHash.UNIFIED_ALL, psbtInput.getSigHash());
        }
    }

    /**
     * Before activation the hash type must be left exactly as it was: a signature that opted in early
     * is neither relayable nor minable.
     */
    @Test
    public void testNotOptingInLeavesTheHashTypeAlone() {
        PSBT psbt = twoInputPsbt();
        psbt.getPsbtInputs().forEach(psbtInput -> psbtInput.setSigHash(SigHash.ALL));
        List<SigHash> before = psbt.getPsbtInputs().stream().map(PSBTInput::getSigHash).toList();
        AppServices.applyUnifiedSigHash(psbt, false);
        List<SigHash> after = psbt.getPsbtInputs().stream().map(PSBTInput::getSigHash).toList();
        Assertions.assertEquals(before, after, "The hash type must be untouched before activation");
        Assertions.assertEquals(List.of(SigHash.ALL, SigHash.ALL), after);
    }

    /**
     * The send path builds its PSBT from a WalletTransaction, where the hash type is already ALL for
     * everything but taproot. That is the value the opt-in has to be applied on top of.
     */
    @Test
    public void testOptingInOverAnExplicitAll() {
        PSBT psbt = twoInputPsbt();
        psbt.getPsbtInputs().forEach(psbtInput -> psbtInput.setSigHash(SigHash.ALL));
        AppServices.applyUnifiedSigHash(psbt, true);
        for(PSBTInput psbtInput : psbt.getPsbtInputs()) {
            Assertions.assertEquals(SigHash.UNIFIED_ALL, psbtInput.getSigHash());
        }
    }

    /**
     * A taproot input declares DEFAULT, which appends no hash type byte and so cannot carry the opt-in.
     * It has to become the explicit type that means the same thing.
     */
    @Test
    public void testOptingInOverTaprootDefault() {
        PSBT psbt = twoInputPsbt();
        psbt.getPsbtInputs().forEach(psbtInput -> psbtInput.setSigHash(SigHash.DEFAULT));
        AppServices.applyUnifiedSigHash(psbt, true);
        for(PSBTInput psbtInput : psbt.getPsbtInputs()) {
            Assertions.assertEquals(SigHash.UNIFIED_ALL, psbtInput.getSigHash());
        }
    }

    /**
     * A silent payment send opts in like any other. The unified message commits to every input and every
     * output, which is the property BIP352 needs, so drongo compares the base hash type and accepts it.
     * Skipping these sends in the wallet was a workaround for an over-strict check in the library, and
     * the check is the right place to fix.
     */
    @Test
    public void testASilentPaymentSendOptsInAndIsAccepted() throws Exception {
        PSBT psbt = new PSBT(silentPaymentPsbtBytes());
        Assertions.assertNotNull(psbt.getPsbtOutputs().getFirst().getSilentPaymentAddress(),
                "Fixture does not carry a silent payment output");

        AppServices.applyUnifiedSigHash(psbt, true);
        for(PSBTInput psbtInput : psbt.getPsbtInputs()) {
            Assertions.assertEquals(SigHash.UNIFIED_ALL, psbtInput.getSigHash());
        }
    }

    private byte[] silentPaymentPsbtBytes() {
        PSBT psbt = twoInputPsbt();
        psbt.getPsbtInputs().forEach(psbtInput -> psbtInput.setSigHash(SigHash.ALL));
        byte[] serialized = psbt.serialize();

        //Two compressed public keys, the scan key and the spend key
        byte[] scanKey = ECKey.fromPrivate(Utils.hexToBytes("33".repeat(32))).getPubKey();
        byte[] spendKey = ECKey.fromPrivate(Utils.hexToBytes("44".repeat(32))).getPubKey();
        byte[] value = new byte[scanKey.length + spendKey.length];
        System.arraycopy(scanKey, 0, value, 0, scanKey.length);
        System.arraycopy(spendKey, 0, value, scanKey.length, spendKey.length);

        //The final byte closes the last output map, so the entry goes immediately before it
        ByteArrayOutputStream spliced = new ByteArrayOutputStream();
        spliced.write(serialized, 0, serialized.length - 1);
        spliced.writeBytes(new VarInt(1).encode());
        spliced.write(PSBTOutput.PSBT_OUT_SP_V0_INFO);
        spliced.writeBytes(new VarInt(value.length).encode());
        spliced.writeBytes(value);
        spliced.write(0x00);

        return spliced.toByteArray();
    }

    /**
     * Opt in where this wallet holds the keys, and where a device has been marked as supporting it.
     *
     * A device that has not implemented the opt-in either refuses the hash type outright, as the BitBox02
     * does, or ignores the PSBT's request and signs the legacy message while the PSBT declares the new
     * one, as Trezor and Ledger do. A third case, measured against stock embit at 84cce66, is worse than
     * either: sign_with skips an input whose hash type it does not recognise, so the device adds no
     * signature at all and reports a failure of its own. An airgapped one returns nothing at that point,
     * so there is no signature for the wallet to inspect and nothing to fall back from.
     *
     * That is why an unmarked device does not opt in. Nothing a device sends says which firmware it runs,
     * so assuming support would break signing rather than cost it replay protection.
     */
    @Test
    public void testAnUnmarkedDeviceDoesNotOptIn() {
        Assertions.assertTrue(AppServices.canSignUnified(walletWith(KeystoreSource.SW_SEED)));
        for(KeystoreSource source : List.of(KeystoreSource.HW_USB, KeystoreSource.HW_AIRGAPPED, KeystoreSource.SW_WATCH)) {
            Assertions.assertFalse(AppServices.canSignUnified(walletWith(source)),
                    source + " cannot be relied on to produce an opted-in signature unmarked");
        }
    }

    /**
     * Marked, a device is taken at its owner's word, which is the only source of the answer there is.
     */
    @Test
    public void testAMarkedDeviceOptsIn() {
        for(KeystoreSource source : List.of(KeystoreSource.HW_USB, KeystoreSource.HW_AIRGAPPED)) {
            Assertions.assertTrue(AppServices.canSignUnified(markedWalletWith(source)),
                    source + " should opt in once marked as supporting it");
        }
    }

    /**
     * A watch only keystore produces no signature whatever it has been marked as, so marking one cannot
     * put the wallet in a position to opt in. Worth pinning, since the flag is stored on every keystore.
     */
    @Test
    public void testMarkingAWatchOnlyKeystoreChangesNothing() {
        Assertions.assertFalse(AppServices.canSignUnified(markedWalletWith(KeystoreSource.SW_WATCH)));
        Assertions.assertEquals(UnifiedSigHashDecision.NO_DEVICE_TO_MARK, AppServices.keystoreDecision(markedWalletWith(KeystoreSource.SW_WATCH)));
    }

    /**
     * The reason a user is shown has to point at the thing they can change, or the setting exists and
     * nobody finds it. This is the only decision with anything to act on.
     */
    @Test
    public void testOnlyTheUnmarkedDeviceReasonCarriesARemedy() {
        List<UnifiedSigHashDecision> actionable =
                List.of(UnifiedSigHashDecision.EXTERNAL_SIGNER, UnifiedSigHashDecision.NO_DEVICE_TO_MARK);
        for(UnifiedSigHashDecision decision : UnifiedSigHashDecision.values()) {
            if(actionable.contains(decision)) {
                Assertions.assertNotNull(decision.getRemedy(), decision + " has something the user can act on");
            } else {
                Assertions.assertNull(decision.getRemedy(), decision + " has no remedy the user can act on");
            }
        }
    }

    /**
     * The remedy on EXTERNAL_SIGNER names the Replay protection control, which KeystoreController only shows for
     * hardware sources. So that reason must only be reachable for a wallet that has one, or it describes a
     * checkbox that is not there.
     *
     * That was the bug: a watch only wallet reported EXTERNAL_SIGNER and sent its owner hunting for a control the
     * keystore tab never draws for it.
     */
    @Test
    public void testTheMarkableReasonIsOnlyGivenWhereThereIsSomethingToMark() {
        for(KeystoreSource blocking : List.of(KeystoreSource.SW_WATCH, KeystoreSource.SW_PAYMENT_CODE)) {
            Assertions.assertEquals(UnifiedSigHashDecision.NO_DEVICE_TO_MARK,
                    AppServices.keystoreDecision(walletWith(blocking)),
                    blocking + " has no device to mark, so must not be told to mark one");
            Assertions.assertEquals(UnifiedSigHashDecision.NO_DEVICE_TO_MARK,
                    AppServices.keystoreDecision(walletWith(KeystoreSource.HW_USB, blocking)),
                    blocking + " alongside a device still cannot be fixed by marking");
        }
    }

    /**
     * One external cosigner in a multisig is enough to break the transaction, so every keystore has to
     * qualify, not just one.
     */
    @Test
    public void testAMixedMultisigOptsIn() {
        //The seed opts in and the unmarked device signs the base type alongside it, which is protection either way
        Assertions.assertTrue(AppServices.canSignUnified(walletWith(KeystoreSource.SW_SEED, KeystoreSource.HW_USB)));
        Assertions.assertTrue(AppServices.canSignUnified(walletWith(KeystoreSource.SW_SEED, KeystoreSource.SW_SEED)));
        Assertions.assertFalse(AppServices.canSignUnified(walletWith()), "A wallet with no keystores cannot sign");
        Assertions.assertFalse(AppServices.canSignUnified(null));

        //One unmarked device is enough to decide for the whole wallet, and marking it is enough to change that
        Wallet mixed = walletWith(KeystoreSource.SW_SEED, KeystoreSource.HW_AIRGAPPED);
        Assertions.assertTrue(AppServices.canSignUnified(mixed));
        mixed.getKeystores().getLast().setUnifiedSigHashSupported(true);
        Assertions.assertTrue(AppServices.canSignUnified(mixed));
    }

    /**
     * The shipped schedule stands when the node reports nothing, which is every Electrum connection and
     * every node without the deployment.
     */
    @Test
    public void testTheShippedHeightStandsWhenTheNodeIsSilent() {
        Assertions.assertTrue(AppServices.isUnifiedSigHashActive(1000, null, 1000));
        Assertions.assertFalse(AppServices.isUnifiedSigHashActive(1000, null, 999));
        Assertions.assertFalse(AppServices.isUnifiedSigHashActive(null, null, 1000),
                "With no shipped height there is nothing to activate against");
    }

    /**
     * Agreement between the two changes nothing.
     */
    @Test
    public void testAgreementWithTheNodeChangesNothing() {
        Assertions.assertTrue(AppServices.isUnifiedSigHashActive(1000, 1000, 1000));
        Assertions.assertFalse(AppServices.isUnifiedSigHashActive(1000, 1000, 999));
    }

    /**
     * The point of the cross-check: if the flagday moves after this build ships, the wallet notices and
     * declines rather than signing under a schedule the network does not share. Declining is safe in
     * both directions, since a signature that does not opt in is always valid.
     */
    @Test
    public void testDisagreementWithTheNodeDeclinesToOptIn() {
        Assertions.assertFalse(AppServices.isUnifiedSigHashActive(1000, 2000, 5000),
                "The node moved the flagday later; this build must not opt in on its own schedule");
        Assertions.assertFalse(AppServices.isUnifiedSigHashActive(1000, 500, 5000),
                "The node moved the flagday earlier; this build must not opt in on its own schedule");
    }

    /**
     * A node that has scheduled the fork while this build has not. This is the mainnet case once a
     * flagday is set: the wallet must decline rather than adopt a height a node offers, but it has to
     * report it, because signing the legacy way past the flagday forgoes replay protection.
     */
    @Test
    public void testANodeSchedulingWhatThisBuildDoesNotDeclinesAndReports() {
        AppServices.clearNodeHardforkHeight();
        Assertions.assertFalse(AppServices.isUnifiedSigHashActive(null, 900000, 900001),
                "A height the node offers is not one this wallet can adopt");
        Assertions.assertEquals("unknown/900000", AppServices.getLastActivationHeightReport(),
                "The operator has to be told an update is due");
    }

    /**
     * A node that says nothing about a network this build has no height for stays silent, since there is
     * no disagreement to report. This is every Electrum connection on such a network.
     */
    @Test
    public void testNoHeightAnywhereReportsNothing() {
        AppServices.clearNodeHardforkHeight();
        Assertions.assertFalse(AppServices.isUnifiedSigHashActive(null, null, 900001));
        Assertions.assertNull(AppServices.getLastActivationHeightReport());
    }

    /**
     * The report is per connection, not per transaction: a stale build disagrees on every send, and the
     * operator only needs telling once.
     */
    @Test
    public void testTheMismatchIsReportedOncePerConnection() {
        AppServices.clearNodeHardforkHeight();
        for(int i = 0; i < 5; i++) {
            Assertions.assertFalse(AppServices.isUnifiedSigHashActive(1000, 2000, 5000));
        }
        Assertions.assertEquals("1000/2000", AppServices.getLastActivationHeightReport());
        Assertions.assertFalse(AppServices.isNewActivationHeightReport("1000/2000"),
                "A sixth send against the same node must not report again");
    }

    /**
     * Reconnecting reports again, so a height from one node cannot silence the report for another.
     */
    @Test
    public void testReconnectingReportsAgain() {
        AppServices.clearNodeHardforkHeight();
        AppServices.isUnifiedSigHashActive(1000, 2000, 5000);
        AppServices.clearNodeHardforkHeight();
        Assertions.assertNull(AppServices.getLastActivationHeightReport(),
                "clearNodeHardforkHeight has to reset the report alongside the height");
        Assertions.assertTrue(AppServices.isNewActivationHeightReport("1000/2000"));
    }

    /**
     * A different disagreement is a different report, so moving between two stale nodes is not silent.
     */
    @Test
    public void testADifferentDisagreementIsReportedSeparately() {
        AppServices.clearNodeHardforkHeight();
        AppServices.isUnifiedSigHashActive(1000, 2000, 5000);
        AppServices.isUnifiedSigHashActive(1000, 3000, 5000);
        Assertions.assertEquals("1000/3000", AppServices.getLastActivationHeightReport());
    }

    /**
     * The shipped testnet4 height must match the node the wallet is built against, or the cross-check
     * above would fire on every correctly configured connection.
     */
    @Test
    public void testTheShippedTestnet4HeightIsTheOneKnotsUses() {
        Assertions.assertEquals(150308, AppServices.getUnifiedSigHashActivationHeight(Network.TESTNET4),
                "Update the provenance comment alongside this value");
    }

    /**
     * Mainnet ships a height now, so the wallet opts in there once the chain reaches it. Until it did, the build
     * declined everywhere on mainnet, which is a materially different thing to be shipping.
     */
    @Test
    public void testTheShippedMainnetHeightIsTheOneKnotsUses() {
        Assertions.assertEquals(961640, AppServices.getUnifiedSigHashActivationHeight(Network.MAINNET),
                "Update the provenance comment alongside this value");
        Assertions.assertNull(AppServices.getUnifiedSigHashActivationHeight(Network.REGTEST),
                "Regtest chooses its own height through the node, so this build ships none");
    }

    /**
     * The wiring, rather than the comparison.
     *
     * Every other test here calls the pure overload with literals, so the field could be dropped from the
     * production path entirely and they would all still pass. This is the test that fails if the
     * cross-check stops being consulted where it actually matters.
     */
    @Test
    public void testTheNodeHeightReachesTheDecision() {
        BlockHeader v2Tip = header(V2_HEADER_HEX);
        int shipped = AppServices.getUnifiedSigHashActivationHeight(Network.TESTNET4);

        Assertions.assertTrue(AppServices.isUnifiedSigHashActive(Network.TESTNET4, shipped, v2Tip),
                "With no node height recorded the shipped schedule should stand");

        AppServices.setNodeHardforkHeight(shipped + 1);
        Assertions.assertFalse(AppServices.isUnifiedSigHashActive(Network.TESTNET4, shipped, v2Tip),
                "A node height that disagrees must reach the decision and decline");

        AppServices.setNodeHardforkHeight(shipped);
        Assertions.assertTrue(AppServices.isUnifiedSigHashActive(Network.TESTNET4, shipped, v2Tip),
                "A node height that agrees must not block the opt-in");

        AppServices.clearNodeHardforkHeight();
        Assertions.assertTrue(AppServices.isUnifiedSigHashActive(Network.TESTNET4, shipped, v2Tip),
                "Clearing must restore the shipped schedule, not leave the last node deciding");
    }

    /**
     * The case this exists for: a 2-of-3 holding one signer that cannot opt in. Two marked signers are a quorum on
     * their own, so the wallet opts in rather than declining on the third, and says the transaction has to be signed
     * by the marked ones.
     */
    @Test
    public void testAQuorumOfMarkedSignersOptsIn() {
        Wallet wallet = multisigWith(2, KeystoreSource.HW_USB, KeystoreSource.HW_USB, KeystoreSource.HW_USB);
        wallet.getKeystores().get(0).setUnifiedSigHashSupported(true);
        wallet.getKeystores().get(1).setUnifiedSigHashSupported(true);

        Assertions.assertTrue(AppServices.canSignUnified(wallet));
        //Guaranteed: the single unmarked signer cannot reach the threshold alone, so every quorum carries an opt-in
        Assertions.assertEquals(UnifiedSigHashDecision.OPTED_IN, AppServices.keystoreDecision(wallet));
    }

    /**
     * One short of the threshold is not a quorum, so there is no signing combination that opts in and the wallet
     * declines rather than building something only part of it can sign.
     */
    /**
     * One marked signer of three, needing two. The protocol protects any transaction carrying one opted-in signature,
     * so this opts in and the unmarked signers are handed the base type. It is not a guarantee: those two could form a
     * quorum between them and that transaction would carry no opt-in at all, which is what the caveat says.
     */
    @Test
    public void testOneMarkedSignerOptsInConditionally() {
        Wallet wallet = multisigWith(2, KeystoreSource.HW_USB, KeystoreSource.HW_USB, KeystoreSource.HW_USB);
        wallet.getKeystores().get(0).setUnifiedSigHashSupported(true);

        Assertions.assertTrue(AppServices.canSignUnified(wallet));
        Assertions.assertEquals(UnifiedSigHashDecision.OPTED_IN_IF_MARKED_SIGNS, AppServices.keystoreDecision(wallet));

        //None marked is the only decline left: nothing in the transaction could opt in
        wallet.getKeystores().get(0).setUnifiedSigHashSupported(false);
        Assertions.assertFalse(AppServices.canSignUnified(wallet));
        Assertions.assertEquals(UnifiedSigHashDecision.EXTERNAL_SIGNER, AppServices.keystoreDecision(wallet));
    }

    /**
     * Every signer marked is not a partial quorum, and must not carry the caveat that some of them cannot sign.
     */
    @Test
    public void testEveryMarkedSignerIsNotAPartialQuorum() {
        Wallet wallet = multisigWith(2, KeystoreSource.HW_USB, KeystoreSource.HW_USB, KeystoreSource.HW_USB);
        wallet.getKeystores().forEach(keystore -> keystore.setUnifiedSigHashSupported(true));

        Assertions.assertEquals(UnifiedSigHashDecision.OPTED_IN, AppServices.keystoreDecision(wallet));
        Assertions.assertNull(UnifiedSigHashDecision.OPTED_IN.getCaveat());
    }

    /**
     * A threshold that cannot be read is not grounds for opting in on fewer signers than the wallet might need, so
     * the fallback requires every keystore. getNumSignaturesRequired throws on a policy it cannot parse, and this
     * is called on the send path where throwing would take the screen with it.
     */
    @Test
    public void testAnUnreadableThresholdRequiresEveryKeystore() {
        Wallet wallet = multisigWith(2, KeystoreSource.HW_USB, KeystoreSource.HW_USB, KeystoreSource.HW_USB);
        wallet.getKeystores().get(0).setUnifiedSigHashSupported(true);
        wallet.getKeystores().get(1).setUnifiedSigHashSupported(true);
        Assertions.assertTrue(AppServices.canSignUnified(wallet), "a readable threshold opts in on a quorum");

        //A threshold that cannot be read is taken as one, so only an entirely marked wallet is called guaranteed.
        //Erring high would claim a guarantee that a smaller real quorum could break.
        wallet.setDefaultPolicy(null);
        Assertions.assertNull(AppServices.readThreshold(wallet), "an absent policy reads as no threshold");
        Assertions.assertEquals(UnifiedSigHashDecision.OPTED_IN_IF_MARKED_SIGNS, AppServices.keystoreDecision(wallet),
                "unreadable means conditional, not guaranteed");

        wallet.setDefaultPolicy(new Policy(new Miniscript("not a parseable descriptor")));
        Assertions.assertNull(AppServices.readThreshold(wallet), "an unparseable policy must not throw");
        Assertions.assertEquals(UnifiedSigHashDecision.OPTED_IN_IF_MARKED_SIGNS, AppServices.keystoreDecision(wallet));

        wallet.getKeystores().get(2).setUnifiedSigHashSupported(true);
        Assertions.assertEquals(UnifiedSigHashDecision.OPTED_IN, AppServices.keystoreDecision(wallet),
                "every signer marked is guaranteed whatever the threshold");
    }

    /**
     * The whole point of the quorum rule, carried through to a signed transaction: a 2-of-3 where the third signer
     * holds no key at all still produces a valid spend, and every signature in it opts in.
     *
     * This is what the threshold rule asserts is possible. Without it the rule is an argument about consensus rather
     * than something the wallet has been shown to do, and the difference matters because a PSBT declaring a hash
     * type only some signers can produce is exactly the thing that could fail at finalisation.
     */
    @Test
    public void testAQuorumSignsAndFinalisesAnOptedInSpend() throws Exception {
        String[] mnemonics = {
                "absent essay fox snake vast pumpkin height crouch silent bulb excuse razor",
                "sample vibrant sound quantum ripple hidden pluck raven mirror ocean fabric noodle",
                "vault cruise pistol trigger pilot scan hidden major fringe course fiber quiz"};

        Wallet wallet = new Wallet();
        wallet.setPolicyType(PolicyType.MULTI_HD);
        wallet.setScriptType(ScriptType.P2WSH);
        for(String mnemonic : mnemonics) {
            DeterministicSeed seed = new DeterministicSeed(mnemonic, "", 0, DeterministicSeed.Type.BIP39);
            wallet.getKeystores().add(Keystore.fromSeed(seed, PolicyType.MULTI_HD, ScriptType.P2WSH.getDefaultDerivation()));
        }
        wallet.setDefaultPolicy(Policy.getPolicy(PolicyType.MULTI_HD, ScriptType.P2WSH, wallet.getKeystores(), 2));
        wallet.getNode(KeyPurpose.RECEIVE);

        //The third signer contributes its key to the script but cannot sign, which is the lagging cosigner
        wallet.getKeystores().get(2).setSeed(null);
        Assertions.assertFalse(wallet.getKeystores().get(2).hasPrivateKey());

        WalletNode receiveNode = wallet.getNode(KeyPurpose.RECEIVE).getChildren().iterator().next();
        Script spk = wallet.getOutputScript(receiveNode);

        Transaction transaction = new Transaction();
        transaction.setVersion(2);
        transaction.addInput(Sha256Hash.wrap("0000000000000000000000000000000000000000000000000000000000000001"), 0, new Script(new byte[0]));
        transaction.getInputs().getFirst().setSequenceNumber(0xFFFFFFFEL);
        transaction.addOutput(90000L, spk);

        PSBT psbt = new PSBT(transaction);
        PSBTInput psbtInput = psbt.getPsbtInputs().getFirst();
        psbtInput.setWitnessUtxo(new TransactionOutput(null, 100000L, spk.getProgram()));
        psbtInput.setWitnessScript(ScriptType.MULTISIG.getOutputScript(2, receiveNode.getPubKeys()));
        psbtInput.setSigHash(SigHash.ALL);

        AppServices.applyUnifiedSigHash(psbt, true);
        Assertions.assertEquals(SigHash.UNIFIED_ALL, psbt.getPsbtInputs().getFirst().getSigHash());

        wallet.sign(psbt);
        wallet.finalise(psbt);
        Transaction finalTx = psbt.extractTransaction();

        TransactionWitness witness = finalTx.getInputs().getFirst().getWitness();
        Assertions.assertNotNull(witness, "the quorum has to produce a witness");
        List<byte[]> signatures = witness.getPushes().stream()
                .filter(push -> push.length >= 70 && push.length <= 73)
                .toList();
        Assertions.assertEquals(2, signatures.size(), "a 2-of-3 finalises on two signatures, not three");
        for(byte[] signature : signatures) {
            Assertions.assertEquals((byte)0x21, signature[signature.length - 1],
                    "every signature in the quorum opts in");
        }
    }

    /**
     * Both the chain and the keystores can now qualify an opt-in, and only one reason is shown. The chain's caveat
     * is about whether the schedule this signed under is the right one, which decides whether the protection holds
     * at all; the keystores' is about who can sign what was built. The first has to win, or a build running on an
     * unverified height reports only that some cosigner is unmarked.
     */
    @Test
    public void testAChainCaveatOutranksAKeystoreCaveat() {
        Wallet quorum = multisigWith(2, KeystoreSource.HW_USB, KeystoreSource.HW_USB, KeystoreSource.HW_USB);
        quorum.getKeystores().get(0).setUnifiedSigHashSupported(true);
        quorum.getKeystores().get(1).setUnifiedSigHashSupported(true);

        Assertions.assertEquals(UnifiedSigHashDecision.OPTED_IN,
                AppServices.combinedDecision(UnifiedSigHashDecision.OPTED_IN, quorum));
        Assertions.assertEquals(UnifiedSigHashDecision.OPTED_IN_UNCORROBORATED,
                AppServices.combinedDecision(UnifiedSigHashDecision.OPTED_IN_UNCORROBORATED, quorum));

        //A decline outranks either caveat, because nothing opted in at all
        Wallet belowThreshold = multisigWith(2, KeystoreSource.HW_USB, KeystoreSource.HW_USB, KeystoreSource.HW_USB);
        Assertions.assertEquals(UnifiedSigHashDecision.EXTERNAL_SIGNER,
                AppServices.combinedDecision(UnifiedSigHashDecision.OPTED_IN_UNCORROBORATED, belowThreshold));
    }

    /**
     * The ordinary case, carried the same distance as the multisig one: a single signature wallet builds, signs and
     * finalises a spend whose signature opts in. Most wallets are this shape, and until now nothing in this project
     * signed one in process; the harness that did is a main method the suite never runs.
     */
    @Test
    public void testASingleSignatureWalletSignsAnOptedInSpend() throws Exception {
        DeterministicSeed seed = new DeterministicSeed(
                "absent essay fox snake vast pumpkin height crouch silent bulb excuse razor", "", 0, DeterministicSeed.Type.BIP39);
        Wallet wallet = new Wallet();
        wallet.setPolicyType(PolicyType.SINGLE_HD);
        wallet.setScriptType(ScriptType.P2WPKH);
        wallet.getKeystores().add(Keystore.fromSeed(seed, PolicyType.SINGLE_HD, ScriptType.P2WPKH.getDefaultDerivation()));
        wallet.setDefaultPolicy(Policy.getPolicy(PolicyType.SINGLE_HD, ScriptType.P2WPKH, wallet.getKeystores(), 1));
        wallet.getNode(KeyPurpose.RECEIVE);

        WalletNode receiveNode = wallet.getNode(KeyPurpose.RECEIVE).getChildren().iterator().next();
        Script spk = wallet.getOutputScript(receiveNode);

        Transaction transaction = new Transaction();
        transaction.setVersion(2);
        transaction.addInput(Sha256Hash.wrap("0000000000000000000000000000000000000000000000000000000000000001"), 0, new Script(new byte[0]));
        transaction.getInputs().getFirst().setSequenceNumber(0xFFFFFFFEL);
        transaction.addOutput(90000L, spk);

        PSBT psbt = new PSBT(transaction);
        PSBTInput psbtInput = psbt.getPsbtInputs().getFirst();
        psbtInput.setWitnessUtxo(new TransactionOutput(null, 100000L, spk.getProgram()));
        psbtInput.setSigHash(SigHash.ALL);

        Assertions.assertTrue(AppServices.canSignUnified(wallet));
        AppServices.applyUnifiedSigHash(psbt, true);
        wallet.sign(psbt);
        wallet.finalise(psbt);

        TransactionWitness witness = psbt.extractTransaction().getInputs().getFirst().getWitness();
        Assertions.assertNotNull(witness);
        byte[] signature = witness.getPushes().getFirst();
        Assertions.assertTrue(signature.length >= 70 && signature.length <= 73, "the first push is the signature");
        Assertions.assertEquals((byte)0x21, signature[signature.length - 1], "the signature opts in");
    }

    /**
     * The quorum rule made it possible to hand a device a transaction it cannot sign: a 2-of-3 with two marked
     * signers declares the opt-in, and the third device is then asked for a hash type it never claimed. Left alone
     * that surfaces as whatever the firmware says, which names no reason anyone can act on.
     */
    @Test
    public void testAnUnmarkedDeviceIsToldWhyBeforeItRefuses() throws Exception {
        Wallet wallet = multisigWith(2, KeystoreSource.HW_USB, KeystoreSource.HW_USB, KeystoreSource.HW_USB);
        String[] fingerprints = {"aaaaaaaa", "bbbbbbbb", "cccccccc"};
        for(int i = 0; i < 3; i++) {
            wallet.getKeystores().get(i).setKeyDerivation(new KeyDerivation(fingerprints[i], "m/48'/0'/0'/2'"));
        }
        wallet.getKeystores().get(0).setUnifiedSigHashSupported(true);
        wallet.getKeystores().get(1).setUnifiedSigHashSupported(true);

        PSBT optedIn = twoInputPsbt();
        AppServices.applyUnifiedSigHash(optedIn, true);

        Assertions.assertTrue(AppServices.deviceCannotSignDeclaredSigHash(wallet, optedIn, "cccccccc"),
                "the unmarked device is asked for a hash type it never claimed");
        for(String marked : new String[] {"aaaaaaaa", "bbbbbbbb"}) {
            Assertions.assertFalse(AppServices.deviceCannotSignDeclaredSigHash(wallet, optedIn, marked),
                    marked + " is marked, so there is nothing to warn about");
        }

        //A transaction that did not opt in asks nothing unusual of anyone
        PSBT legacy = twoInputPsbt();
        AppServices.applyUnifiedSigHash(legacy, false);
        Assertions.assertFalse(AppServices.deviceCannotSignDeclaredSigHash(wallet, legacy, "cccccccc"));

        //Absent inputs are no grounds for inventing an objection
        Assertions.assertFalse(AppServices.deviceCannotSignDeclaredSigHash(wallet, optedIn, null));
        Assertions.assertFalse(AppServices.deviceCannotSignDeclaredSigHash(wallet, null, "cccccccc"));
        Assertions.assertFalse(AppServices.deviceCannotSignDeclaredSigHash(null, optedIn, "cccccccc"));
        Assertions.assertFalse(AppServices.deviceCannotSignDeclaredSigHash(wallet, optedIn, "dddddddd"),
                "a fingerprint this wallet does not hold is not this wallet's business");
    }

    /**
     * The caveat says the transaction has to be signed by the marked signers without saying which, which leaves the
     * reader to go and look them up. Named only where naming adds something: a wallet where every signer qualifies
     * has no subset to distinguish.
     */
    @Test
    public void testTheSignersThatCanOptInAreNamed() {
        Wallet wallet = multisigWith(2, KeystoreSource.HW_USB, KeystoreSource.HW_USB, KeystoreSource.HW_USB);
        String[] labels = {"Coldcard", "SeedSigner", "Old Jade"};
        for(int i = 0; i < 3; i++) {
            wallet.getKeystores().get(i).setLabel(labels[i]);
        }
        wallet.getKeystores().get(0).setUnifiedSigHashSupported(true);
        wallet.getKeystores().get(1).setUnifiedSigHashSupported(true);

        Assertions.assertEquals("Coldcard, SeedSigner", AppServices.markedSignerNames(wallet));

        //Every signer marked leaves no subset worth naming
        wallet.getKeystores().get(2).setUnifiedSigHashSupported(true);
        Assertions.assertNull(AppServices.markedSignerNames(wallet));

        //Nor does none of them
        wallet.getKeystores().forEach(keystore -> keystore.setUnifiedSigHashSupported(false));
        Assertions.assertNull(AppServices.markedSignerNames(wallet));

        Assertions.assertNull(AppServices.markedSignerNames(null));
    }

    /**
     * A device that has not been marked is handed the base hash type rather than turned away, so it can sign
     * alongside the marked ones. One opted-in signature in the transaction is what makes it unreplayable, which is
     * why the unmarked signature costs nothing.
     */
    /**
     * Selecting Anyone Can Pay and handing an unmarked device the base type produces a 0x81 signature. That is the one
     * legacy type which commits only to its own input and to the outputs, so unlike a legacy ALL it survives being
     * lifted into another transaction. Reachable rather than hypothetical, which is what this pins.
     */
    @Test
    public void testAnUnmarkedDeviceIsHandedTheLiftableTypeForAnyonecanpay() {
        Wallet wallet = multisigWith(2, KeystoreSource.HW_USB, KeystoreSource.HW_USB, KeystoreSource.HW_USB);
        String[] fingerprints = {"aaaaaaaa", "bbbbbbbb", "cccccccc"};
        for(int i = 0; i < 3; i++) {
            wallet.getKeystores().get(i).setKeyDerivation(new KeyDerivation(fingerprints[i], "m/48'/0'/0'/2'"));
        }
        wallet.getKeystores().get(0).setUnifiedSigHashSupported(true);
        wallet.getKeystores().get(1).setUnifiedSigHashSupported(true);

        PSBT psbt = twoInputPsbt();
        for(PSBTInput psbtInput : psbt.getPsbtInputs()) {
            psbtInput.setSigHash(SigHash.ANYONECANPAY_ALL);
        }
        AppServices.applyUnifiedSigHash(psbt, true);
        Assertions.assertEquals(SigHash.UNIFIED_ANYONECANPAY_ALL, psbt.getPsbtInputs().get(0).getSigHash(),
                "the opt-in bit rides on the type the user chose");

        PSBT forUnmarked = AppServices.psbtForDevice(wallet, psbt, "cccccccc");
        Assertions.assertEquals(SigHash.ANYONECANPAY_ALL, forUnmarked.getPsbtInputs().get(0).getSigHash(),
                "which for ANYONECANPAY is the signature that can be lifted out");
    }

    /**
     * Counted off the signatures, and only the ones that can actually be lifted. An opted-in ANYONECANPAY signature is
     * invalid under the pre-fork rules to begin with, and a legacy ALL commits to every input, so neither counts.
     * Proven against a node in anyonecanpay_lift.py, where the lifted signature was mined on the pre-fork chain.
     */
    @Test
    public void testOnlyALegacyAnyonecanpaySignatureIsCountedAsLiftable() throws Exception {
        Assertions.assertEquals(0, AppServices.liftableSignatureCount(null), "no transaction, nothing to count");

        Assertions.assertEquals(1, liftableCountForMixedWitness(SigHash.UNIFIED_ANYONECANPAY_ALL, SigHash.ANYONECANPAY_ALL),
                "the legacy ANYONECANPAY signature can be lifted, the opted-in one cannot");
        Assertions.assertEquals(0, liftableCountForMixedWitness(SigHash.UNIFIED_ALL, SigHash.ALL),
                "a legacy ALL commits to every input, so it is useless in another transaction");
        Assertions.assertEquals(0, liftableCountForMixedWitness(SigHash.UNIFIED_ANYONECANPAY_ALL, SigHash.UNIFIED_ANYONECANPAY_ALL),
                "an opted-in ANYONECANPAY is refused under the pre-fork rules anyway");
        Assertions.assertEquals(2, liftableCountForMixedWitness(SigHash.ANYONECANPAY_ALL, SigHash.ANYONECANPAY_ALL),
                "both legacy, both liftable");
    }

    /** A real 2-of-2 P2WSH signed once per hash type, the way per-device PSBTs combine. */
    private int liftableCountForMixedWitness(SigHash firstType, SigHash secondType) throws Exception {
        String[] mnemonics = {
                "absent essay fox snake vast pumpkin height crouch silent bulb excuse razor",
                "sample vibrant sound quantum ripple hidden pluck raven mirror ocean fabric noodle"};

        Wallet wallet = new Wallet();
        wallet.setPolicyType(PolicyType.MULTI_HD);
        wallet.setScriptType(ScriptType.P2WSH);
        for(String mnemonic : mnemonics) {
            DeterministicSeed seed = new DeterministicSeed(mnemonic, "", 0, DeterministicSeed.Type.BIP39);
            wallet.getKeystores().add(Keystore.fromSeed(seed, PolicyType.MULTI_HD, ScriptType.P2WSH.getDefaultDerivation()));
        }
        wallet.setDefaultPolicy(Policy.getPolicy(PolicyType.MULTI_HD, ScriptType.P2WSH, wallet.getKeystores(), 2));
        wallet.getNode(KeyPurpose.RECEIVE);

        WalletNode receiveNode = wallet.getNode(KeyPurpose.RECEIVE).getChildren().iterator().next();
        Script spk = wallet.getOutputScript(receiveNode);

        Transaction transaction = new Transaction();
        transaction.setVersion(2);
        transaction.addInput(Sha256Hash.wrap("0000000000000000000000000000000000000000000000000000000000000001"), 0, new Script(new byte[0]));
        transaction.getInputs().getFirst().setSequenceNumber(0xFFFFFFFEL);
        transaction.addOutput(90000L, spk);

        PSBT psbt = new PSBT(transaction);
        PSBTInput psbtInput = psbt.getPsbtInputs().getFirst();
        psbtInput.setWitnessUtxo(new TransactionOutput(null, 100000L, spk.getProgram()));
        psbtInput.setWitnessScript(ScriptType.MULTISIG.getOutputScript(2, receiveNode.getPubKeys()));

        //One key at a time, each asked for its own hash type, which is what per-device PSBTs produce
        DeterministicSeed second = wallet.getKeystores().get(1).getSeed();
        wallet.getKeystores().get(1).setSeed(null);
        psbtInput.setSigHash(firstType);
        wallet.sign(psbt);

        wallet.getKeystores().get(1).setSeed(second);
        DeterministicSeed first = wallet.getKeystores().getFirst().getSeed();
        wallet.getKeystores().getFirst().setSeed(null);
        psbtInput.setSigHash(secondType);
        wallet.sign(psbt);
        wallet.getKeystores().getFirst().setSeed(first);

        Assertions.assertEquals(2, AppServices.signatureOptInCounts(psbt)[1], "both keys must have signed");
        return AppServices.liftableSignatureCount(psbt);
    }

    @Test
    public void testAnUnmarkedDeviceIsHandedTheTypeItCanSign() {
        Wallet wallet = multisigWith(2, KeystoreSource.HW_USB, KeystoreSource.HW_USB, KeystoreSource.HW_USB);
        String[] fingerprints = {"aaaaaaaa", "bbbbbbbb", "cccccccc"};
        for(int i = 0; i < 3; i++) {
            wallet.getKeystores().get(i).setKeyDerivation(new KeyDerivation(fingerprints[i], "m/48'/0'/0'/2'"));
        }
        wallet.getKeystores().get(0).setUnifiedSigHashSupported(true);
        wallet.getKeystores().get(1).setUnifiedSigHashSupported(true);

        PSBT psbt = twoInputPsbt();
        AppServices.applyUnifiedSigHash(psbt, true);

        //A marked device gets the transaction itself, untouched
        Assertions.assertSame(psbt, AppServices.psbtForDevice(wallet, psbt, "aaaaaaaa"));

        //An unmarked one gets a copy asking for the base type, leaving the original alone
        PSBT forUnmarked = AppServices.psbtForDevice(wallet, psbt, "cccccccc");
        Assertions.assertNotSame(psbt, forUnmarked);
        for(PSBTInput psbtInput : forUnmarked.getPsbtInputs()) {
            Assertions.assertEquals(SigHash.ALL, psbtInput.getSigHash(), "the device is asked for what it can produce");
        }
        for(PSBTInput psbtInput : psbt.getPsbtInputs()) {
            Assertions.assertEquals(SigHash.UNIFIED_ALL, psbtInput.getSigHash(), "the original still opts in");
        }

        //Nothing to downgrade when the transaction never opted in
        PSBT legacy = twoInputPsbt();
        AppServices.applyUnifiedSigHash(legacy, false);
        Assertions.assertSame(legacy, AppServices.psbtForDevice(wallet, legacy, "cccccccc"));
    }

    /**
     * A mixed witness is counted off the signatures, not the declared hash type. The transaction is protected, since
     * one opted-in signature is enough, but only that signature commits to the amounts it spends. Reporting a single
     * answer for both would claim the second property for a signer that never had it.
     */
    @Test
    public void testAMixedWitnessIsCountedBySignature() throws Exception {
        String[] mnemonics = {
                "absent essay fox snake vast pumpkin height crouch silent bulb excuse razor",
                "sample vibrant sound quantum ripple hidden pluck raven mirror ocean fabric noodle"};

        Wallet wallet = new Wallet();
        wallet.setPolicyType(PolicyType.MULTI_HD);
        wallet.setScriptType(ScriptType.P2WSH);
        for(String mnemonic : mnemonics) {
            DeterministicSeed seed = new DeterministicSeed(mnemonic, "", 0, DeterministicSeed.Type.BIP39);
            wallet.getKeystores().add(Keystore.fromSeed(seed, PolicyType.MULTI_HD, ScriptType.P2WSH.getDefaultDerivation()));
        }
        wallet.setDefaultPolicy(Policy.getPolicy(PolicyType.MULTI_HD, ScriptType.P2WSH, wallet.getKeystores(), 2));
        wallet.getNode(KeyPurpose.RECEIVE);

        WalletNode receiveNode = wallet.getNode(KeyPurpose.RECEIVE).getChildren().iterator().next();
        Script spk = wallet.getOutputScript(receiveNode);

        Transaction transaction = new Transaction();
        transaction.setVersion(2);
        transaction.addInput(Sha256Hash.wrap("0000000000000000000000000000000000000000000000000000000000000001"), 0, new Script(new byte[0]));
        transaction.getInputs().getFirst().setSequenceNumber(0xFFFFFFFEL);
        transaction.addOutput(90000L, spk);

        PSBT psbt = new PSBT(transaction);
        PSBTInput psbtInput = psbt.getPsbtInputs().getFirst();
        psbtInput.setWitnessUtxo(new TransactionOutput(null, 100000L, spk.getProgram()));
        psbtInput.setWitnessScript(ScriptType.MULTISIG.getOutputScript(2, receiveNode.getPubKeys()));

        Assertions.assertArrayEquals(new int[] {0, 0}, AppServices.signatureOptInCounts(psbt), "nothing signed yet");

        //One key signs the opted-in message, the other the legacy one, which is what per-device PSBTs produce
        DeterministicSeed second = wallet.getKeystores().get(1).getSeed();
        wallet.getKeystores().get(1).setSeed(null);
        psbtInput.setSigHash(SigHash.UNIFIED_ALL);
        wallet.sign(psbt);

        wallet.getKeystores().get(1).setSeed(second);
        DeterministicSeed first = wallet.getKeystores().getFirst().getSeed();
        wallet.getKeystores().getFirst().setSeed(null);
        psbtInput.setSigHash(SigHash.ALL);
        wallet.sign(psbt);
        wallet.getKeystores().getFirst().setSeed(first);

        int[] counts = AppServices.signatureOptInCounts(psbt);
        Assertions.assertEquals(2, counts[1], "both keys signed");
        Assertions.assertEquals(1, counts[0], "exactly one of them opted in");
    }

    /**
     * An m-of-n with a policy actually set, which walletWith deliberately leaves absent. Without one the threshold
     * cannot be read and every keystore is required, so a wallet built there exercises the fallback rather than the
     * quorum rule.
     */
    private Wallet multisigWith(int threshold, KeystoreSource... sources) {
        Wallet wallet = new Wallet();
        wallet.setPolicyType(PolicyType.MULTI_HD);
        wallet.setScriptType(ScriptType.P2WSH);
        for(KeystoreSource source : sources) {
            Keystore keystore = new Keystore();
            keystore.setSource(source);
            wallet.getKeystores().add(keystore);
        }
        wallet.setDefaultPolicy(Policy.getPolicy(PolicyType.MULTI_HD, ScriptType.P2WSH, wallet.getKeystores(), threshold));
        return wallet;
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

    /**
     * The whole chain, from the mark a user sets to the hash type the PSBT carries.
     *
     * Every piece of this is covered on its own: the keystore decision, the chain decision, the order the two are
     * asked in, and applying the result to a PSBT. None of that says the mark reaches the transaction, which is the
     * only thing the feature exists to do. A wiring mistake anywhere between them passes every other test here.
     */
    @Test
    public void aMarkedDeviceProducesAnOptedInPsbt() {
        for(KeystoreSource source : List.of(KeystoreSource.HW_USB, KeystoreSource.HW_AIRGAPPED)) {
            Wallet unmarked = walletWith(source);
            PSBT unmarkedPsbt = AppServices.applyUnifiedSigHash(twoInputPsbt(),
                    AppServices.combinedDecision(UnifiedSigHashDecision.OPTED_IN, unmarked).isOptedIn());
            for(PSBTInput psbtInput : unmarkedPsbt.getPsbtInputs()) {
                Assertions.assertNotEquals(SigHash.UNIFIED_ALL, psbtInput.getSigHash(),
                        source + " unmarked must not produce an opted-in PSBT");
            }

            Wallet marked = markedWalletWith(source);
            PSBT markedPsbt = AppServices.applyUnifiedSigHash(twoInputPsbt(),
                    AppServices.combinedDecision(UnifiedSigHashDecision.OPTED_IN, marked).isOptedIn());
            for(PSBTInput psbtInput : markedPsbt.getPsbtInputs()) {
                Assertions.assertEquals(SigHash.UNIFIED_ALL, psbtInput.getSigHash(),
                        source + " marked must produce an opted-in PSBT");
            }
        }
    }

    /**
     * Offline there is no tip, and the wallet says so rather than reporting what the chain decided.
     *
     * These are different claims. One is the chain having answered, the other is never having asked, and a user
     * choosing between signing now and connecting first is deciding on exactly that difference. Reporting the
     * first for the second states something about a chain that was never seen.
     */
    @Test
    public void testAnUnseenChainIsNotReportedAsNotActivated() {
        for(Network network : List.of(Network.MAINNET, Network.TESTNET4, Network.REGTEST)) {
            Assertions.assertEquals(UnifiedSigHashDecision.CHAIN_UNSEEN, AppServices.chainDecision(network, null, null),
                    network + " with no tip has not been told anything about the chain");
            Assertions.assertEquals(UnifiedSigHashDecision.CHAIN_UNSEEN, AppServices.chainDecision(network, 1000, null),
                    network + " with a height but no header still has no header to judge");
        }

        //A header that is present and v1 is the chain answering, which is the other reason entirely
        BlockHeader v1 = Network.MAINNET.getGenesisHeader();
        Assertions.assertEquals(UnifiedSigHashDecision.CHAIN_NOT_ACTIVATED, AppServices.chainDecision(Network.MAINNET, 1000, v1));

        Assertions.assertFalse(UnifiedSigHashDecision.CHAIN_UNSEEN.isOptedIn());
        Assertions.assertNull(UnifiedSigHashDecision.CHAIN_UNSEEN.getRemedy(),
                "connecting is not something the wallet can do for the user, so no remedy is offered");
    }

    /**
     * The send screen must re-read the decision when either input to it moves.
     *
     * The status is rendered from the chain tip and the node's schedule, neither of which the send screen owns.
     * Rendered only when the transaction changed, it goes stale: a node upgraded mid-session reports a new
     * activation height, the disagreement clears, and the screen keeps saying the two disagree while the
     * transaction it builds is opted in. The label and the PSBT then describe the same send differently, and
     * only the label is wrong, which is the worst way round for a user deciding whether to sign.
     */
    @Test
    public void testTheSendScreenListensForWhatChangesTheDecision() {
        for(Class<?> event : List.of(UnifiedSigHashScheduleEvent.class, NewBlockEvent.class)) {
            boolean subscribed = Arrays.stream(SendController.class.getDeclaredMethods())
                    .filter(method -> method.isAnnotationPresent(com.google.common.eventbus.Subscribe.class))
                    .anyMatch(method -> method.getParameterCount() == 1 && method.getParameterTypes()[0] == event);
            Assertions.assertTrue(subscribed,
                    "SendController does not listen for " + event.getSimpleName() + ", so its status cannot refresh when the decision changes");
        }
    }

    private Wallet markedWalletWith(KeystoreSource... sources) {
        Wallet wallet = walletWith(sources);
        wallet.getKeystores().forEach(keystore -> keystore.setUnifiedSigHashSupported(true));

        return wallet;
    }

    private PSBT twoInputPsbt() {
        ECKey key = ECKey.fromPrivate(Utils.hexToBytes("11".repeat(32)));
        Script spk = ScriptType.P2WPKH.getOutputScript(PolicyType.SINGLE_HD, key);

        Transaction transaction = new Transaction();
        transaction.setVersion(2);
        transaction.addInput(Sha256Hash.wrap(Utils.hexToBytes("aa".repeat(32))), 0, new Script(new byte[0]));
        transaction.addInput(Sha256Hash.wrap(Utils.hexToBytes("bb".repeat(32))), 1, new Script(new byte[0]));
        transaction.addOutput(150000L, spk);

        PSBT psbt = new PSBT(transaction);
        psbt.getPsbtInputs().get(0).setWitnessUtxo(new TransactionOutput(null, 100000L, spk.getProgram()));
        psbt.getPsbtInputs().get(1).setWitnessUtxo(new TransactionOutput(null, 60000L, spk.getProgram()));
        return psbt;
    }
}
