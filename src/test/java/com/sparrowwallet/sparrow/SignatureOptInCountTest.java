package com.sparrowwallet.sparrow;

import com.sparrowwallet.drongo.Utils;
import com.sparrowwallet.drongo.crypto.ECKey;
import com.sparrowwallet.drongo.policy.PolicyType;
import com.sparrowwallet.drongo.protocol.Script;
import com.sparrowwallet.drongo.protocol.ScriptType;
import com.sparrowwallet.drongo.protocol.Sha256Hash;
import com.sparrowwallet.drongo.protocol.SigHash;
import com.sparrowwallet.drongo.protocol.Transaction;
import com.sparrowwallet.drongo.protocol.TransactionOutput;
import com.sparrowwallet.drongo.protocol.TransactionSignature;
import com.sparrowwallet.drongo.protocol.TransactionWitness;
import com.sparrowwallet.drongo.psbt.PSBT;
import com.sparrowwallet.drongo.psbt.PSBTInput;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * What the replay protection label is counted from, at the surface that feeds it.
 *
 * The two numbers are claimed in opposite directions and so are counted differently. The first says a protection is
 * there, and only a signature that verifies can say that. The second is the denominator, and leaving an unverifiable
 * signature out of it would turn "one of two signatures opted in" into "every signature opted in".
 */
public class SignatureOptInCountTest {
    private static final String PRIVATE_KEY = "11".repeat(32);
    private static final long VALUE = 100_000_000L;

    private ECKey key() {
        return ECKey.fromPrivate(Utils.hexToBytes(PRIVATE_KEY));
    }

    private PSBT signed(byte sigHashType) {
        ECKey outputKey = ScriptType.P2WPKH.getOutputKey(PolicyType.SINGLE_HD, key());
        Script spk = ScriptType.P2WPKH.getOutputScript(PolicyType.SINGLE_HD, key());

        Transaction transaction = new Transaction();
        transaction.setVersion(2);
        transaction.addInput(Sha256Hash.ZERO_HASH, 0, new Script(new byte[0]));
        transaction.addOutput(VALUE - 10_000, spk);

        PSBT psbt = new PSBT(transaction);
        PSBTInput psbtInput = psbt.getPsbtInputs().get(0);
        psbtInput.setWitnessUtxo(new TransactionOutput(null, VALUE, spk.getProgram()));
        psbtInput.setSigHash(SigHash.fromByte(sigHashType));
        psbtInput.sign(outputKey);

        return psbt;
    }

    /** A 65 byte push ending in a byte that reads as an opt-in, which a taproot control block is half the time. */
    /** What a caller vouches for here: the one key this fixture signs with. */
    private List<ECKey> trusted() {
        return List.of(ECKey.fromPublicOnly(ScriptType.P2WPKH.getOutputKey(PolicyType.SINGLE_HD, key())));
    }

    private byte[] junk() {
        byte[] push = new byte[65];
        Arrays.fill(push, (byte)0x11);
        push[64] = (byte)(SigHash.UNIFIED_FLAG | SigHash.ALL.byteValue());
        return push;
    }

    private void finaliseWith(PSBT psbt, byte[]... extraPushes) {
        PSBTInput psbtInput = psbt.getPsbtInputs().get(0);
        ECKey outputKey = ScriptType.P2WPKH.getOutputKey(PolicyType.SINGLE_HD, key());
        TransactionSignature signature = psbtInput.getPartialSignature(ECKey.fromPublicOnly(outputKey));

        List<byte[]> pushes = new ArrayList<>();
        pushes.add(signature.encodeToBitcoin());
        pushes.add(outputKey.getPubKey());
        pushes.addAll(Arrays.asList(extraPushes));
        psbtInput.setFinalScriptWitness(new TransactionWitness(null, pushes));
    }

