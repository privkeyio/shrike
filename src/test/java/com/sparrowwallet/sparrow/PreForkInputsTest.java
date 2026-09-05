package com.sparrowwallet.sparrow;

import com.sparrowwallet.drongo.Utils;
import com.sparrowwallet.drongo.protocol.Transaction;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Read against transactions a Bitcoin Knots regtest node actually mined, rather than ones assembled here.
 *
 * The fixtures come from prefork_reachability_e2e.py, which is where the claim this class rests on is established:
 * run without the fork scheduled, the node refuses optedInFunder for its signature, "Signature opts in to the
 * hardfork, which is not active here", and not for a missing input, so its outputs cannot exist there. It then
 * reports missing-inputs for the legacy spend of one of those outputs, which is the whole property: that spend's own
 * signature carries no replay protection, and there is nothing anywhere to replay it against.
 */
public class PreForkInputsTest {
    private static Map<String, Transaction> fixtures;

    @BeforeAll
    public static void readFixtures() throws Exception {
        Map<String, Transaction> read = new HashMap<>();
        try(InputStream in = PreForkInputsTest.class.getResourceAsStream("prefork-fixtures.txt");
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while((line = reader.readLine()) != null) {
                if(line.startsWith("#") || line.isBlank()) {
                    continue;
                }
                String[] parts = line.split("=", 2);
                read.put(parts[0], new Transaction(Utils.hexToBytes(parts[1])));
            }
        }

        Assertions.assertEquals(4, read.size(), "the fixtures did not read");
        fixtures = read;
    }

    private Transaction fixture(String name) {
        return fixtures.get(name);
    }

    @Test
    public void the_opted_in_transaction_is_read_as_opted_in() {
        Assertions.assertTrue(PreForkInputs.optsIn(fixture("optedInFunder")),
                "the transaction the node refused for its signature was not read as opting in");
    }

    @Test
    public void an_ordinary_transaction_is_not() {
        Assertions.assertFalse(PreForkInputs.optsIn(fixture("legacyFunder")));
        Assertions.assertFalse(PreForkInputs.optsIn(fixture("legacySpendOfIt")),
                "a spend that is itself signed the old way must not be read as opting in, whatever funded it");
    }

    /**
     * The case this exists for: a spend whose own signature carries no replay protection at all, funded by a
     * transaction that cannot exist before the fork.
     */
    @Test
    public void a_legacy_spend_of_an_opted_in_output_has_nothing_to_replay() {
        Assertions.assertTrue(PreForkInputs.noneReachable(List.of(fixture("optedInFunder"))));
    }

    @Test
    public void a_legacy_funded_input_is_not_proven() {
        Assertions.assertFalse(PreForkInputs.noneReachable(List.of(fixture("legacyFunder"))));
    }

    /**
     * One reachable input is enough to make the claim false: that coin exists before the fork and the signature
     * spending it is valid there, so the transaction can be replayed whatever the other inputs are.
     */
    @Test
    public void one_reachable_input_among_unreachable_ones_settles_it() {
        Assertions.assertFalse(PreForkInputs.noneReachable(
                List.of(fixture("optedInFunder"), fixture("legacyFunder"))));
        Assertions.assertTrue(PreForkInputs.noneReachable(
                List.of(fixture("optedInFunder"), fixture("optedInFunder"))));
    }

    /**
     * An input whose funding transaction was not found proves nothing, so it cannot be counted as unreachable. That
     * covers a coinbase, which carries no signature to read, and a co-signer's input this wallet does not hold.
     */
    @Test
    public void an_input_that_could_not_be_read_is_not_proven() {
        Assertions.assertFalse(PreForkInputs.noneReachable(Collections.singletonList(null)));
        Assertions.assertFalse(PreForkInputs.noneReachable(Arrays.asList(fixture("optedInFunder"), null)));
    }

    /**
     * Nothing known about no inputs is not the same as nothing to replay, and the empty case is reached before a
     * transaction has any inputs at all.
     */
    @Test
    public void nothing_known_is_not_a_proof() {
        Assertions.assertFalse(PreForkInputs.noneReachable(List.of()));
        Assertions.assertFalse(PreForkInputs.noneReachable((Collection<Transaction>)null));
    }
}
