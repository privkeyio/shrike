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
import com.sparrowwallet.drongo.protocol.VarInt;
import com.sparrowwallet.drongo.psbt.PSBT;
import com.sparrowwallet.drongo.psbt.PSBTInput;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;

/**
 * A transaction signed only by a taproot script path is a signed transaction.
 *
 * The whole point of this reading is that a declaration is not a check. An input carrying only script path signatures
 * used to arrive with nothing a wallet could see: they were dropped at parse, so it had no partial signatures, no key
 * path signature and nothing finalised, which is what an unsigned input looks like. The label then said what the
 * transaction would be, taken from a hash type the file wrote for itself, over a transaction that was already signed
 * and whose signatures nobody had looked at.
 *
 * Built by writing the entry into a real PSBT's bytes and handing those to the real parser, so what is checked is the
 * path a PSBT from another wallet actually takes.
 */
public class TapScriptSignedTest {
    private static final String PRIVATE_KEY = "11".repeat(32);
    private static final long VALUE = 100_000_000L;

    public static PSBT declaredUnifiedPsbt() {
        ECKey key = ECKey.fromPrivate(Utils.hexToBytes(PRIVATE_KEY));
        //Taproot, because a script path signature belongs to a taproot input and is ignored anywhere else
        Script spk = ScriptType.P2TR.getOutputScript(PolicyType.SINGLE_HD, key);

        Transaction transaction = new Transaction();
        transaction.setVersion(2);
        transaction.addInput(Sha256Hash.ZERO_HASH, 0, new Script(new byte[0]));
        transaction.addOutput(VALUE - 10_000, spk);

        PSBT psbt = new PSBT(transaction);
        PSBTInput psbtInput = psbt.getPsbtInputs().get(0);
        psbtInput.setWitnessUtxo(new TransactionOutput(null, VALUE, spk.getProgram()));
        psbtInput.setSigHash(SigHash.fromByte((byte)(SigHash.UNIFIED_FLAG | SigHash.ALL.byteValue())));

        return psbt;
    }

    /**
     * The entry, written into the first input's map. The global map ends at the first zero length key after the magic,
     * and the first input's map begins there, so an entry inserted at that point belongs to input zero.
     */
    public static byte[] withTapScriptSignature(byte[] serialized, byte sigHashType) {
        byte[] signature = new byte[65];
        Arrays.fill(signature, (byte)0x33);
        signature[64] = sigHashType;

        return withTapScriptSignature(serialized, signature);
    }

    public static byte[] withTapScriptSignature(byte[] serialized, byte[] signature) {
        int offset = 5;
        while(true) {
            VarInt keyLength = new VarInt(serialized, offset);
            offset += keyLength.getOriginalSizeInBytes();
            if(keyLength.value == 0) {
                break;
            }
            offset += (int)keyLength.value;
            VarInt dataLength = new VarInt(serialized, offset);
            offset += dataLength.getOriginalSizeInBytes() + (int)dataLength.value;
        }

        byte[] keyData = Utils.hexToBytes("aa".repeat(32) + "bb".repeat(32));
        byte[] key = new byte[1 + keyData.length];
        key[0] = PSBTInput.PSBT_IN_TAP_SCRIPT_SIG;
        System.arraycopy(keyData, 0, key, 1, keyData.length);

        ByteArrayOutputStream spliced = new ByteArrayOutputStream();
        spliced.writeBytes(Arrays.copyOfRange(serialized, 0, offset));
        spliced.writeBytes(new VarInt(key.length).encode());
        spliced.writeBytes(key);
        spliced.writeBytes(new VarInt(signature.length).encode());
        spliced.writeBytes(signature);
        spliced.writeBytes(Arrays.copyOfRange(serialized, offset, serialized.length));

        return spliced.toByteArray();
    }

    public static PSBT signedByScriptPath(byte sigHashType) throws Exception {
        PSBT psbt = new PSBT(withTapScriptSignature(declaredUnifiedPsbt().serialize(), sigHashType), false);
        Assertions.assertEquals(1, psbt.getPsbtInputs().get(0).getTapScriptSignatures().size(),
                "the fixture has to carry the signature, or this test proves nothing");
        return psbt;
    }

