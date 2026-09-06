package com.sparrowwallet.sparrow.transaction;

import com.sparrowwallet.drongo.protocol.SigHash;
import com.sparrowwallet.sparrow.UnifiedSigHashDecision;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Every state the replay protection label can be in, and the sentence each one puts in front of a user.
 *
 * The counts behind it are checked elsewhere, against transactions a node mined. What is checked here is what those
 * counts are turned into, because the counts are not what anyone reads. Three of these cases were wrong at some point
 * while this was being written, and none of them was caught by counting.
 */
public class OptInStatusTest {
    /**
     * The fixtures below take nothing signed to mean no signature present, which is true of them and is a convention
     * of this test rather than a rule of the code. Production asks the PSBT instead, because a finalised input
     * carrying nothing signature shaped counts zero signatures and is not unsigned; SignedStateTest covers that.
     */
    private HeadersController.OptInStatus status(SigHash declared, int optedIn, int verified, int signatures, int liftable) {
        boolean declaresUnified = declared != null && declared.isUnified();
        return optInStatus(declaresUnified, declaresUnified, true, signatures == 0, null,
                optedIn, verified, signatures, liftable);
    }

    private static HeadersController.OptInStatus optInStatus(boolean everyInputDeclaresUnified, boolean anyInputDeclaresUnified,
                                                             boolean willBeHonoured, boolean nothingSigned,
                                                             UnifiedSigHashDecision decision, int optedInSignatures,
                                                             int verifiedSignatures, int signatures, int liftable) {
        return HeadersController.optInStatus(everyInputDeclaresUnified, anyInputDeclaresUnified, willBeHonoured,
                nothingSigned, decision, java.util.List.of(), optedInSignatures, verifiedSignatures, signatures, liftable);
    }

    /**
     * Nothing signed yet, so the declared type is all there is to go on, and a declaration is not a check. A PSBT from
     * elsewhere writes its own declarations, so the settled wording and the success mark are not available here.
     */
    @Test
    public void an_unsigned_transaction_says_what_it_will_be_rather_than_what_it_is() {
        HeadersController.OptInStatus unified = status(SigHash.UNIFIED_ALL, 0, 0, 0, 0);
        Assertions.assertEquals(HeadersController.OptInLevel.UNCHECKED, unified.level(),
                "nothing has been checked, so this cannot carry the settled mark");
        Assertions.assertEquals("Will be replay protected", unified.summary());
        Assertions.assertFalse(unified.optedIn());

        HeadersController.OptInStatus legacy = status(SigHash.ALL, 0, 0, 0, 0);
        Assertions.assertFalse(legacy.optedIn());
        Assertions.assertEquals("Will not be replay protected", legacy.summary());
        Assertions.assertTrue(legacy.detail().startsWith("These signatures will be made the way they always have been."),
                "nothing is signed yet, so this describes what the transaction will be rather than what it is");

        Assertions.assertEquals("Will not be replay protected", status(null, 0, 0, 0, 0).summary(),
                "a transaction declaring nothing has not opted in");
        Assertions.assertFalse(optInStatus(false, false, true, true, null, 0, 0, 0, 0).optedIn(),
                "an unsigned transaction where any input declares the old type has not opted in");
    }

    /**
     * A declaration is worth what the signer will honour, and this wallet strips the opt-in on the way to a device it
     * was not told supports it. Promising protection that the next step removes is the worst of both: it reads as
     * settled, and it is contradicted by the wallet itself a moment later.
     */
    @Test
    public void a_declaration_a_signer_may_not_honour_is_said_conditionally() {
        HeadersController.OptInStatus honoured = optInStatus(true, true, true, true, null, 0, 0, 0, 0);
        Assertions.assertEquals("Will be replay protected", honoured.summary());

        HeadersController.OptInStatus maybe = optInStatus(true, true, false, true, null, 0, 0, 0, 0);
        Assertions.assertEquals(HeadersController.OptInLevel.UNCHECKED, maybe.level());
        Assertions.assertEquals("Replay protected only if the signer opts in", maybe.summary());
        Assertions.assertTrue(maybe.detail().contains("Open this transaction with the wallet that holds the keys"),
                "a conditional answer has to say what would settle it");
    }

    /**
     * An unsigned transaction that will not opt in is a settled answer, not an unknown, and takes the warning mark
     * rather than the question mark.
     */
    @Test
    public void an_unsigned_transaction_that_will_not_opt_in_is_a_settled_negative() {
        HeadersController.OptInStatus status = status(SigHash.ALL, 0, 0, 0, 0);

        Assertions.assertEquals(HeadersController.OptInLevel.UNPROTECTED, status.level());
        Assertions.assertEquals("Will not be replay protected", status.summary());
    }

