package com.sparrowwallet.sparrow;

import com.sparrowwallet.drongo.KeyPurpose;
import com.sparrowwallet.drongo.Network;
import com.sparrowwallet.drongo.crypto.ECKey;
import com.sparrowwallet.drongo.policy.Policy;
import com.sparrowwallet.drongo.policy.PolicyType;
import com.sparrowwallet.drongo.protocol.Script;
import com.sparrowwallet.drongo.protocol.ScriptType;
import com.sparrowwallet.drongo.protocol.Sha256Hash;
import com.sparrowwallet.drongo.protocol.SigHash;
import com.sparrowwallet.drongo.protocol.Transaction;
import com.sparrowwallet.drongo.protocol.TransactionOutput;
import com.sparrowwallet.drongo.psbt.PSBT;
import com.sparrowwallet.drongo.psbt.PSBTInput;
import com.sparrowwallet.drongo.wallet.DeterministicSeed;
import com.sparrowwallet.drongo.wallet.Keystore;
import com.sparrowwallet.drongo.wallet.KeystoreSource;
import com.sparrowwallet.drongo.wallet.Wallet;
import com.sparrowwallet.drongo.policy.Policy;
import com.sparrowwallet.drongo.wallet.WalletNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Set;

/**
 * A signer that cannot produce the opt-in still has to be able to take its turn.
 *
 * Krux has no USB mode, so psbtForDevice cannot reach it and an opted-in PSBT is one it refuses outright. In a 2-of-3
 * whose marked signers are exactly the threshold, losing one of them then leaves the wallet unspendable.
 */
public class PsbtForExportTest {
    /** Krux's own rule, from its check_sighash: safe_sighash = {None, DEFAULT, ALL}. */
    private static final Set<SigHash> KRUX_ACCEPTS = Set.of(SigHash.DEFAULT, SigHash.ALL);

    private Wallet wallet;
    private WalletNode receiveNode;

    @BeforeEach
    public void setUp() throws Exception {
        Network.set(Network.MAINNET);
        wallet = new Wallet();
        wallet.setPolicyType(PolicyType.SINGLE_HD);
        wallet.setScriptType(ScriptType.P2WPKH);
        DeterministicSeed seed = new DeterministicSeed(
                "absent essay fox snake vast pumpkin height crouch silent bulb excuse razor", "", 0, DeterministicSeed.Type.BIP39);
        wallet.getKeystores().add(Keystore.fromSeed(seed, PolicyType.SINGLE_HD, ScriptType.P2WPKH.getDefaultDerivation()));
        wallet.setDefaultPolicy(Policy.getPolicy(PolicyType.SINGLE_HD, ScriptType.P2WPKH, wallet.getKeystores(), null));
        wallet.getNode(KeyPurpose.RECEIVE);
        receiveNode = wallet.getNode(KeyPurpose.RECEIVE).getChildren().iterator().next();
    }

    @AfterEach
    public void tearDown() {
        Network.set(null);
    }

    /** The signer that cannot produce the opt-in, alongside the one that can. */
    private void addUnmarkedSigner() {
        Keystore unmarked = new Keystore();
        unmarked.setSource(KeystoreSource.HW_AIRGAPPED);
        unmarked.setLabel("Krux");
        wallet.getKeystores().add(unmarked);
    }

    private PSBT optedInPsbt() {
        Script spk = wallet.getOutputScript(receiveNode);
        Transaction transaction = new Transaction();
        transaction.setVersion(2);
        transaction.addInput(Sha256Hash.ZERO_HASH, 0, new Script(new byte[0]));
        transaction.addOutput(90_000L, spk);

        PSBT psbt = new PSBT(transaction);
        PSBTInput psbtInput = psbt.getPsbtInputs().get(0);
        psbtInput.setWitnessUtxo(new TransactionOutput(null, 100_000L, spk.getProgram()));
        psbtInput.setSigHash(SigHash.UNIFIED_ALL);

        return psbt;
    }

    private void signOptedIn(PSBT psbt) throws Exception {
        ECKey key = wallet.getKeystores().getFirst().getKey(receiveNode);
        Assertions.assertTrue(psbt.getPsbtInputs().getFirst().sign(key), "the fixture must produce a signature");
    }

    private SigHash declared(PSBT psbt) {
        return psbt.getPsbtInputs().getFirst().getSigHash();
    }

    /**
     * Until one signature opts in, the declaration is the only thing asking for it.
     */
    @Test
    public void testTheOptInSurvivesUntilASignatureCarriesIt() {
        addUnmarkedSigner();
        PSBT psbt = optedInPsbt();

        Assertions.assertEquals(0, AppServices.signatureOptInCounts(psbt, wallet)[0], "nothing has signed yet");
        Assertions.assertEquals(SigHash.UNIFIED_ALL, declared(AppServices.psbtForExport(wallet, psbt)));
    }

