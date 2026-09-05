package com.sparrowwallet.sparrow;

import com.sparrowwallet.drongo.Utils;
import com.sparrowwallet.drongo.protocol.Sha256Hash;
import com.sparrowwallet.drongo.protocol.Transaction;
import com.sparrowwallet.drongo.wallet.BlockTransaction;
import com.sparrowwallet.drongo.wallet.BlockTransactionHashIndex;
import com.sparrowwallet.drongo.wallet.Payment;
import com.sparrowwallet.drongo.wallet.Wallet;
import com.sparrowwallet.drongo.wallet.WalletNode;
import com.sparrowwallet.drongo.wallet.WalletTransaction;

import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * What the send screen would say about a spend, printed for a node-driven harness to check.
 *
 * The node establishes whether the coin can be reached before the fork; this says what the wallet tells the user about
 * it, through the same calls SendController makes. Kept out of the controller's way rather than duplicating its logic:
 * everything here past building the wallet is the production path.
 *
 *   summary fundingRawTx spendRawTx
 */
public class PreForkLabelHarness {
    public static void main(String[] args) {
        if(args.length != 3 || !"summary".equals(args[0])) {
            throw new IllegalArgumentException("Usage: summary <fundingRawTx> <spendRawTx>");
        }

        Transaction funding = new Transaction(Utils.hexToBytes(args[1]));
        Transaction spend = new Transaction(Utils.hexToBytes(args[2]));

        Wallet wallet = new Wallet();
        Map<Sha256Hash, BlockTransaction> history = new LinkedHashMap<>();
        history.put(funding.getTxId(), new BlockTransaction(funding.getTxId(), 1, new Date(), 0L, funding));
        wallet.updateTransactions(history);

        Map<BlockTransactionHashIndex, WalletNode> selected = new LinkedHashMap<>();
        long spentIndex = spend.getInputs().get(0).getOutpoint().getIndex();
        selected.put(new BlockTransactionHashIndex(funding.getTxId(), 1, new Date(), 0L, spentIndex,
                        funding.getOutputs().get((int)spentIndex).getValue()),
                new WalletNode(wallet, "m/0/0"));

        WalletTransaction walletTransaction = new WalletTransaction(wallet, spend, List.of(), List.of(selected),
                List.of(new Payment(null, null, spend.getOutputs().get(0).getValue(), false)),
                spend.getOutputs().stream().map(WalletTransaction.Output::new).collect(Collectors.toList()), 1000L);

        boolean nothingToReplay = PreForkInputs.noneReachable(walletTransaction);
        //False for the opt-in because this is the case the check exists for: signatures that carry no replay
        //protection at all. The harness spends with hash type 0x01, so that is what it would be.
        System.out.println("NOTHING_TO_REPLAY=" + nothingToReplay);
        System.out.println("SUMMARY=" + UnifiedSigHashDecision.summaryFor(false, nothingToReplay));
    }
}
