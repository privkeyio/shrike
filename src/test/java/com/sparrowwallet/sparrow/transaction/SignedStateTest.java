package com.sparrowwallet.sparrow.transaction;

import com.sparrowwallet.drongo.Utils;
import com.sparrowwallet.drongo.crypto.ECKey;
import com.sparrowwallet.drongo.policy.PolicyType;
import com.sparrowwallet.drongo.protocol.Script;
import com.sparrowwallet.drongo.protocol.ScriptType;
import com.sparrowwallet.drongo.protocol.Sha256Hash;
import com.sparrowwallet.drongo.protocol.SigHash;
import com.sparrowwallet.drongo.protocol.Transaction;
import com.sparrowwallet.drongo.protocol.TransactionOutput;
import com.sparrowwallet.drongo.protocol.TransactionWitness;
import com.sparrowwallet.drongo.psbt.PSBT;
import com.sparrowwallet.drongo.psbt.PSBTInput;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

/**
 * Whether anything has signed, which decides whether the label reports a declaration or a count.
 *
 * Read off the number of pushes that looked like signatures, a finalised input carrying nothing signature shaped
 * counted zero and the label republished the file's own declaration as a settled promise. The predicate is asked of
 * the PSBT instead, and it is asserted here rather than only by a harness, because every test in this branch would
 * still pass with the old rule put back.
 */
public class SignedStateTest {
    private static final String PRIVATE_KEY = "11".repeat(32);
    private static final long VALUE = 100_000_000L;

    private PSBT unsigned(SigHash sigHash) {
        ECKey key = ECKey.fromPrivate(Utils.hexToBytes(PRIVATE_KEY));
        Script spk = ScriptType.P2WPKH.getOutputScript(PolicyType.SINGLE_HD, key);

        Transaction transaction = new Transaction();
        transaction.setVersion(2);
        transaction.addInput(Sha256Hash.ZERO_HASH, 0, new Script(new byte[0]));
        transaction.addOutput(VALUE - 10_000, spk);

        PSBT psbt = new PSBT(transaction);
        PSBTInput psbtInput = psbt.getPsbtInputs().get(0);
        psbtInput.setWitnessUtxo(new TransactionOutput(null, VALUE, spk.getProgram()));
        psbtInput.setSigHash(sigHash);

        return psbt;
    }

    @Test
    public void an_unsigned_transaction_has_nothing_signed() {
        Assertions.assertTrue(HeadersController.nothingSigned(unsigned(SigHash.UNIFIED_ALL)));
    }

    @Test
    public void a_partial_signature_counts() {
        PSBT psbt = unsigned(SigHash.ALL);
        Assertions.assertTrue(psbt.getPsbtInputs().get(0).sign(
                ScriptType.P2WPKH.getOutputKey(PolicyType.SINGLE_HD, ECKey.fromPrivate(Utils.hexToBytes(PRIVATE_KEY)))));

        Assertions.assertFalse(HeadersController.nothingSigned(psbt));
    }

    /**
     * The case the old rule got wrong. Finalised, so it has been signed, and carrying one push that reads as nothing,
     * so counting signature shaped pushes gives zero and the transaction looks untouched.
     */
    @Test
    public void a_finalised_input_carrying_nothing_readable_is_still_signed() {
        PSBT psbt = unsigned(SigHash.UNIFIED_ALL);
        byte[] pubKeyShaped = new byte[33];
        Arrays.fill(pubKeyShaped, (byte)0x11);
        pubKeyShaped[0] = 0x02;
        psbt.getPsbtInputs().get(0).setFinalScriptWitness(new TransactionWitness(null, List.of(pubKeyShaped)));

        Assertions.assertFalse(HeadersController.nothingSigned(psbt),
                "it is finalised, so something signed it, whatever the pushes read as");
    }

    /** And the taproot script path case, whose signatures were being dropped before they could be seen at all. */
    @Test
    public void an_input_signed_only_by_a_taproot_script_path_is_signed() throws Exception {
        PSBT psbt = com.sparrowwallet.sparrow.TapScriptSignedTest.signedByScriptPath(SigHash.ALL.byteValue());

        Assertions.assertEquals(1, psbt.getPsbtInputs().get(0).getTapScriptSignatures().size(),
                "the fixture has to carry the signature, or this test proves nothing");
        Assertions.assertFalse(HeadersController.nothingSigned(psbt));
    }
}