    /**
     * The whole point: one opted-in signature makes the transaction unreplayable whatever the rest carry, so from
     * there the declaration can go and the signer that refused it can take its turn.
     */
    @Test
    public void testOnceProtectedTheExportIsOneKruxAccepts() throws Exception {
        addUnmarkedSigner();
        PSBT psbt = optedInPsbt();
        signOptedIn(psbt);

        Assertions.assertEquals(1, AppServices.signatureOptInCounts(psbt, wallet)[0], "the protection is banked");

        PSBT exported = AppServices.psbtForExport(wallet, psbt);
        Assertions.assertEquals(SigHash.ALL, declared(exported));
        Assertions.assertTrue(KRUX_ACCEPTS.contains(declared(exported)),
                "Krux refuses anything outside {None, DEFAULT, ALL}: " + declared(exported));
    }

    /**
     * The signature already made is untouched: it carries its own hash type and is what the protection rests on.
     */
    @Test
    public void testTheOptedInSignatureIsNotDisturbed() throws Exception {
        addUnmarkedSigner();
        PSBT psbt = optedInPsbt();
        signOptedIn(psbt);

        PSBT exported = AppServices.psbtForExport(wallet, psbt);
        Assertions.assertEquals(1, AppServices.signatureOptInCounts(exported, wallet)[0],
                "dropping the declaration must not drop the protection");
        Assertions.assertEquals(psbt.getPsbtInputs().getFirst().getPartialSignatures(),
                exported.getPsbtInputs().getFirst().getPartialSignatures());
    }

    /**
     * Nothing to work around where every signer can produce the opt-in, and dropping it there would cost those
     * signers the commitment to every spent amount for no reason.
     */
    @Test
    public void testAFullyMarkedWalletKeepsTheOptIn() throws Exception {
        PSBT psbt = optedInPsbt();
        signOptedIn(psbt);

        Assertions.assertEquals(SigHash.UNIFIED_ALL, declared(AppServices.psbtForExport(wallet, psbt)));
    }

    @Test
    public void testTheGivenPsbtIsNeverMutated() throws Exception {
        addUnmarkedSigner();
        PSBT psbt = optedInPsbt();
        signOptedIn(psbt);

        AppServices.psbtForExport(wallet, psbt);
        Assertions.assertEquals(SigHash.UNIFIED_ALL, declared(psbt), "the wallet still holds the opted-in one");
    }

    /**
     * Replay protection belongs to the transaction, not to each input: a single opted-in signature anywhere in it
     * fails to verify on the chain that did not fork, which takes the whole transaction with it. So an input that
     * has not been signed yet can have the declaration dropped alongside one that has.
     */
    @Test
    public void testOneSignedInputCoversTheOthers() throws Exception {
        addUnmarkedSigner();
        PSBT psbt = twoInputPsbt();
        ECKey key = wallet.getKeystores().getFirst().getKey(receiveNode);
        Assertions.assertTrue(psbt.getPsbtInputs().getFirst().sign(key), "only the first input is signed");

        Assertions.assertEquals(1, AppServices.signatureOptInCounts(psbt, wallet)[0]);

        PSBT exported = AppServices.psbtForExport(wallet, psbt);
        for(PSBTInput psbtInput : exported.getPsbtInputs()) {
            Assertions.assertEquals(SigHash.ALL, psbtInput.getSigHash(),
                    "every input, including the one still unsigned");
        }
    }

    /**
     * A transaction that never opted in has nothing to drop, whatever else is true of the wallet.
     */
    @Test
    public void testATransactionThatNeverOptedInIsUntouched() throws Exception {
        addUnmarkedSigner();
        PSBT psbt = optedInPsbt();
        psbt.getPsbtInputs().getFirst().setSigHash(SigHash.ALL);
        signOptedIn(psbt);

        Assertions.assertEquals(SigHash.ALL, declared(AppServices.psbtForExport(wallet, psbt)));
    }

    private PSBT twoInputPsbt() {
        Script spk = wallet.getOutputScript(receiveNode);
        Transaction transaction = new Transaction();
        transaction.setVersion(2);
        transaction.addInput(Sha256Hash.ZERO_HASH, 0, new Script(new byte[0]));
        transaction.addInput(Sha256Hash.ZERO_HASH, 1, new Script(new byte[0]));
        transaction.addOutput(180_000L, spk);

        PSBT psbt = new PSBT(transaction);
        for(PSBTInput psbtInput : psbt.getPsbtInputs()) {
            psbtInput.setWitnessUtxo(new TransactionOutput(null, 100_000L, spk.getProgram()));
            psbtInput.setSigHash(SigHash.UNIFIED_ALL);
        }

        return psbt;
    }

