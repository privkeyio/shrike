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
    /** MAX_INPUT_SIGNATURE_CHECKS in AppServices, which is what refuses an input before any check runs. */
    private static final int MAX_INPUT_PUSHES = 1024;

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

    /** A transaction of many inputs, each signed for real by the one key this fixture vouches for. */
    private PSBT manyInputs(int inputs, byte sigHashType) {
        ECKey outputKey = ScriptType.P2WPKH.getOutputKey(PolicyType.SINGLE_HD, key());
        Script spk = ScriptType.P2WPKH.getOutputScript(PolicyType.SINGLE_HD, key());

        Transaction transaction = new Transaction();
        transaction.setVersion(2);
        for(int i = 0; i < inputs; i++) {
            transaction.addInput(Sha256Hash.ZERO_HASH, i, new Script(new byte[0]));
        }
        transaction.addOutput(VALUE - 10_000, spk);

        PSBT psbt = new PSBT(transaction);
        //Every spent output first: the unified message commits to all of them, so none can be signed until all are set
        for(PSBTInput psbtInput : psbt.getPsbtInputs()) {
            psbtInput.setWitnessUtxo(new TransactionOutput(null, VALUE, spk.getProgram()));
            psbtInput.setSigHash(SigHash.fromByte(sigHashType));
        }
        for(PSBTInput psbtInput : psbt.getPsbtInputs()) {
            psbtInput.sign(outputKey);
        }

        return psbt;
    }

    /**
     * An ordinary transaction of many inputs is checked to the end. A ceiling meant to bound a hostile transaction must
     * not ration an honest one, and two hundred single signature inputs is a consolidation someone really sends.
     */
    @Test
    public void every_input_of_an_ordinary_transaction_is_checked() {
        byte unifiedAll = (byte)(SigHash.UNIFIED_FLAG | SigHash.ALL.byteValue());
        PSBT psbt = manyInputs(200, unifiedAll);

        int[] counts = AppServices.signatureOptInCounts(psbt, trusted());
        Assertions.assertEquals(200, counts[0], "every input opted in and every one should have been checked");
        Assertions.assertEquals(200, counts[1]);
        Assertions.assertEquals(200, counts[2]);
    }

    /**
     * Inputs that cannot be checked must cost the transaction nothing.
     *
     * Each is refused before a single check runs, because it carries more pushes than one input may ask for. If the
     * budget were charged for that refused work, enough of them would exhaust it and the honest input at the end would
     * go unchecked, which suppresses exactly the signal this reports. The count of crafted inputs here is chosen so
     * that charging for them would overrun the budget and skipping them does not.
     */
    @Test
    public void inputs_that_cannot_be_checked_do_not_silence_the_honest_one() {
        byte unifiedAll = (byte)(SigHash.UNIFIED_FLAG | SigHash.ALL.byteValue());
        //Chosen so that charging for the crafted inputs would consume the transaction's budget exactly, leaving the
        //honest input nothing: nine inputs allow 9216 checks, and eight refused inputs of 1152 pushes come to 9216.
        int crafted = 8;
        int pushesEach = 1152;

        ECKey outputKey = ScriptType.P2WPKH.getOutputKey(PolicyType.SINGLE_HD, key());
        Script spk = ScriptType.P2WPKH.getOutputScript(PolicyType.SINGLE_HD, key());

        Transaction transaction = new Transaction();
        transaction.setVersion(2);
        for(int i = 0; i <= crafted; i++) {
            transaction.addInput(Sha256Hash.ZERO_HASH, i, new Script(new byte[0]));
        }
        transaction.addOutput(VALUE - 10_000, spk);

        PSBT psbt = new PSBT(transaction);
        for(PSBTInput psbtInput : psbt.getPsbtInputs()) {
            psbtInput.setWitnessUtxo(new TransactionOutput(null, VALUE, spk.getProgram()));
            psbtInput.setSigHash(SigHash.fromByte(unifiedAll));
        }

        //The honest input is last, so anything the crafted ones consume is consumed before it is reached
        PSBTInput honest = psbt.getPsbtInputs().get(crafted);
        Assertions.assertTrue(honest.sign(outputKey), "the honest input must be signed");

        for(int i = 0; i < crafted; i++) {
            List<byte[]> pushes = new ArrayList<>();
            for(int j = 0; j < pushesEach; j++) {
                pushes.add(junk());
            }
            psbt.getPsbtInputs().get(i).setFinalScriptWitness(new TransactionWitness(null, pushes));
        }

        int[] counts = AppServices.signatureOptInCounts(psbt, trusted());
        Assertions.assertEquals(1, counts[0], "the honest input was never checked, so a crafted one silenced it");
        Assertions.assertEquals(1, counts[1]);
        //Each crafted input carries more pushes than a spend needs, so it is now refused before any of them is
        //parsed and counts as a bounded block of present-and-unread rather than push by push. What matters is
        //unchanged: it is still present, so the answer stays open, and it cost the honest input nothing.
        Assertions.assertTrue(counts[2] > counts[1], "the crafted inputs still have to leave the answer open");
        Assertions.assertTrue(counts[2] >= crafted * 1024, "and still have to count as present");
    }

    /**
     * A push that is provably not a signature must not make "not replay protected" unreachable.
     *
     * An uncompressed public key is 65 bytes and decodes as a Schnorr signature, so a finalised legacy input carries
     * one beside its real signature. Counting it would leave something present that could never be checked, which
     * reads as "not checked" forever, and hands anyone supplying a transaction a one push way to arrange that.
     */
    @Test
    public void a_public_key_beside_a_signature_does_not_make_the_answer_unknowable() {
        PSBT psbt = signed(SigHash.ALL.byteValue());
        PSBTInput psbtInput = psbt.getPsbtInputs().get(0);
        ECKey outputKey = ScriptType.P2WPKH.getOutputKey(PolicyType.SINGLE_HD, key());
        TransactionSignature signature = psbtInput.getPartialSignature(ECKey.fromPublicOnly(outputKey));

        //An uncompressed key, which is what a legacy input pushes beside its signature
        byte[] uncompressed = new byte[65];
        Arrays.fill(uncompressed, (byte)0x11);
        uncompressed[0] = 0x04;
        psbtInput.setFinalScriptWitness(new TransactionWitness(null, List.of(signature.encodeToBitcoin(), uncompressed)));

        int[] counts = AppServices.signatureOptInCounts(psbt, trusted());
        Assertions.assertEquals(0, counts[0], "the signature does not opt in");
        Assertions.assertEquals(1, counts[1], "the signature was checked");
        Assertions.assertEquals(1, counts[2],
                "the public key is not a signature, so nothing is left unchecked and the answer is a finding");
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

    /**
     * An input that was skipped stays in the denominator, so the caller can still tell it was skipped.
     *
     * The filter on the denominator exists to stop a crafted push inflating it against a reading that did happen. On an
     * input no reading happened for it did the reverse: an uncompressed public key is 65 bytes and decodes as a
     * signature, so a refused input made only of them counted zero checked and zero present, and vanished from both
     * counts. A transaction whose other input is honest and legacy then reads as every signature checked and none
     * opting in, which is a settled "not replay protected" over an input that was never looked at and may carry the
     * opt-in. One signature that opts in anywhere is enough, so that answer is not this wallet's to give.
     */
    @Test
    public void an_input_the_budget_skipped_is_still_counted_as_present() {
        ECKey outputKey = ScriptType.P2WPKH.getOutputKey(PolicyType.SINGLE_HD, key());
        Script spk = ScriptType.P2WPKH.getOutputScript(PolicyType.SINGLE_HD, key());

        Transaction transaction = new Transaction();
        transaction.setVersion(2);
        transaction.addInput(Sha256Hash.ZERO_HASH, 0, new Script(new byte[0]));
        transaction.addInput(Sha256Hash.ZERO_HASH, 1, new Script(new byte[0]));
        transaction.addOutput(VALUE - 10_000, spk);

        PSBT psbt = new PSBT(transaction);
        for(PSBTInput psbtInput : psbt.getPsbtInputs()) {
            psbtInput.setWitnessUtxo(new TransactionOutput(null, VALUE, spk.getProgram()));
            psbtInput.setSigHash(SigHash.ALL);
        }

        //Checked, and found to be the old kind, which on its own is a settled answer
        Assertions.assertTrue(psbt.getPsbtInputs().get(0).sign(outputKey), "the honest input must be signed");

        //Refused before a check runs, because it asks for more than one input may, and made of pushes the denominator
        //filter excludes: 65 bytes beginning 0x04 is the uncompressed public key encoding
        List<byte[]> pushes = new ArrayList<>();
        for(int i = 0; i < MAX_INPUT_PUSHES + 1; i++) {
            byte[] push = new byte[65];
            Arrays.fill(push, (byte)0x11);
            push[0] = 0x04;
            pushes.add(push);
        }
        psbt.getPsbtInputs().get(1).setFinalScriptWitness(new TransactionWitness(null, pushes));

        int[] counts = AppServices.signatureOptInCounts(psbt, trusted());
        Assertions.assertEquals(0, counts[0]);
        Assertions.assertEquals(1, counts[1], "only the honest input could be checked");
        Assertions.assertTrue(counts[2] > counts[1],
                "the skipped input has to stay present, or nothing tells the caller it went unchecked");
    }

    /**
     * A signature is not a control block because it happens to start like one.
     *
     * isTaprootControlBlock and isPubKey are shape tests: 65 bytes beginning 0xc0 or 0xc1 is the first, 65 beginning
     * 0x04 the second, and about three in 256 Schnorr signatures begin with one of those. A finalised taproot key path
     * input carries exactly one push, its signature, so filtering by shape alone discards it: the Anyone Can Pay
     * warning goes missing, and where the signature also fails to verify the input leaves the denominator entirely and
     * a transaction whose other input checks out reads as settled.
     *
     * Those pushes sit last, after the signatures, so the position is what tells them apart.
     */
    @Test
    public void a_lone_signature_shaped_like_a_control_block_is_still_a_signature() {
        for(byte first : new byte[] {(byte)0xc0, (byte)0xc1, 0x04}) {
            byte[] signature = new byte[65];
            Arrays.fill(signature, (byte)0x33);
            signature[0] = first;
            signature[64] = (byte)(SigHash.ALL.byteValue() | SigHash.ANYONECANPAY.value);

            PSBT psbt = signed(SigHash.ALL.byteValue());
            PSBTInput psbtInput = psbt.getPsbtInputs().get(0);
            psbtInput.getPartialSignatures().clear();
            //One push, which is what a taproot key path spend finalises to
            psbtInput.setFinalScriptWitness(new TransactionWitness(null, List.of(signature)));

            Assertions.assertEquals(1, AppServices.liftableSignatureCount(psbt),
                    String.format("a lone signature beginning 0x%02x lost its Anyone Can Pay warning", first));
            Assertions.assertTrue(AppServices.signatureOptInCounts(psbt, trusted())[2] > 0,
                    String.format("a lone signature beginning 0x%02x left the denominator", first));
        }
    }

    /** And the pushes that really are those things, in the position they really sit, are still left out. */
    @Test
    public void a_public_key_or_control_block_in_its_own_position_is_still_excluded() {
        byte[] pubKey = new byte[65];
        Arrays.fill(pubKey, (byte)0x11);
        pubKey[0] = 0x04;
        pubKey[64] = (byte)(SigHash.ALL.byteValue() | SigHash.ANYONECANPAY.value);

        PSBT withPubKey = signed(SigHash.ALL.byteValue());
        finaliseWith(withPubKey, pubKey);
        Assertions.assertEquals(0, AppServices.liftableSignatureCount(withPubKey),
                "the real signature signs All, and the trailing push is a public key rather than a second signature");
    }

    /**
     * The budget has to reach the end of a consolidation a quorum signed, which is the shape this whole reading exists
     * for. It was charging keys times signatures against a budget scaled for the expensive part, and the expensive part
     * is the message: one is built per distinct hash type and remembered, so an input costs one however many keys
     * vouch for it. A 2 of 3 was charged six times what it spends, and the label read "not checked" for the wallet
     * this feature is aimed at.
     */
    @Test
    public void a_quorum_signed_consolidation_is_checked_to_the_end() {
        int inputs = 200;
        ECKey outputKey = ScriptType.P2WPKH.getOutputKey(PolicyType.SINGLE_HD, key());
        Script spk = ScriptType.P2WPKH.getOutputScript(PolicyType.SINGLE_HD, key());

        Transaction transaction = new Transaction();
        transaction.setVersion(2);
        for(int i = 0; i < inputs; i++) {
            transaction.addInput(Sha256Hash.ZERO_HASH, i, new Script(new byte[0]));
        }
        transaction.addOutput(VALUE - 10_000, spk);

        PSBT psbt = new PSBT(transaction);
        //Every spent output first: the opted-in message commits to all of them, so none can be signed until all are set
        for(PSBTInput psbtInput : psbt.getPsbtInputs()) {
            psbtInput.setWitnessUtxo(new TransactionOutput(null, VALUE, spk.getProgram()));
            psbtInput.setSigHash(SigHash.fromByte((byte)(SigHash.UNIFIED_FLAG | SigHash.ALL.byteValue())));
        }
        for(PSBTInput psbtInput : psbt.getPsbtInputs()) {
            Assertions.assertTrue(psbtInput.sign(outputKey), "every input must be signed");
        }

        //Three keys vouched for, as a quorum's worth would be, against one signature on each input
        List<ECKey> quorum = new ArrayList<>(trusted());
        quorum.add(ECKey.fromPublicOnly(ECKey.fromPrivate(Utils.hexToBytes("22".repeat(32))).getPubKey()));
        quorum.add(ECKey.fromPublicOnly(ECKey.fromPrivate(Utils.hexToBytes("33".repeat(32))).getPubKey()));

        int[] counts = AppServices.signatureOptInCounts(psbt, quorum);
        Assertions.assertEquals(inputs, counts[1], "the budget stopped short of the end of an ordinary consolidation");
        Assertions.assertEquals(inputs, counts[0], "every signature opts in, so every one of them has to be counted");
    }

    /**
     * An 11 of 15 consolidation, which is the largest ordinary quorum and the shape the old accounting put out of
     * reach: 165 checks charged an input that actually spends 11, so the budget ran out a fifth of the way in and the
     * label read "not checked" for the wallet most in need of it.
     */
    @Test
    public void a_large_quorum_consolidation_is_checked_to_the_end() {
        int inputs = 100;
        int keyCount = 15;
        int threshold = 11;

        List<ECKey> keys = new ArrayList<>();
        for(int i = 0; i < keyCount; i++) {
            keys.add(ECKey.fromPrivate(Utils.hexToBytes(String.format("%02x", i + 1).repeat(32))));
        }
        Script witnessScript = ScriptType.MULTISIG.getOutputScript(threshold, keys);
        Script spk = ScriptType.P2WSH.getOutputScript(witnessScript);

        Transaction transaction = new Transaction();
        transaction.setVersion(2);
        for(int i = 0; i < inputs; i++) {
            transaction.addInput(Sha256Hash.ZERO_HASH, i, new Script(new byte[0]));
        }
        transaction.addOutput(VALUE - 10_000, spk);

        PSBT psbt = new PSBT(transaction);
        for(PSBTInput psbtInput : psbt.getPsbtInputs()) {
            psbtInput.setWitnessUtxo(new TransactionOutput(null, VALUE, spk.getProgram()));
            psbtInput.setWitnessScript(witnessScript);
            psbtInput.setSigHash(SigHash.fromByte((byte)(SigHash.UNIFIED_FLAG | SigHash.ALL.byteValue())));
        }
        for(PSBTInput psbtInput : psbt.getPsbtInputs()) {
            for(int i = 0; i < threshold; i++) {
                Assertions.assertTrue(psbtInput.sign(keys.get(i)), "every input must be signed by the quorum");
            }
        }

        List<ECKey> trusted = new ArrayList<>();
        for(ECKey key : keys) {
            trusted.add(ECKey.fromPublicOnly(key));
        }

        long start = System.nanoTime();
        int[] counts = AppServices.signatureOptInCounts(psbt, trusted);
        long ms = (System.nanoTime() - start) / 1_000_000;

        Assertions.assertEquals(inputs * threshold, counts[1],
                "the budget stopped short of the end of an 11 of 15 consolidation");
        Assertions.assertEquals(inputs * threshold, counts[0], "every signature opts in");
        Assertions.assertTrue(ms < 2000, "this runs while a screen is drawing, and took " + ms + "ms");
    }


    /**
     * The liftable warning walks every push in the transaction and nothing else caps them: four million measured two
     * seconds on the drawing thread, and gave a count no sentence should carry. Bounded now. Stopping early can only
     * leave a warning unsaid, and a transaction that reaches the cap already reads as not checked from the counts.
     */
    @Test
    public void the_liftable_warning_does_not_walk_a_transaction_without_end() {
        int inputs = 100;
        int pushesEach = 1000;
        Script spk = ScriptType.P2WPKH.getOutputScript(PolicyType.SINGLE_HD, key());

        Transaction transaction = new Transaction();
        transaction.setVersion(2);
        for(int i = 0; i < inputs; i++) {
            transaction.addInput(Sha256Hash.ZERO_HASH, i, new Script(new byte[0]));
        }
        transaction.addOutput(VALUE - 10_000, spk);

        PSBT psbt = new PSBT(transaction);
        for(PSBTInput psbtInput : psbt.getPsbtInputs()) {
            psbtInput.setWitnessUtxo(new TransactionOutput(null, VALUE, spk.getProgram()));
            List<byte[]> pushes = new ArrayList<>();
            for(int j = 0; j < pushesEach; j++) {
                byte[] push = new byte[65];
                Arrays.fill(push, (byte)0x33);
                push[64] = (byte)(SigHash.ALL.byteValue() | SigHash.ANYONECANPAY.value);
                pushes.add(push);
            }
            psbtInput.setFinalScriptWitness(new TransactionWitness(null, pushes));
        }

        int liftable = AppServices.liftableSignatureCount(psbt);
        Assertions.assertTrue(liftable < inputs * pushesEach,
                "every push in the transaction was walked, and nothing bounds how many there are");
        Assertions.assertTrue(liftable > 0, "and what it did look at is still reported");
    }

    /** An ordinary transaction is nowhere near that, so its count is exact. */
    @Test
    public void an_ordinary_transaction_still_gets_an_exact_liftable_count() {
        byte legacyAnyoneCanPay = (byte)(SigHash.ALL.byteValue() | SigHash.ANYONECANPAY.value);
        PSBT psbt = signed(legacyAnyoneCanPay);

        Assertions.assertEquals(1, AppServices.liftableSignatureCount(psbt));
    }

    /**
     * An input carrying more pushes than any spend needs is refused before any of them is parsed.
     *
     * Every other ceiling here counts signatures, and a witness may carry any number of pushes that are not signature
     * shaped: nothing counting signatures sees them, and each is still parsed and offered to the signature decoder,
     * three times over. A hundred inputs of a hundred thousand of them measured two and a half minutes on the thread
     * that draws. It still has to count as present, or an input nobody looked at leaves the answer settled.
     */
    @Test
    public void an_input_of_more_pushes_than_a_spend_needs_is_not_parsed() {
        int inputs = 2;
        int tinyPushes = 2000;
        Script spk = ScriptType.P2WPKH.getOutputScript(PolicyType.SINGLE_HD, key());

        Transaction transaction = new Transaction();
        transaction.setVersion(2);
        for(int i = 0; i < inputs; i++) {
            transaction.addInput(Sha256Hash.ZERO_HASH, i, new Script(new byte[0]));
        }
        transaction.addOutput(VALUE - 10_000, spk);

        PSBT psbt = new PSBT(transaction);
        for(PSBTInput psbtInput : psbt.getPsbtInputs()) {
            psbtInput.setWitnessUtxo(new TransactionOutput(null, VALUE, spk.getProgram()));
            psbtInput.setSigHash(SigHash.ALL);

            List<byte[]> pushes = new ArrayList<>();
            byte[] signature = new byte[65];
            Arrays.fill(signature, (byte)0x33);
            signature[64] = SigHash.ALL.byteValue();
            pushes.add(signature);
            for(int j = 0; j < tinyPushes; j++) {
                pushes.add(new byte[] {0x01});
            }
            psbtInput.setFinalScriptWitness(new TransactionWitness(null, pushes));
        }

        int[] counts = AppServices.signatureOptInCounts(psbt, trusted());
        Assertions.assertEquals(0, counts[0], "nothing was checked, so nothing may be reported as opting in");
        Assertions.assertEquals(0, counts[1]);
        Assertions.assertTrue(counts[2] >= inputs * 1024,
                "an input this refused to read still has to count as present, or the answer reads as settled");
        Assertions.assertEquals(0, AppServices.liftableSignatureCount(psbt),
                "and the liftable walk refuses it for the same reason");
    }

    /** An ordinary witness is nowhere near that, so it is read as before. */
    @Test
    public void an_ordinary_witness_is_still_read() {
        PSBT psbt = signed(SigHash.ALL.byteValue());
        finaliseWith(psbt);

        int[] counts = AppServices.signatureOptInCounts(psbt, trusted());
        Assertions.assertEquals(1, counts[1], "a real spend carries a handful of pushes and is checked");
        Assertions.assertEquals(1, counts[2]);
    }

    /**
     * Inputs belonging to someone else must cost the transaction nothing, asked through the wallet.
     *
     * A coinjoin or a payjoin proposal is mostly inputs this wallet holds no key for, and it vouches for none of them.
     * An input that names its keys was charged for its signatures whether or not any key was vouched for, so enough
     * of them ahead of the wallet's own spent the budget without a check being made and the honest input read as
     * unchecked. Every other test here uses the key list overload, where every input has trusted keys.
     */
    @Test
    public void foreign_inputs_do_not_silence_the_wallet_s_own() throws Exception {
        int foreign = 8;
        int signaturesEach = 512;

        com.sparrowwallet.drongo.wallet.Wallet wallet = new com.sparrowwallet.drongo.wallet.Wallet();
        wallet.setPolicyType(PolicyType.SINGLE_HD);
        wallet.setScriptType(ScriptType.P2WPKH);
        wallet.getKeystores().add(com.sparrowwallet.drongo.wallet.Keystore.fromSeed(new com.sparrowwallet.drongo.wallet.DeterministicSeed(
                "absent essay fox snake vast pumpkin height crouch silent bulb excuse razor", "", 0,
                com.sparrowwallet.drongo.wallet.DeterministicSeed.Type.BIP39), PolicyType.SINGLE_HD, ScriptType.P2WPKH.getDefaultDerivation()));
        wallet.setDefaultPolicy(com.sparrowwallet.drongo.policy.Policy.getPolicy(PolicyType.SINGLE_HD, ScriptType.P2WPKH, wallet.getKeystores(), null));
        wallet.getNode(com.sparrowwallet.drongo.KeyPurpose.RECEIVE);

        com.sparrowwallet.drongo.wallet.WalletNode node = wallet.getNode(com.sparrowwallet.drongo.KeyPurpose.RECEIVE).getChildren().iterator().next();
        Script ours = wallet.getOutputScript(node);
        Script theirs = ScriptType.P2WPKH.getOutputScript(PolicyType.SINGLE_HD,
                ECKey.fromPrivate(Utils.hexToBytes("99".repeat(32))));

        Transaction transaction = new Transaction();
        transaction.setVersion(2);
        for(int i = 0; i <= foreign; i++) {
            transaction.addInput(Sha256Hash.ZERO_HASH, i, new Script(new byte[0]));
        }
        transaction.addOutput(VALUE - 10_000, ours);

        List<ECKey> strangers = new ArrayList<>();
        for(int i = 0; i < signaturesEach; i++) {
            //A fixed body with a varying tail, so every one is a distinct key well inside the valid range
            strangers.add(ECKey.fromPublicOnly(ECKey.fromPrivate(
                    Utils.hexToBytes("11".repeat(30) + String.format("%04x", i + 1))).getPubKey()));
        }

        PSBT psbt = new PSBT(transaction);
        for(int i = 0; i < foreign; i++) {
            PSBTInput psbtInput = psbt.getPsbtInputs().get(i);
            psbtInput.setWitnessUtxo(new TransactionOutput(null, VALUE, theirs.getProgram()));
            psbtInput.setSigHash(SigHash.ALL);
            byte[] push = new byte[65];
            Arrays.fill(push, (byte)0x33);
            push[64] = SigHash.ALL.byteValue();
            for(ECKey stranger : strangers) {
                psbtInput.getPartialSignatures().put(stranger,
                        TransactionSignature.decodeFromBitcoin(TransactionSignature.Type.SCHNORR, push, false));
            }
        }

        //The wallet's own input, last, so anything the others consume is consumed before it is reached
        PSBTInput honest = psbt.getPsbtInputs().get(foreign);
        honest.setWitnessUtxo(new TransactionOutput(null, VALUE, ours.getProgram()));
        honest.setSigHash(SigHash.fromByte((byte)(SigHash.UNIFIED_FLAG | SigHash.ALL.byteValue())));
        honest.getDerivedPublicKeys().put(node.getPubKey(),
                wallet.getKeystores().get(0).getKeyDerivation().extend(node.getDerivation()));
        Assertions.assertTrue(honest.sign(wallet.getKeystores().get(0).getKey(node)), "the honest input must be signed");

        int[] counts = AppServices.signatureOptInCounts(psbt, wallet);
        Assertions.assertEquals(1, counts[1], "the wallet's own input was never checked, so a stranger's silenced it");
        Assertions.assertEquals(1, counts[0], "and its signature opts in");
    }

    /**
     * A scriptSig that does not parse must not throw from the counting.
     *
     * The push count is read before the per input guard, because refusing an input has to be cheaper than reading it.
     * That put a script parse outside the guard for the first time, so what it does with a script it cannot read
     * decides whether a label survives a truncated one.
     */
    @Test
    public void a_scriptsig_that_does_not_parse_is_counted_rather_than_thrown() {
        Script spk = ScriptType.P2WPKH.getOutputScript(PolicyType.SINGLE_HD, key());

        Transaction transaction = new Transaction();
        transaction.setVersion(2);
        transaction.addInput(Sha256Hash.ZERO_HASH, 0, new Script(new byte[0]));
        transaction.addOutput(VALUE - 10_000, spk);

        PSBT psbt = new PSBT(transaction);
        PSBTInput psbtInput = psbt.getPsbtInputs().get(0);
        psbtInput.setWitnessUtxo(new TransactionOutput(null, VALUE, spk.getProgram()));
        psbtInput.setSigHash(SigHash.ALL);
        //OP_PUSHDATA1 with no length byte after it, then a push claiming more bytes than follow
        psbtInput.setFinalScriptSig(new Script(new byte[] {0x4c}));

        int[] counts = Assertions.assertDoesNotThrow(() -> AppServices.signatureOptInCounts(psbt, trusted()));
        Assertions.assertEquals(0, counts[0], "nothing readable opts in");
        Assertions.assertEquals(0, AppServices.liftableSignatureCount(psbt));

        psbtInput.setFinalScriptSig(new Script(new byte[] {0x4b, 0x01, 0x02}));
        Assertions.assertDoesNotThrow(() -> AppServices.signatureOptInCounts(psbt, trusted()),
                "a push claiming more bytes than follow it is still only an unreadable script");
    }

}