    /**
     * Something signed, and nothing on it could be read as a signature. That is not the unsigned case, and the file's
     * own declaration is not evidence: it wrote that itself.
     */
    @Test
    public void signed_but_unreadable_is_not_read_as_unsigned() {
        HeadersController.OptInStatus status =
                optInStatus(true, true, true, false, null, 0, 0, 0, 0);

        Assertions.assertEquals(HeadersController.OptInLevel.UNCHECKED, status.level());
        Assertions.assertEquals("Replay protection not checked", status.summary());
        Assertions.assertTrue(status.detail().contains("could not be read"));
        Assertions.assertFalse(status.detail().contains("will not be replayable"),
                "a declaration this could not check must not be repeated as a promise");
    }

    /** One signature, checked, and it opts in. */
    @Test
    public void a_signature_that_opts_in_is_reported_as_protection() {
        HeadersController.OptInStatus status = status(SigHash.UNIFIED_ALL, 1, 1, 1, 0);

        Assertions.assertEquals(HeadersController.OptInLevel.PROTECTED, status.level());
        Assertions.assertTrue(status.optedIn());
        Assertions.assertEquals("Replay protected", status.summary());
        Assertions.assertTrue(status.detail().contains("cannot be replayed"));
        Assertions.assertTrue(status.detail().contains("each commits to the amount it spends"));
    }

    /**
     * The case a stock signer produces, and the one that made this test exist. The signature was checked and is simply
     * the old kind, so this knows how it was made and must not say it could not be read.
     */
    @Test
    public void a_signature_checked_and_found_legacy_is_stated_plainly() {
        HeadersController.OptInStatus status = status(SigHash.ALL, 0, 1, 1, 0);

        Assertions.assertFalse(status.optedIn());
        Assertions.assertEquals(HeadersController.OptInLevel.UNPROTECTED, status.level(),
                "every signature present was checked, so this is a finding and not a gap");
        Assertions.assertEquals("Not replay protected", status.summary());
        Assertions.assertEquals("These signatures are made the way they always have been. They carry no replay protection.",
                status.detail());
        Assertions.assertFalse(status.detail().contains("could not be"),
                "the signature was checked, so nothing here is unknown to this wallet");
    }

    /**
     * And the case that is genuinely unknown: something present could not be checked at all. No wallet to vouch for the
     * keys yet, an input this wallet does not derive, or a push that is not a signature all arrive here.
     */
    @Test
    public void something_that_could_not_be_checked_is_hedged() {
        HeadersController.OptInStatus status = status(SigHash.ALL, 0, 1, 2, 0);

        Assertions.assertFalse(status.optedIn());
        Assertions.assertTrue(status.detail().startsWith("Not all of these signatures could be checked"),
                "what could not be read must not be reported as though it had been");
        Assertions.assertTrue(status.detail().contains("no replay protection"));
    }

    /**
     * A mixed witness, which is the shape the fork produces where one cosigner is marked and another is not. One
     * opted-in signature protects the transaction; the rest are named for what they are.
     */
    @Test
    public void a_mixed_witness_names_both_halves() {
        HeadersController.OptInStatus status = status(SigHash.ALL, 1, 2, 2, 0);

        Assertions.assertTrue(status.optedIn());
        Assertions.assertEquals("Replay protected", status.summary());
        Assertions.assertTrue(status.detail().contains("1 of 2 does"), "a count of one reads as one");
        Assertions.assertTrue(status.detail().contains("The other one was made the old way"),
                "both signatures were checked, so neither is unknown, and one of them reads as one");
        Assertions.assertFalse(status.detail().contains("could not be confirmed"));
    }

    /** The same, where one of the others could not be checked. */
    @Test
    public void and_hedges_the_half_it_could_not_read() {
        HeadersController.OptInStatus status = status(SigHash.ALL, 1, 2, 3, 0);

        Assertions.assertTrue(status.optedIn());
        Assertions.assertTrue(status.detail().contains("1 of 3 does"));
        Assertions.assertTrue(status.detail().contains("The other 2 could not be confirmed as opting in"));
    }

    /**
     * A legacy Anyone Can Pay signature beside an opted-in one commits only to its own input and the outputs, so it can
     * be lifted out and spent where the fork was never adopted. The transaction is protected and that input is not.
     */
    @Test
    public void a_liftable_signature_is_called_out_even_though_the_transaction_is_protected() {
        HeadersController.OptInStatus one = status(SigHash.ALL, 1, 2, 2, 1);
        Assertions.assertTrue(one.optedIn());
        Assertions.assertTrue(one.detail().contains("One of those signs Anyone Can Pay"));
        Assertions.assertTrue(one.detail().contains("can be lifted into another transaction"));

        HeadersController.OptInStatus two = status(SigHash.ALL, 1, 3, 3, 2);
        Assertions.assertTrue(two.detail().contains("2 of those sign Anyone Can Pay"));
    }