    /**
     * The shape this was reported against: a real 2-of-3 P2WSH, not a single-sig wallet with a keystore bolted on.
     *
     * signatureOptInCounts has to attribute the signature to the quorum before anything can be dropped. If it does
     * not, the export silently never downgrades and the fix does nothing for the wallet it was written for.
     */
    @Test
    public void testARealTwoOfThreeQuorum() throws Exception {
        String[] mnemonics = {
                "absent essay fox snake vast pumpkin height crouch silent bulb excuse razor",
                "sample vibrant sound quantum ripple hidden pluck raven mirror ocean fabric noodle",
                "vault cruise pistol trigger pilot scan hidden major fringe course fiber quiz"};

        Wallet quorum = new Wallet();
        quorum.setPolicyType(PolicyType.MULTI_HD);
        quorum.setScriptType(ScriptType.P2WSH);
        for(String mnemonic : mnemonics) {
            DeterministicSeed seed = new DeterministicSeed(mnemonic, "", 0, DeterministicSeed.Type.BIP39);
            quorum.getKeystores().add(Keystore.fromSeed(seed, PolicyType.MULTI_HD, ScriptType.P2WSH.getDefaultDerivation()));
        }
        //Krux: in the quorum, and not one of the signers that can produce the opt-in
        quorum.getKeystores().getLast().setSource(KeystoreSource.HW_AIRGAPPED);
        quorum.getKeystores().getLast().setLabel("Krux");
        quorum.setDefaultPolicy(Policy.getPolicy(PolicyType.MULTI_HD, ScriptType.P2WSH, quorum.getKeystores(), 2));
        quorum.getNode(KeyPurpose.RECEIVE);
        WalletNode node = quorum.getNode(KeyPurpose.RECEIVE).getChildren().iterator().next();
        Script spk = quorum.getOutputScript(node);

        Transaction transaction = new Transaction();
        transaction.setVersion(2);
        transaction.addInput(Sha256Hash.ZERO_HASH, 0, new Script(new byte[0]));
        transaction.addOutput(90_000L, spk);
        PSBT psbt = new PSBT(transaction);
        PSBTInput psbtInput = psbt.getPsbtInputs().getFirst();
        psbtInput.setWitnessUtxo(new TransactionOutput(null, 100_000L, spk.getProgram()));
        psbtInput.setWitnessScript(ScriptType.MULTISIG.getOutputScript(
                quorum.getDefaultPolicy().getNumSignaturesRequired(), node.getPubKeys()));
        psbtInput.setSigHash(SigHash.UNIFIED_ALL);

        Assertions.assertEquals(SigHash.UNIFIED_ALL, declared(AppServices.psbtForExport(quorum, psbt)),
                "nothing has signed, so the declaration is all that asks for the opt-in");

        //SeedSigner signs, and only SeedSigner
        Assertions.assertTrue(psbtInput.sign(quorum.getKeystores().getFirst().getKey(node)),
                "the fixture must produce a signature");
        Assertions.assertEquals(1, AppServices.signatureOptInCounts(psbt, quorum)[0],
                "the quorum must recognise its own signature, or nothing downgrades");

        PSBT exported = AppServices.psbtForExport(quorum, psbt);
        Assertions.assertEquals(SigHash.ALL, declared(exported), "Krux can now take its turn");
        Assertions.assertEquals(1, AppServices.signatureOptInCounts(exported, quorum)[0],
                "and the protection the first signature bought is still there");
    }

    /**
     * What the export dialog says, at the moment someone is about to carry this to a device. The send screen says it
     * too, on hover, while the transaction is being built.
     */
    @Test
    public void testTheExportSaysWhichOfTheTwoItIs() throws Exception {
        addUnmarkedSigner();
        PSBT psbt = optedInPsbt();

        String before = AppServices.exportDescription(wallet, psbt);
        Assertions.assertNotNull(before);
        Assertions.assertTrue(before.contains("Krux"), before);
        Assertions.assertTrue(before.contains("cannot produce"), before);

        signOptedIn(psbt);
        String after = AppServices.exportDescription(wallet, AppServices.psbtForExport(wallet, psbt));
        Assertions.assertNotNull(after);
        Assertions.assertTrue(after.contains("Any signer can sign"), after);
        Assertions.assertTrue(after.contains("Krux"), after);
        Assertions.assertNotEquals(before, after, "the same button now produces a different export");
    }

    /** Nothing to explain where every signer can produce the opt-in. */
    @Test
    public void testAFullyMarkedWalletIsToldNothing() throws Exception {
        PSBT psbt = optedInPsbt();
        Assertions.assertNull(AppServices.exportDescription(wallet, psbt));
    }

    @Test
    public void testNoWalletAndNoPsbtAreNotErrors() {
        Assertions.assertNull(AppServices.psbtForExport(wallet, null));
        PSBT psbt = optedInPsbt();
        Assertions.assertSame(psbt, AppServices.psbtForExport(null, psbt));
    }
}
