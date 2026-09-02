package com.sparrowwallet.sparrow;

import com.sparrowwallet.drongo.KeyPurpose;
import com.sparrowwallet.drongo.Network;
import com.sparrowwallet.drongo.policy.Policy;
import com.sparrowwallet.drongo.policy.PolicyType;
import com.sparrowwallet.drongo.protocol.*;
import com.sparrowwallet.drongo.psbt.PSBT;
import com.sparrowwallet.drongo.psbt.PSBTInput;
import com.sparrowwallet.drongo.psbt.PSBTSignatureException;
import com.sparrowwallet.drongo.wallet.DeterministicSeed;
import com.sparrowwallet.drongo.wallet.Keystore;
import com.sparrowwallet.drongo.wallet.Wallet;
import com.sparrowwallet.drongo.wallet.WalletNode;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * A witness carrying one opted-in signature and one legacy signature is the shape the fork is built to
 * produce: one marked signer is enough, and an unmarked cosigner is handed the base type so it can sign
 * alongside. Verification has to check each signature against the type that signature names, not against
 * the one the input happens to declare.
 */
public class MixedWitnessCombineTest {
    @Test
    public void testAMixedWitnessVerifies() throws Exception {
        Network.set(Network.MAINNET);
        PSBT psbt = mixedWitnessPsbt(SigHash.UNIFIED_ALL, SigHash.ALL);

        Assertions.assertEquals(2, AppServices.signatureOptInCounts(psbt)[1], "both keys must have signed");
        Assertions.assertEquals(1, AppServices.signatureOptInCounts(psbt)[0], "one of them opted in");

        //The combine path and the parse path both run this. It must not reject a witness the finalise
        //path is happy to build.
        psbt.verifySignatures();
    }

    @Test
    public void testAMixedWitnessVerifiesWhateverOrderItWasSignedIn() throws Exception {
        Network.set(Network.MAINNET);
        PSBT psbt = mixedWitnessPsbt(SigHash.ALL, SigHash.UNIFIED_ALL);

        Assertions.assertEquals(1, AppServices.signatureOptInCounts(psbt)[0], "one of them opted in");
        psbt.verifySignatures();
    }

    /**
     * An unmarked device is handed a copy asking for the base type, so the PSBT it returns declares the base type.
     * Combining that back must not leave the input asking for the base type, or every signer that has not signed yet
     * is asked for the legacy digest and the transaction finishes with no protection at all.
     */
    @Test
    public void testCombiningAnUnmarkedDevicesReplyKeepsTheOptIn() throws Exception {
        Network.set(Network.MAINNET);
        PSBT psbt = unsignedPsbt(SigHash.UNIFIED_ALL);

        PSBT forUnmarked = psbt.copy();
        for(PSBTInput psbtInput : forUnmarked.getPsbtInputs()) {
            psbtInput.setSigHash(SigHash.ALL);
        }

        psbt.combine(forUnmarked);

        for(PSBTInput psbtInput : psbt.getPsbtInputs()) {
            Assertions.assertEquals(SigHash.UNIFIED_ALL, psbtInput.getSigHash(),
                    "the opt-in must survive a reply from a device that was asked for the base type");
        }
    }

    /** Combining a genuine change of type, rather than the downgrade above, still takes effect. */
    @Test
    public void testCombiningStillAdoptsADifferentType() throws Exception {
        Network.set(Network.MAINNET);
        PSBT psbt = unsignedPsbt(SigHash.UNIFIED_ALL);

        PSBT other = psbt.copy();
        for(PSBTInput psbtInput : other.getPsbtInputs()) {
            psbtInput.setSigHash(SigHash.UNIFIED_NONE);
        }

        psbt.combine(other);

        for(PSBTInput psbtInput : psbt.getPsbtInputs()) {
            Assertions.assertEquals(SigHash.UNIFIED_NONE, psbtInput.getSigHash());
        }
    }

    /**
     * Checking each signature against the type it names must not become "any type verifies".
     *
     * The opt-in bit is the only thing a signer may differ on, because it is the only thing this wallet varies per
     * signer. A signature over SIGHASH_NONE commits to no outputs at all, so accepting one where ALL was asked for
     * would finalise a transaction anyone could redirect.
     */
    @Test
    public void testASignatureOverATypeNobodyAskedForIsRefused() throws Exception {
        Network.set(Network.MAINNET);
        PSBT psbt = mixedWitnessPsbt(SigHash.ALL, SigHash.NONE);

        //Both keys signed, over two different output types
        Assertions.assertEquals(2, AppServices.signatureOptInCounts(psbt)[1]);

        Assertions.assertThrows(PSBTSignatureException.class, psbt::verifySignatures,
                "a signature over a type the input never asked for must not verify");
    }

    /** The opt-in still travels with the declared type, which is the whole point of the per signature check. */
    @Test
    public void testTheOptInBitIsTheOneThingASignerMayDifferOn() throws Exception {
        Network.set(Network.MAINNET);
        //UNIFIED_ALL declared, one signature 0x21 and one 0x01: the shape psbtForDevice produces
        mixedWitnessPsbt(SigHash.UNIFIED_ALL, SigHash.ALL).verifySignatures();
        mixedWitnessPsbt(SigHash.ALL, SigHash.UNIFIED_ALL).verifySignatures();
    }

    /**
     * A signer that answers with a different output type than it was handed must not clear the opt-in either. A
     * taproot signer returning DEFAULT where ALL was asked for is the ordinary case, and matching only on the exact
     * base type let that one through.
     */
    @Test
    public void testAnUnrelatedTypeDoesNotClearTheOptIn() throws Exception {
        Network.set(Network.MAINNET);
        PSBT psbt = unsignedPsbt(SigHash.UNIFIED_ALL);

        PSBT reply = psbt.copy();
        for(PSBTInput psbtInput : reply.getPsbtInputs()) {
            psbtInput.setSigHash(SigHash.DEFAULT);
        }

        psbt.combine(reply);

        for(PSBTInput psbtInput : psbt.getPsbtInputs()) {
            Assertions.assertEquals(SigHash.UNIFIED_ALL, psbtInput.getSigHash(),
                    "the opt-in survives a reply declaring any type without the bit");
        }
    }

    /** A real 2-of-2 P2WSH declaring one hash type, with nothing signed yet. */
    private PSBT unsignedPsbt(SigHash declared) throws Exception {
        Wallet wallet = twoOfTwo();
        PSBT psbt = psbtFor(wallet);
        psbt.getPsbtInputs().getFirst().setSigHash(declared);
        return psbt;
    }

    /** A real 2-of-2 P2WSH signed once per hash type, the way per-device PSBTs combine. */
    private PSBT mixedWitnessPsbt(SigHash firstType, SigHash secondType) throws Exception {
        Wallet wallet = twoOfTwo();
        PSBT psbt = psbtFor(wallet);
        PSBTInput psbtInput = psbt.getPsbtInputs().getFirst();

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

        return psbt;
    }

    private Wallet twoOfTwo() throws Exception {
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

        return wallet;
    }

    private PSBT psbtFor(Wallet wallet) {
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

        return psbt;
    }
}
