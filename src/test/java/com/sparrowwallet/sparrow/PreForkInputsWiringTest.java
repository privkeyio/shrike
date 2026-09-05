package com.sparrowwallet.sparrow;

import com.sparrowwallet.drongo.Utils;
import com.sparrowwallet.drongo.protocol.Sha256Hash;
import com.sparrowwallet.drongo.protocol.Script;
import com.sparrowwallet.drongo.protocol.Transaction;
import com.sparrowwallet.drongo.psbt.PSBT;
import com.sparrowwallet.drongo.wallet.BlockTransaction;
import com.sparrowwallet.drongo.wallet.BlockTransactionHashIndex;
import com.sparrowwallet.drongo.wallet.Payment;
import com.sparrowwallet.drongo.wallet.Wallet;
import com.sparrowwallet.drongo.wallet.WalletNode;
import com.sparrowwallet.drongo.wallet.WalletTransaction;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * The two lookups the send screen and the transaction view actually make, driven the way they drive them.
 *
 * PreForkInputsTest covers the reading of a transaction. This covers finding the transaction to read: from the wallet
 * for a spend being prepared, and from the PSBT or the wallet for one that already exists. Both are wired to real
 * transactions a node mined, so a change that breaks the lookup fails here rather than in a screenshot.
 */
public class PreForkInputsWiringTest {
    private static Map<String, Transaction> fixtures;

