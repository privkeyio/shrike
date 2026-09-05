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
import com.sparrowwallet.drongo.wallet.FinalizingPSBTWallet;
import com.sparrowwallet.drongo.wallet.Keystore;
import com.sparrowwallet.drongo.wallet.Wallet;
import com.sparrowwallet.drongo.wallet.WalletNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Set;

/**
 * Which keys the wallet will stand behind for an input, which is the whole of what the replay protection label rests on.
 *
 * A signature counts as an opt-in only where a key from here made it. Everything an input carries about its keys was
 * written by whoever wrote the input, so trusting any of that would answer "did someone sign something", which anyone
 * handing over a PSBT can arrange with a key of their own.
 */
public class TrustedKeysTest {
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

    private PSBT lastPsbt;

    /** A PSBT spending the given output, which is what the wallet is asked to vouch for. */
    private PSBTInput inputSpending(Script utxoScript) {
        Transaction transaction = new Transaction();
        transaction.setVersion(2);
        transaction.addInput(Sha256Hash.ZERO_HASH, 0, new Script(new byte[0]));
        transaction.addOutput(90_000L, wallet.getOutputScript(receiveNode));

        PSBT psbt = new PSBT(transaction);
        lastPsbt = psbt;
        PSBTInput psbtInput = psbt.getPsbtInputs().get(0);
        psbtInput.setWitnessUtxo(new TransactionOutput(null, 100_000L, utxoScript.getProgram()));
        psbtInput.setSigHash(SigHash.ALL);

        return psbtInput;
    }

    @Test
    public void the_wallet_vouches_for_its_own_node() {
        Set<ECKey> keys = AppServices.trustedKeys(inputSpending(wallet.getOutputScript(receiveNode)), wallet, receiveNode);

        Assertions.assertEquals(1, keys.size());
        Assertions.assertTrue(keys.contains(ECKey.fromPublicOnly(receiveNode.getPubKey())),
                "the key the wallet derives for this node is what it stands behind");
    }

    /**
     * The spent output has to be the one this wallet derives. Everything the message is built from comes out of the
     * PSBT, the amount included, so an input whose output the wallet did not derive is one where a stranger chose the
     * message, and a signature over a message a stranger chose says nothing about the transaction being sent.
     */
    @Test
    public void and_for_nothing_where_the_spent_output_is_not_the_one_it_derives() {
        WalletNode otherNode = wallet.getNode(KeyPurpose.CHANGE).getChildren().iterator().next();
        Script otherScript = wallet.getOutputScript(otherNode);

        Assertions.assertTrue(AppServices.trustedKeys(inputSpending(otherScript), wallet, receiveNode).isEmpty(),
                "the node and the spent output disagree, so there is nothing to vouch for");
    }

    @Test
    public void nor_where_there_is_no_wallet_or_no_node() {
        PSBTInput psbtInput = inputSpending(wallet.getOutputScript(receiveNode));

        Assertions.assertTrue(AppServices.trustedKeys(psbtInput, null, receiveNode).isEmpty());
        Assertions.assertTrue(AppServices.trustedKeys(psbtInput, wallet, null).isEmpty());
    }