    /**
     * Present and unverifiable is not the same as absent. The leaf script these are made against is not parsed, so they
     * can never reach the verified count, and counting them is what makes the pair say so.
     */
    @Test
    public void a_script_path_signature_counts_as_something_present_and_unchecked() throws Exception {
        int[] counts = AppServices.signatureOptInCounts(signedByScriptPath(SigHash.ALL.byteValue()),
                java.util.List.of(ECKey.fromPublicOnly(ScriptType.P2TR.getOutputKey(PolicyType.SINGLE_HD,
                        ECKey.fromPrivate(Utils.hexToBytes(PRIVATE_KEY))))));

        Assertions.assertEquals(0, counts[0], "nothing was verified, so nothing may be reported as opting in");
        Assertions.assertEquals(0, counts[1]);
        Assertions.assertTrue(counts[2] > counts[1],
                "a signature that is present and cannot be checked has to leave the answer open");
    }

    /**
     * And the same where the last byte of the signature says it opts in. Reading that byte off a signature nothing
     * verified is the forgery this whole reading exists to refuse, so it must not become an opt-in here either.
     */
    @Test
    public void one_whose_last_byte_claims_the_opt_in_is_still_not_counted() throws Exception {
        int[] counts = AppServices.signatureOptInCounts(
                signedByScriptPath((byte)(SigHash.UNIFIED_FLAG | SigHash.ALL.byteValue())),
                java.util.List.of(ECKey.fromPublicOnly(ScriptType.P2TR.getOutputKey(PolicyType.SINGLE_HD,
                        ECKey.fromPrivate(Utils.hexToBytes(PRIVATE_KEY))))));

        Assertions.assertEquals(0, counts[0], "a byte in an unverified signature is not evidence of anything");
        Assertions.assertTrue(counts[2] > counts[1]);
    }

    /**
     * An input that omits its spent output is not recognisable as taproot, and its script path signature still counts.
     *
     * Narrowing the count to taproot inputs looked tidy and was wrong: isTaproot turns on the spent output being
     * present, and whoever writes the PSBT chooses whether to include it. Left out, the signature stops reaching
     * either count, and a transaction whose other input checks out reads as fully checked with nothing opting in,
     * which is a settled claim over an entry nobody looked at.
     */
    @Test
    public void a_script_path_signature_counts_even_where_the_spent_output_is_missing() throws Exception {
        PSBT psbt = signedByScriptPath(SigHash.ALL.byteValue());
        psbt.getPsbtInputs().get(0).setWitnessUtxo(null);
        Assertions.assertFalse(psbt.getPsbtInputs().get(0).isTaproot(),
                "the fixture has to be unrecognisable as taproot, or this test proves nothing");

        int[] counts = AppServices.signatureOptInCounts(psbt, java.util.List.of());
        Assertions.assertEquals(0, counts[0]);
        Assertions.assertEquals(0, counts[1]);
        Assertions.assertTrue(counts[2] > 0, "a signature this cannot check has to leave the answer open");
    }