    @BeforeAll
    public static void readFixtures() throws Exception {
        Map<String, Transaction> read = new HashMap<>();
        try(InputStream in = PreForkInputsWiringTest.class.getResourceAsStream("prefork-fixtures.txt");
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
        fixtures = read;
    }

    private Transaction fixture(String name) {
        return fixtures.get(name);
    }

    private Wallet walletHolding(Transaction... transactions) {
        Wallet wallet = new Wallet();
        Map<Sha256Hash, BlockTransaction> history = new LinkedHashMap<>();
        for(Transaction transaction : transactions) {
            history.put(transaction.getTxId(), new BlockTransaction(transaction.getTxId(), 1, new Date(), 0L, transaction));
        }
        wallet.updateTransactions(history);

        return wallet;
    }

    /**
     * The send screen's question, asked of the coins it has selected, before anything is signed.
     */
    private WalletTransaction spendOf(Wallet wallet, Transaction funder) {
        Map<BlockTransactionHashIndex, WalletNode> selected = new LinkedHashMap<>();
        selected.put(new BlockTransactionHashIndex(funder.getTxId(), 1, new Date(), 0L, 0,
                funder.getOutputs().get(0).getValue()), new WalletNode(wallet, "m/0/0"));

        Transaction spend = fixture("legacySpendOfIt");
        List<WalletTransaction.Output> outputs = spend.getOutputs().stream()
                .map(WalletTransaction.Output::new).collect(Collectors.toList());

        return new WalletTransaction(wallet, spend, List.of(), List.of(selected),
                List.of(new Payment(null, null, spend.getOutputs().get(0).getValue(), false)), outputs, 1000L);
    }

    @Test
    public void the_send_screen_reads_the_funding_transaction_out_of_the_wallet() {
        Wallet wallet = walletHolding(fixture("optedInFunder"));
        Assertions.assertTrue(PreForkInputs.noneReachable(spendOf(wallet, fixture("optedInFunder"))));
    }

    @Test
    public void and_says_nothing_where_the_funding_transaction_is_an_ordinary_one() {
        Wallet wallet = walletHolding(fixture("legacyFunder"));
        Assertions.assertFalse(PreForkInputs.noneReachable(spendOf(wallet, fixture("legacyFunder"))));
    }

    /**
     * A wallet that does not hold the funding transaction cannot answer, and must not be read as an answer. This is the
     * state a freshly loaded wallet is in before its history arrives.
     */
    @Test
    public void a_wallet_without_the_history_proves_nothing() {
        Wallet empty = new Wallet();
        Assertions.assertFalse(PreForkInputs.noneReachable(spendOf(empty, fixture("optedInFunder"))));
        Assertions.assertFalse(PreForkInputs.noneReachable((WalletTransaction)null));
    }

    /**
     * A transaction carrying an input this wallet did not choose, which is what a payjoin makes. The other party's coin
     * is not one this can read, so the answer is the conservative one however unreachable the wallet's own coins are.
     */
    @Test
    public void an_input_the_wallet_did_not_choose_is_not_answered_for() {
        Wallet wallet = walletHolding(fixture("optedInFunder"));
        WalletTransaction spend = spendOf(wallet, fixture("optedInFunder"));
        Assertions.assertTrue(PreForkInputs.noneReachable(spend), "the fixture must start out proven");

        Transaction withAnother = new Transaction(spend.getTransaction().bitcoinSerialize());
        withAnother.addInput(fixture("legacyFunder").getTxId(), 0, new Script(new byte[0]));
        WalletTransaction payjoined = new WalletTransaction(wallet, withAnother, List.of(),
                spend.getSelectedUtxoSets(), spend.getPayments(),
                withAnother.getOutputs().stream().map(WalletTransaction.Output::new).collect(Collectors.toList()), 1000L);

        Assertions.assertFalse(PreForkInputs.noneReachable(payjoined),
                "an input the wallet did not select must not be passed over");
    }

    /**
     * The transaction view's question, answered from the wallet's own history.
     */
    @Test
    public void the_transaction_view_reads_it_from_the_wallet() {
        PSBT psbt = new PSBT(fixture("legacySpendOfIt"));

        Assertions.assertTrue(PreForkInputs.noneReachable(psbt, walletHolding(fixture("optedInFunder"))));
        Assertions.assertFalse(PreForkInputs.noneReachable(psbt, new Wallet()),
                "nothing holds the funding transaction, so nothing is proven");
        Assertions.assertFalse(PreForkInputs.noneReachable(psbt, walletHolding(fixture("legacyFunder"))),
                "the wallet holds a different transaction, which does not fund this input");
        Assertions.assertFalse(PreForkInputs.noneReachable(psbt, null));
    }

    /**
     * A forged funding transaction, which is why the PSBT is not a source for this.
     *
     * A PSBT's non witness utxo is checked on parse against the input's outpoint, and a txid does not commit to a
     * witness, so the same txid can arrive carrying any witness at all. malleatedFunder is exactly that: the ordinary
     * funding transaction with the opted-in one's witness attached. Read on its own it looks like it opts in, and it is
     * the witness that this reads, so trusting the PSBT for it would let whoever sent the PSBT choose the answer.
     */
    @Test
    public void a_funding_transaction_supplied_with_the_psbt_is_not_trusted() {
        Transaction forged = fixture("malleatedFunder");
        Assertions.assertEquals(fixture("legacyFunder").getTxId(), forged.getTxId(),
                "the forgery must carry the real transaction's txid, or it proves nothing");
        Assertions.assertTrue(PreForkInputs.optsIn(forged),
                "read on its own the forgery looks opted in, which is what makes the source matter");

        PSBT psbt = new PSBT(fixture("legacySpendOfIt"));
        psbt.getPsbtInputs().get(0).setNonWitnessUtxo(forged);

        Assertions.assertFalse(PreForkInputs.noneReachable(psbt, new Wallet()),
                "a funding transaction carried by the PSBT must not be read at all");
        Assertions.assertFalse(PreForkInputs.noneReachable(psbt, walletHolding(fixture("legacyFunder"))),
                "the wallet holds the real transaction, which does not opt in, and that is the one that counts");
    }

    /**
     * What each of the three states is called, which is the whole point of the check.
     */
    @Test
    public void the_wording_follows_the_answer() {
        Assertions.assertEquals("Replay protected", UnifiedSigHashDecision.summaryFor(true, false));
        Assertions.assertEquals("Replay protected", UnifiedSigHashDecision.summaryFor(true, true),
                "an opted-in transaction is protected by its signatures, which is the stronger claim of the two");
        Assertions.assertEquals("Nothing to replay", UnifiedSigHashDecision.summaryFor(false, true));
        Assertions.assertEquals("Not replay protected", UnifiedSigHashDecision.summaryFor(false, false));
        Assertions.assertEquals("Not replay protected", UnifiedSigHashDecision.summaryFor(false),
                "the reading that knows nothing about the inputs must keep the warning");
    }
}