    /**
     * Taproot, where the key that signs is not the key the wallet derives.
     *
     * A key path signature verifies against the tweaked output key, so vouching for the untweaked one would verify
     * nothing and report every taproot spend as unprotected. The output key is taken from the script this wallet
     * derived, which is the same script the check above just matched.
     */
    @Test
    public void a_taproot_wallet_vouches_for_the_output_key() throws Exception {
        Wallet taproot = new Wallet();
        taproot.setPolicyType(PolicyType.SINGLE_HD);
        taproot.setScriptType(ScriptType.P2TR);
        DeterministicSeed seed = new DeterministicSeed(
                "absent essay fox snake vast pumpkin height crouch silent bulb excuse razor", "", 0, DeterministicSeed.Type.BIP39);
        taproot.getKeystores().add(Keystore.fromSeed(seed, PolicyType.SINGLE_HD, ScriptType.P2TR.getDefaultDerivation()));
        taproot.setDefaultPolicy(Policy.getPolicy(PolicyType.SINGLE_HD, ScriptType.P2TR, taproot.getKeystores(), null));
        taproot.getNode(KeyPurpose.RECEIVE);
        WalletNode node = taproot.getNode(KeyPurpose.RECEIVE).getChildren().iterator().next();
        Script script = taproot.getOutputScript(node);

        Transaction transaction = new Transaction();
        transaction.addInput(Sha256Hash.ZERO_HASH, 0, new Script(new byte[0]));
        transaction.addOutput(90_000L, script);
        PSBT psbt = new PSBT(transaction);
        PSBTInput psbtInput = psbt.getPsbtInputs().get(0);
        psbtInput.setWitnessUtxo(new TransactionOutput(null, 100_000L, script.getProgram()));

        Set<ECKey> keys = AppServices.trustedKeys(psbtInput, taproot, node);
        Assertions.assertEquals(1, keys.size());
        Assertions.assertEquals(ScriptType.P2TR.getPublicKeyFromScript(script), keys.iterator().next(),
                "the key a taproot spend signs against is the one in the output, not the one it was derived from");
        Assertions.assertNotEquals(ECKey.fromPublicOnly(node.getPubKey()), keys.iterator().next(),
                "the untweaked key would verify nothing");
    }

    /** A quorum vouches for every key in it, because any of them may be the one that signed. */
    @Test
    public void a_multisig_wallet_vouches_for_the_whole_quorum() throws Exception {
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
        quorum.setDefaultPolicy(Policy.getPolicy(PolicyType.MULTI_HD, ScriptType.P2WSH, quorum.getKeystores(), 2));
        quorum.getNode(KeyPurpose.RECEIVE);
        WalletNode node = quorum.getNode(KeyPurpose.RECEIVE).getChildren().iterator().next();
        Script script = quorum.getOutputScript(node);

        Transaction transaction = new Transaction();
        transaction.addInput(Sha256Hash.ZERO_HASH, 0, new Script(new byte[0]));
        transaction.addOutput(90_000L, script);
        PSBT psbt = new PSBT(transaction);
        PSBTInput psbtInput = psbt.getPsbtInputs().get(0);
        psbtInput.setWitnessUtxo(new TransactionOutput(null, 100_000L, script.getProgram()));

        Set<ECKey> keys = AppServices.trustedKeys(psbtInput, quorum, node);
        Assertions.assertEquals(3, keys.size(), "every key in the quorum can be the one that signed");
        Assertions.assertTrue(keys.containsAll(node.getPubKeys()));
    }

    /**
     * A wallet built out of the PSBT is the case that matters most, because it looks like a wallet and vouches for
     * nothing: it answers for the script with the PSBT's own output and for the keys with the ones the PSBT names, so
     * every check would be the file agreeing with itself.
     */
    @Test
    public void and_never_for_a_wallet_built_out_of_the_psbt() throws Exception {
        PSBTInput psbtInput = inputSpending(wallet.getOutputScript(receiveNode));
        //This wallet only exists for a PSBT that is already signed, which is exactly when the label is drawn for one
        //that arrived from elsewhere
        wallet.sign(lastPsbt);
        Wallet finalizing = new FinalizingPSBTWallet(lastPsbt);

        //The node this wallet itself answers with, which is what the counting passes in. Using the real wallet's node
        //instead would be refused for disagreeing about the script, and would prove nothing about this wallet.
        WalletNode finalizingNode = finalizing.getSigningNodes(lastPsbt).get(psbtInput);
        Assertions.assertNotNull(finalizingNode, "this wallet must claim the input, or the case is not being exercised");
        Assertions.assertEquals(finalizing.getOutputScript(finalizingNode), psbtInput.getUtxo().getScript(),
                "its script check is the file agreeing with itself, which is what makes it worthless");

        Assertions.assertTrue(AppServices.trustedKeys(psbtInput, finalizing, finalizingNode).isEmpty(),
                "a wallet that reads its answers out of the PSBT must vouch for nothing");
    }
}