    @Test
    public void a_signature_that_opts_in_is_counted() {
        PSBT psbt = signed((byte)(SigHash.UNIFIED_FLAG | SigHash.ALL.byteValue()));

        Assertions.assertEquals(1, AppServices.signatureOptInCounts(psbt, trusted())[0]);
        Assertions.assertEquals(1, AppServices.signatureOptInCounts(psbt, trusted())[2]);
    }

    @Test
    public void one_that_does_not_is_not() {
        PSBT psbt = signed(SigHash.ALL.byteValue());

        Assertions.assertEquals(0, AppServices.signatureOptInCounts(psbt, trusted())[0]);
        Assertions.assertEquals(1, AppServices.signatureOptInCounts(psbt, trusted())[2]);
    }

    /**
     * The case this is here for. The witness carries a real signature that does not opt in, next to a push that is not a
     * signature and ends in a byte that says it does. Counted without checking, this transaction reports itself replay
     * protected when nothing about it is.
     */
    @Test
    public void a_push_that_only_looks_like_a_signature_cannot_claim_the_protection() {
        PSBT psbt = signed(SigHash.ALL.byteValue());
        finaliseWith(psbt, junk());

        int[] counts = AppServices.signatureOptInCounts(psbt, trusted());
        Assertions.assertEquals(0, counts[0], "a push that verifies against nothing claimed the protection");
        Assertions.assertEquals(2, counts[2], "the denominator counts what is present, so the claim reads as incomplete");
    }

    /**
     * And the other direction: a real opt-in beside the same push is still reported, so the check refuses the forgery
     * rather than refusing everything.
     */
    @Test
    public void a_real_opt_in_beside_it_is_still_counted() {
        PSBT psbt = signed((byte)(SigHash.UNIFIED_FLAG | SigHash.ALL.byteValue()));
        finaliseWith(psbt, junk());

        int[] counts = AppServices.signatureOptInCounts(psbt, trusted());
        Assertions.assertEquals(1, counts[0]);
        Assertions.assertEquals(2, counts[2], "the push is still counted, so one of two opted in rather than all of them");
    }

    /**
     * The two reasons a transaction does not opt in, which the numbers have to keep apart because they read very
     * differently to someone deciding whether to broadcast.
     *
     * A stock signer produces a signature this wallet checks and finds is simply the old kind. A push that is not a
     * signature is something this wallet cannot check at all. Reporting the second as though it were the first would
     * tell a user their own signature could not be confirmed, which is both wrong and alarming.
     */
    @Test
    public void a_signature_checked_and_found_legacy_is_not_the_same_as_one_that_could_not_be_checked() {
        int[] stockSigner = AppServices.signatureOptInCounts(signed(SigHash.ALL.byteValue()), trusted());
        Assertions.assertArrayEquals(new int[] {0, 1, 1}, stockSigner,
                "the signature was checked, so nothing here is unknown");

        PSBT withJunk = signed(SigHash.ALL.byteValue());
        finaliseWith(withJunk, junk());
        int[] somethingUnreadable = AppServices.signatureOptInCounts(withJunk, trusted());
        Assertions.assertArrayEquals(new int[] {0, 1, 2}, somethingUnreadable,
                "one of the two could not be checked, and the count has to show that");
    }

    /**
     * The risk count is read the other way round, from everything that looks like a signature, because naming a risk
     * that is not there costs a warning and missing one costs the warning that mattered.
     */
    @Test
    public void the_liftable_count_still_reads_every_push() {
        PSBT psbt = signed((byte)(SigHash.UNIFIED_FLAG | SigHash.ALL.byteValue()));
        byte[] legacyAnyoneCanPay = new byte[65];
        Arrays.fill(legacyAnyoneCanPay, (byte)0x11);
        legacyAnyoneCanPay[64] = (byte)(SigHash.ANYONECANPAY.value | SigHash.ALL.byteValue());
        finaliseWith(psbt, legacyAnyoneCanPay);

        Assertions.assertEquals(1, AppServices.liftableSignatureCount(psbt),
                "a push that could be a liftable signature must still be reported");
    }
}
