package com.sparrowwallet.sparrow;

import com.sparrowwallet.drongo.Network;
import com.sparrowwallet.drongo.protocol.BlockHeader;
import com.sparrowwallet.drongo.protocol.Sha256Hash;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;

/**
 * What the chain has to say about a transaction that already declares the opt-in.
 *
 * The transaction view used to fold this into the sentence about what signs the inputs, which produced
 * "whether it survives depends on what signs them, because the fork is not active on this chain yet": a reason that
 * explains nothing in the sentence carrying it. Only the keystore marks decide whether a declared hash type survives,
 * so the chain answers separately or not at all. It must still answer, because dropping it was how the transaction
 * view came to make a stronger claim than the send screen on every Electrum connection.
 */
public class ChainQualificationsTest {
    /**
     * Every decision the chain can reach, since a decision arriving here with nothing to say is how the neighbouring
     * sentence came to print the word null.
     */
    @Test
    public void every_decision_reads_as_a_sentence() {
        for(UnifiedSigHashDecision decision : UnifiedSigHashDecision.values()) {
            for(String qualification : AppServices.chainQualifications(decision)) {
                Assertions.assertFalse(qualification.contains("null"), decision + ": " + qualification);
                Assertions.assertTrue(qualification.endsWith("."), decision + ": " + qualification);
                Assertions.assertTrue(Character.isUpperCase(qualification.charAt(0)), decision + ": " + qualification);
            }
        }
    }

    /**
     * A chain that would not opt in says so as its own statement. "Separately" is load bearing: the sentence before it
     * says what the signatures will be, and without it the two read as one answer contradicting itself.
     */
    @Test
    public void a_chain_that_would_not_opt_in_says_so_apart_from_the_signers() {
        List<String> qualifications = AppServices.chainQualifications(UnifiedSigHashDecision.BEFORE_ACTIVATION_HEIGHT);

        Assertions.assertEquals("Separately, the chain has not reached the activation height, so this wallet would not "
                + "itself opt in as things stand.", qualifications.get(0));
        Assertions.assertFalse(qualifications.get(0).contains("signs them"),
                "the chain explains nothing about what signs the inputs, which is why it is stated apart from them");
    }

    /**
     * The Electrum case, and the reason this is plumbed through at all. No Electrum server reports an activation
     * height, so the cross check that would catch a stale build cannot run, and the transaction view said nothing
     * about it while the send screen did.
     */
    @Test
    public void an_uncorroborated_opt_in_keeps_its_caveat() {
        List<String> qualifications = AppServices.chainQualifications(UnifiedSigHashDecision.OPTED_IN_UNCORROBORATED);

        Assertions.assertEquals(1, qualifications.size());
        Assertions.assertEquals(UnifiedSigHashDecision.OPTED_IN_UNCORROBORATED.getCaveat(), qualifications.get(0));
    }

    /** A chain that has activated and can be corroborated has nothing to add. */
    @Test
    public void a_settled_chain_says_nothing() {
        Assertions.assertEquals(List.of(), AppServices.chainQualifications(UnifiedSigHashDecision.OPTED_IN));
    }

    /**
     * And the same through the real reading of the chain rather than a decision handed in, so the wiring between the
     * announced tip and these sentences is checked and not assumed.
     */
    @Test
    public void the_announced_tip_reaches_these_sentences() {
        Network previousNetwork = Network.get();
        Network.set(Network.MAINNET);
        ChainTip previous = AppServices.getAnnouncedTip();
        try {
            AppServices.setAnnouncedTip(null);
            Assertions.assertEquals(AppServices.chainQualifications(UnifiedSigHashDecision.CHAIN_UNSEEN),
                    AppServices.chainQualifications(),
                    "with no tip announced the chain has not been seen, and that is what the label has to say");

            //A pre-fork header, which is a chain that has not activated whatever height it claims
            BlockHeader preFork = new BlockHeader(1, Sha256Hash.ZERO_HASH, Sha256Hash.ZERO_HASH, null, 0, 0x207fffffL, 0);
            AppServices.setAnnouncedTip(new ChainTip(1, preFork));
            Assertions.assertEquals(AppServices.chainQualifications(UnifiedSigHashDecision.CHAIN_NOT_ACTIVATED),
                    AppServices.chainQualifications());
        } finally {
            AppServices.setAnnouncedTip(previous);
            Network.set(previousNetwork);
        }
    }
}
