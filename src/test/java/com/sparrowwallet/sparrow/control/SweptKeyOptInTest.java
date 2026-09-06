package com.sparrowwallet.sparrow.control;

import com.sparrowwallet.drongo.Network;
import com.sparrowwallet.sparrow.AppServices;
import com.sparrowwallet.drongo.crypto.ECKey;
import com.sparrowwallet.drongo.policy.PolicyType;
import com.sparrowwallet.drongo.protocol.*;
import com.sparrowwallet.drongo.psbt.PSBT;
import com.sparrowwallet.drongo.psbt.PSBTInput;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * A swept key signs in process, from a key the sweep dialog holds, so there is no signer to declare for and the
 * chain is the whole question. The dialog builds its own PSBT rather than going through createPSBT, which is why the
 * shape it builds is pinned here: it was the one signing path in the application that never opted in.
 */
public class SweptKeyOptInTest {
    @AfterEach
    public void tearDown() {
        Network.set(null);
    }

    @Test
    public void testASweptKeySignsTheOptInWhenTheChainHasIt() throws Exception {
        Network.set(Network.MAINNET);
        ECKey privKey = ECKey.fromPrivate(new java.math.BigInteger(1, com.sparrowwallet.drongo.Utils.hexToBytes("a1b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3d4e5f60718293a4b5c6d7e8f90")), true);
        PSBT psbt = sweepPsbtFor(privKey, true);

        PSBTInput psbtInput = psbt.getPsbtInputs().getFirst();
        Assertions.assertEquals(SigHash.UNIFIED_ALL, psbtInput.getSigHash());
        Assertions.assertTrue(psbtInput.sign(ScriptType.P2WPKH.getOutputKey(PolicyType.SINGLE_HD, privKey)));

        //The swept key is the whole of what there is to vouch for, since it belongs to no wallet. Read through the
        //input itself rather than the wallet path, which has no wallet here to ask.
        java.util.List<com.sparrowwallet.drongo.protocol.TransactionSignature> verified =
                psbtInput.getVerifiedSignatures(java.util.List.of(ScriptType.P2WPKH.getOutputKey(PolicyType.SINGLE_HD, privKey)));
        int[] counts = new int[] {(int)verified.stream().filter(s -> (s.sighashFlags & SigHash.UNIFIED_FLAG) != 0).count(),
                verified.size(), psbtInput.getSignatures().size()};
        Assertions.assertEquals(1, counts[2], "the key signed");
        Assertions.assertEquals(1, counts[0], "and it opted in");

        //The signature has to verify against the digest its own byte names, or the node would refuse it
        psbt.verifySignatures();
        Assertions.assertEquals(0, AppServices.liftableSignatureCount(psbt));
    }

    /** Left alone before activation, so a sweep signs exactly as it did where the fork is not live. */
    @Test
    public void testASweptKeySignsTheOldWayWhereTheChainHasNot() throws Exception {
        Network.set(Network.MAINNET);
        ECKey privKey = ECKey.fromPrivate(new java.math.BigInteger(1, com.sparrowwallet.drongo.Utils.hexToBytes("a1b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3d4e5f60718293a4b5c6d7e8f90")), true);
        PSBT psbt = sweepPsbtFor(privKey, false);

        PSBTInput psbtInput = psbt.getPsbtInputs().getFirst();
        Assertions.assertNull(psbtInput.getSigHash(), "nothing declared, which signs the default");
        Assertions.assertTrue(psbtInput.sign(ScriptType.P2WPKH.getOutputKey(PolicyType.SINGLE_HD, privKey)));

        Assertions.assertTrue(psbt.getPsbtInputs().getFirst()
                .getVerifiedSignatures(java.util.List.of(ScriptType.P2WPKH.getOutputKey(PolicyType.SINGLE_HD, privKey)))
                .stream().noneMatch(s -> (s.sighashFlags & SigHash.UNIFIED_FLAG) != 0), "no signature opted in");
        psbt.verifySignatures();
    }

    /** Built through the dialog's own seam, so removing the opt-in from it fails these. */
    private PSBT sweepPsbtFor(ECKey privKey, boolean optedIn) {
        Script spk = ScriptType.P2WPKH.getOutputScript(PolicyType.SINGLE_HD, privKey);

        Transaction transaction = new Transaction();
        transaction.setVersion(2);
        transaction.addInput(Sha256Hash.wrap("0000000000000000000000000000000000000000000000000000000000000001"), 0, new Script(new byte[0]));
        transaction.addOutput(90000L, spk);

        return PrivateKeySweepDialog.sweepPsbt(transaction,
                java.util.List.of(new TransactionOutput(null, 100000L, spk.getProgram())), optedIn);
    }
}
