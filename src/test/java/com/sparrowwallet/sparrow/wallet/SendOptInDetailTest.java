package com.sparrowwallet.sparrow.wallet;

import com.sparrowwallet.sparrow.UnifiedSigHashDecision;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;

/**
 * The sentence the send screen puts in front of someone about to broadcast.
 *
 * The transaction view had this same shape written inline and it printed the literal word null, for the ordinary
 * multisig with one marked signer, because a decision that carries a condition carries no reason. That bug was found
 * by rendering every state rather than by reading the code, so this checks every state here too, on the screen where
 * the decision to broadcast is actually taken.
 */
public class SendOptInDetailTest {
    /** No decision may put the word null in front of a user, and each has to end a sentence. */
    @Test
    public void every_decision_reads_as_a_sentence() {
        for(UnifiedSigHashDecision decision : UnifiedSigHashDecision.values()) {
            String detail = SendController.optInDetail(decision, List.of());

            Assertions.assertFalse(detail.contains("null"), decision + " renders null: " + detail);
            Assertions.assertTrue(detail.endsWith("."), decision + " does not end a sentence: " + detail);
            Assertions.assertFalse(detail.contains(", because ."), decision + " has an empty reason: " + detail);
            Assertions.assertFalse(detail.isBlank(), decision + " says nothing at all");
        }
    }

    /** A settled opt-in is the only state that may claim both properties. */
    @Test
    public void only_a_guaranteed_opt_in_claims_the_commitment_to_amounts() {
        for(UnifiedSigHashDecision decision : UnifiedSigHashDecision.values()) {
            String detail = SendController.optInDetail(decision, List.of());

            if(!decision.isGuaranteed()) {
                Assertions.assertFalse(detail.contains("each commits to the amount it spends"),
                        decision + " is not settled, so it may not claim every signature commits to its amount");
            }
            if(!decision.isOptedIn()) {
                Assertions.assertFalse(detail.contains("cannot be replayed"),
                        decision + " does not opt in, so it may not claim protection");
            }
        }
    }

    /**
     * A partially marked quorum is protected only if a marked signer is among those that sign, and says so. It carries
     * no reason, which is exactly the state that broke the other screen.
     */
    @Test
    public void a_partially_marked_quorum_states_its_condition() {
        String detail = SendController.optInDetail(UnifiedSigHashDecision.OPTED_IN_IF_MARKED_SIGNS, List.of());

        Assertions.assertFalse(detail.contains("null"), detail);
        Assertions.assertTrue(detail.startsWith("This transaction cannot be replayed onto the SHA256d chain if one of the marked signers"));
        Assertions.assertFalse(detail.contains("each commits to the amount it spends"),
                "the unmarked signers commit to nothing, so this is not the settled sentence");
    }

    /**
     * Every caveat, not only the one the headline came from. On an Electrum connection a partially marked multisig has
     * two at once and only one of them can be the headline; the one dropped is the actionable one.
     */
    @Test
    public void every_caveat_is_reported_whatever_the_headline_was() {
        String detail = SendController.optInDetail(UnifiedSigHashDecision.OPTED_IN_IF_MARKED_SIGNS,
                List.of("First caveat.", "Second caveat."));

        Assertions.assertTrue(detail.contains("First caveat."));
        Assertions.assertTrue(detail.contains("Second caveat."));
    }

    /**
     * Caveats reach the states that decline too. A decision that would not opt in still has whatever the chain or the
     * keystores had to add, and dropping it there was the asymmetry between the two screens.
     */
    @Test
    public void a_declined_decision_still_reports_its_caveats_and_its_remedy() {
        String detail = SendController.optInDetail(UnifiedSigHashDecision.EXTERNAL_SIGNER, List.of("A caveat."));

        Assertions.assertTrue(detail.startsWith("These signatures will be made the way they always have been, because "));
        Assertions.assertTrue(detail.contains("A caveat."));
        Assertions.assertTrue(detail.contains(UnifiedSigHashDecision.EXTERNAL_SIGNER.getRemedy()),
                "a state the user can act on has to say what to do");
    }
}
