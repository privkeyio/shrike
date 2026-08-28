package com.sparrowwallet.sparrow;

import com.sparrowwallet.drongo.Network;
import com.sparrowwallet.drongo.Utils;
import com.sparrowwallet.drongo.crypto.ECKey;
import com.sparrowwallet.drongo.policy.PolicyType;
import com.sparrowwallet.drongo.protocol.*;
import com.sparrowwallet.drongo.psbt.PSBT;
import com.sparrowwallet.drongo.psbt.PSBTInput;
import com.sparrowwallet.drongo.psbt.PSBTOutput;
import com.sparrowwallet.drongo.wallet.Keystore;
import com.sparrowwallet.drongo.wallet.KeystoreSource;
import com.sparrowwallet.drongo.wallet.Wallet;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
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
     * The property that matters for mainnet: the fork is not scheduled there, so no header a server
     * serves can make a wallet opt in. Without this floor a forged v2 header would be enough to make
     * every transaction the wallet produced unbroadcastable.
     */
    @Test
    public void testAForgedV2TipCannotOptInOnMainnet() {
        BlockHeader forged = header(V2_HEADER_HEX);
        Assertions.assertTrue(forged.isHeaderV2());
        Assertions.assertNull(AppServices.getUnifiedSigHashActivationHeight(Network.MAINNET),
                "Mainnet must have no activation height until the fork is scheduled");
        Assertions.assertFalse(AppServices.isUnifiedSigHashActive(Network.MAINNET, 900000, forged));
        Assertions.assertFalse(AppServices.isUnifiedSigHashActive(Network.MAINNET, Integer.MAX_VALUE, forged));
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
     * Only opt in when this wallet holds the keys.
     *
     * A device that has not implemented the opt-in either refuses the hash type outright, as the BitBox02
     * does, or ignores the PSBT's request and signs the legacy message while the PSBT declares the new
     * one, as Trezor and Ledger do. The second case is the dangerous one: the signature simply does not
     * verify, and the user is told the PSBT is invalid with nothing to act on. Opting in is optional, so
     * a device-backed wallet keeps signing the way it does today.
     */
    @Test
    public void testOnlyASoftwareWalletOptsIn() {
        Assertions.assertTrue(AppServices.canSignUnified(walletWith(KeystoreSource.SW_SEED)));
        for(KeystoreSource source : List.of(KeystoreSource.HW_USB, KeystoreSource.HW_AIRGAPPED, KeystoreSource.SW_WATCH)) {
            Assertions.assertFalse(AppServices.canSignUnified(walletWith(source)),
                    source + " cannot be relied on to produce an opted-in signature");
        }
    }

    /**
     * One external cosigner in a multisig is enough to break the transaction, so every keystore has to
     * qualify, not just one.
     */
    @Test
    public void testAMixedMultisigDoesNotOptIn() {
        Assertions.assertFalse(AppServices.canSignUnified(walletWith(KeystoreSource.SW_SEED, KeystoreSource.HW_USB)));
        Assertions.assertTrue(AppServices.canSignUnified(walletWith(KeystoreSource.SW_SEED, KeystoreSource.SW_SEED)));
        Assertions.assertFalse(AppServices.canSignUnified(walletWith()), "A wallet with no keystores cannot sign");
        Assertions.assertFalse(AppServices.canSignUnified(null));
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
        Assertions.assertEquals(150027, AppServices.getUnifiedSigHashActivationHeight(Network.TESTNET4),
                "Update the provenance comment alongside this value");
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