    /**
     * Nothing verified at all, which is what a signed transaction viewed without its wallet looks like, and what every
     * transaction opened with no matching wallet looks like.
     *
     * The headline says it was not checked rather than that it was checked and found wanting, because one signature
     * that opts in anywhere is enough and an unchecked one might be it. The detail must not imply this looked.
     */
    @Test
    public void nothing_checkable_says_so_rather_than_implying_it_looked() {
        HeadersController.OptInStatus status = status(SigHash.ALL, 0, 0, 2, 0);

        Assertions.assertFalse(status.optedIn());
        Assertions.assertEquals(HeadersController.OptInLevel.UNCHECKED, status.level());
        Assertions.assertEquals("Replay protection not checked", status.summary(),
                "saying it is not protected would state as a finding something this did not look at");
        Assertions.assertTrue(status.detail().startsWith("None of these signatures could be checked against a key this wallet holds"));
        Assertions.assertTrue(status.detail().contains("treat it as carrying no replay protection"),
                "unknown is not an invitation to assume the good case");
    }

    /**
     * Some checked, some not. One opted-in signature anywhere is enough, so an unchecked one leaves the question open:
     * this is not entitled to say the transaction is unprotected either.
     */
    @Test
    public void partly_checkable_is_also_unknown() {
        HeadersController.OptInStatus status = status(SigHash.ALL, 0, 1, 2, 0);

        Assertions.assertEquals(HeadersController.OptInLevel.UNCHECKED, status.level());
        Assertions.assertEquals("Replay protection not checked", status.summary());
        Assertions.assertTrue(status.detail().startsWith("Not all of these signatures could be checked"));
        Assertions.assertTrue(status.detail().contains("one signature that opts in anywhere is enough"));
    }

    /**
     * Every signature present opted in, which is the strongest thing this can say: the transaction cannot be replayed
     * and every signature in it commits to the amount it spends.
     */
    @Test
    public void every_signature_opting_in_earns_the_stronger_sentence() {
        HeadersController.OptInStatus status = status(SigHash.UNIFIED_ALL, 2, 2, 2, 0);

        Assertions.assertTrue(status.optedIn());
        Assertions.assertTrue(status.detail().contains("each commits to the amount it spends"));
        Assertions.assertFalse(status.detail().contains("The other"));
    }

    /**
     * A partially marked quorum has no reason it would not opt in, because it would, given the right signers. What it
     * has is the condition, and the condition lives in the caveat rather than the reason. Reading the reason produced
     * the literal word null in a sentence shown to the user, in the ordinary case of a multisig with one marked signer.
     */
    @Test
    public void a_partially_marked_quorum_states_the_condition_rather_than_a_reason() {
        //The caveat arrives as a qualification because the caller has the wallet and so can name the marked signers,
        //which is the half of it a reader can act on
        HeadersController.OptInStatus status = HeadersController.optInStatus(true, true, false, true,
                UnifiedSigHashDecision.OPTED_IN_IF_MARKED_SIGNS,
                java.util.List.of(UnifiedSigHashDecision.OPTED_IN_IF_MARKED_SIGNS.getCaveat() + " Those are: Signer 1."),
                0, 0, 0, 0);

        Assertions.assertEquals("Replay protected only if the signer opts in", status.summary());
        Assertions.assertFalse(status.detail().contains("null"), status.detail());
        Assertions.assertTrue(status.detail().contains("a quorum made up entirely of them would not opt in"),
                "the condition the caveat states is the one thing this case has to say");
        Assertions.assertTrue(status.detail().contains("Those are: Signer 1."),
                "and naming who to reach for is the half of it a reader can act on");
    }

    /**
     * And the same for every other decision, since a decision reaching here with nothing to say is exactly how the
     * first one got through. None of them may put the word null in front of a user, and each has to end a sentence.
     */
    @Test
    public void no_decision_renders_as_the_word_null() {
        for(UnifiedSigHashDecision decision : UnifiedSigHashDecision.values()) {
            HeadersController.OptInStatus status = optInStatus(true, true, false, true, decision, 0, 0, 0, 0);
            Assertions.assertFalse(status.detail().contains("null"), decision + " renders null: " + status.detail());
            Assertions.assertTrue(status.detail().endsWith(".") || status.detail().endsWith("in."),
                    decision + " does not end a sentence: " + status.detail());
        }
    }

    /**
     * Some inputs ask for the opt-in and some do not, which is any externally assembled transaction: a co-signer's
     * PSBT, a payjoin proposal, a coinjoin. One opted-in signature anywhere protects the whole transaction, so calling
     * this settled and unprotected states as a finding something that depends on which inputs get signed.
     */
    @Test
    public void a_transaction_where_only_some_inputs_ask_for_the_opt_in_is_not_settled() {
        HeadersController.OptInStatus status = HeadersController.optInStatus(false, true, true, true, null,
                java.util.List.of(), 0, 0, 0, 0);

        Assertions.assertEquals(HeadersController.OptInLevel.UNCHECKED, status.level(),
                "this is not a finding, it depends on which of these inputs are signed");
        Assertions.assertEquals("Replay protection depends on which inputs are signed", status.summary());
        Assertions.assertTrue(status.detail().contains("One signature that opts in anywhere protects the whole transaction"));

        Assertions.assertEquals("Will not be replay protected",
                HeadersController.optInStatus(false, false, true, true, null, java.util.List.of(), 0, 0, 0, 0).summary(),
                "where no input asks for it, the answer really is settled");
    }
}