    /**
     * And the same beside an input that does check out. This is the shape that turns a dropped signature into a
     * settled wrong answer rather than a merely incomplete one: without it the transaction reads as every signature
     * checked and none opting in.
     */
    @Test
    public void an_unreadable_signature_beside_a_readable_one_still_holds_the_answer_open() throws Exception {
        ECKey key = ECKey.fromPrivate(Utils.hexToBytes(PRIVATE_KEY));
        ECKey outputKey = ScriptType.P2WPKH.getOutputKey(PolicyType.SINGLE_HD, key);
        Script spk = ScriptType.P2WPKH.getOutputScript(PolicyType.SINGLE_HD, key);
        Script taprootSpk = ScriptType.P2TR.getOutputScript(PolicyType.SINGLE_HD, key);

        Transaction transaction = new Transaction();
        transaction.setVersion(2);
        transaction.addInput(Sha256Hash.ZERO_HASH, 0, new Script(new byte[0]));
        transaction.addInput(Sha256Hash.ZERO_HASH, 1, new Script(new byte[0]));
        transaction.addOutput(VALUE - 10_000, spk);

        PSBT psbt = new PSBT(transaction);
        //Input zero takes the script path signature, spliced in below, and is never checkable
        PSBTInput unreadable = psbt.getPsbtInputs().get(0);
        unreadable.setWitnessUtxo(new TransactionOutput(null, VALUE, taprootSpk.getProgram()));
        unreadable.setSigHash(SigHash.ALL);

        //Input one is signed for real and the old way, so on its own the answer would be settled
        PSBTInput honest = psbt.getPsbtInputs().get(1);
        honest.setWitnessUtxo(new TransactionOutput(null, VALUE, spk.getProgram()));
        honest.setSigHash(SigHash.ALL);
        Assertions.assertTrue(honest.sign(outputKey), "the honest input must be signed");

        //The splice lands in the first input's map, which is input zero
        PSBT spliced = new PSBT(withTapScriptSignature(psbt.serialize(), SigHash.ALL.byteValue()), false);
        Assertions.assertEquals(1, spliced.getPsbtInputs().get(0).getTapScriptSignatures().size(),
                "the fixture has to carry the signature, or this test proves nothing");

        int[] counts = AppServices.signatureOptInCounts(spliced, java.util.List.of(ECKey.fromPublicOnly(outputKey)));
        Assertions.assertEquals(0, counts[0], "nothing verified opts in");
        Assertions.assertEquals(1, counts[1], "one input checked out");
        Assertions.assertTrue(counts[2] > counts[1],
                "the other carries something unreadable, so this is not entitled to call the answer settled");
    }

    /**
     * A script path signature that signs Anyone Can Pay commits to its own input and the outputs and nothing else, so
     * it can be lifted into another transaction and spent on the SHA256d chain. liftableCandidates reads the pushes an
     * input carries and these travel in a field of their own, so the one warning that method exists to give went
     * missing for every taproot script path spend.
     */
    @Test
    public void a_script_path_signature_that_stands_alone_is_named_as_liftable() throws Exception {
        byte legacyAnyoneCanPay = (byte)(SigHash.ALL.byteValue() | SigHash.ANYONECANPAY.value);
        Assertions.assertEquals(1, AppServices.liftableSignatureCount(signedByScriptPath(legacyAnyoneCanPay)),
                "this one can be lifted out and spent, which is the whole of the warning");

        Assertions.assertEquals(0, AppServices.liftableSignatureCount(signedByScriptPath(SigHash.ALL.byteValue())),
                "one that commits to every input cannot be lifted anywhere");

        byte unifiedAnyoneCanPay = (byte)(SigHash.UNIFIED_FLAG | SigHash.ALL.byteValue() | SigHash.ANYONECANPAY.value);
        Assertions.assertEquals(0, AppServices.liftableSignatureCount(signedByScriptPath(unifiedAnyoneCanPay)),
                "one that opts in is not valid under the pre-fork rules to begin with, so there is nowhere to lift it");
    }

    /**
     * A 64 byte signature carries no hash type byte at all. That is the default type, which commits to every input, so
     * there is nothing to lift and nothing to read off the end of it.
     */
    @Test
    public void a_signature_with_no_hash_type_byte_is_not_read_as_one() throws Exception {
        PSBT psbt = declaredUnifiedPsbt();
        byte[] serialized = psbt.serialize();

        byte[] shortSignature = new byte[64];
        java.util.Arrays.fill(shortSignature, (byte)0x33);
        PSBT parsed = new PSBT(withTapScriptSignature(serialized, shortSignature), false);

        Assertions.assertEquals(1, parsed.getPsbtInputs().get(0).getTapScriptSignatures().size());
        Assertions.assertEquals(0, AppServices.liftableSignatureCount(parsed));
        Assertions.assertTrue(AppServices.signatureOptInCounts(parsed, java.util.List.of())[2] > 0,
                "it is still present and still cannot be checked");
    }
}
