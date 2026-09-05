package com.sparrowwallet.sparrow;

import com.sparrowwallet.drongo.protocol.Sha256Hash;
import com.sparrowwallet.drongo.protocol.SigHash;
import com.sparrowwallet.drongo.protocol.Transaction;
import com.sparrowwallet.drongo.protocol.TransactionInput;
import com.sparrowwallet.drongo.protocol.TransactionSignature;
import com.sparrowwallet.drongo.psbt.PSBT;
import com.sparrowwallet.drongo.psbt.PSBTInput;
import com.sparrowwallet.drongo.wallet.BlockTransaction;
import com.sparrowwallet.drongo.wallet.BlockTransactionHashIndex;
import com.sparrowwallet.drongo.wallet.Wallet;
import com.sparrowwallet.drongo.wallet.WalletNode;
import com.sparrowwallet.drongo.wallet.WalletTransaction;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Whether the coins a transaction spends can exist at all for nodes that have not adopted the fork.
 *
 * A signature that does not opt in is valid under both rule sets, which is what "not replay protected" says, and it says
 * it about the signature. Whether anything can actually be replayed is a different question, answered by the inputs: a
 * replay has to spend the same coins, so it needs those coins to exist for the nodes it is replayed against. Where every
 * input was created by a transaction those nodes must reject, there is nothing there to spend and nothing to replay,
 * however the spend was signed.
 *
 * The test is one directional, and deliberately so. One opted-in signature makes a transaction invalid under the
 * pre-fork rules, so its outputs cannot exist there, and that is provable from the transaction by itself. The converse
 * is not: a transaction signed entirely the old way may or may not have been relayed and mined by nodes that never
 * adopted the fork, and nothing in it says which. So this answers "provably nothing to replay" or "not proven", never
 * "replayable", and everything it cannot prove keeps the warning it already had.
 *
 * Not proven covers more than a legacy parent. An input funded by a coinbase has no signature to read, and one whose
 * funding transaction the wallet does not hold cannot be read at all. Both are outputs that may well be unreachable
 * before the fork; neither is shown as such, because the claim being made here is that a replay is impossible.
 */
public class PreForkInputs {
    /**
     * Said instead of "not replay protected", which is true of the signatures and reads as a risk that is not there.
     */
    public static final String SUMMARY = "Nothing to replay";

    /**
     * Why, phrased about the coins rather than the signatures, because that is where the answer comes from.
     */
    public static final String DETAIL = "Every coin this spends was created by a transaction that opts in, and nodes that have not adopted the fork refuse those, so these coins do not exist for them. There is nothing there to replay against.";

    /**
     * The transactions that created the coins a transaction being prepared will spend, with an entry for every input.
     */
    public static boolean noneReachable(WalletTransaction walletTransaction) {
        if(walletTransaction == null || walletTransaction.getWallet() == null) {
            return false;
        }

        Map<BlockTransactionHashIndex, WalletNode> selectedUtxos = walletTransaction.getSelectedUtxos();
        //Every input, not only the ones this wallet chose. A payjoin adds one belonging to the other party, and judging
        //the selection alone would answer for a transaction that is not the one being sent.
        if(selectedUtxos.size() != walletTransaction.getTransaction().getInputs().size()) {
            return false;
        }

        Map<Sha256Hash, BlockTransaction> walletTransactions = walletTransaction.getWallet().getTransactions();
        List<Transaction> funding = new ArrayList<>();
        for(BlockTransactionHashIndex utxo : selectedUtxos.keySet()) {
            BlockTransaction blockTransaction = walletTransactions.get(utxo.getHash());
            funding.add(blockTransaction == null ? null : blockTransaction.getTransaction());
        }

        return noneReachable(funding);
    }

    /**
     * The same for a transaction that already exists, taking each input's funding transaction from the PSBT where it
     * carries one and from the wallet otherwise. A PSBT is only obliged to carry the spent output for a segwit input,
     * not the transaction that created it, so the wallet is what answers for those.
     */
    public static boolean noneReachable(PSBT psbt, Wallet wallet) {
        if(psbt == null) {
            return false;
        }

        Map<Sha256Hash, BlockTransaction> walletTransactions = wallet == null ? Map.of() : wallet.getTransactions();
        List<Transaction> funding = new ArrayList<>();
        for(PSBTInput psbtInput : psbt.getPsbtInputs()) {
            Transaction nonWitnessUtxo = psbtInput.getNonWitnessUtxo();
            if(nonWitnessUtxo != null) {
                funding.add(nonWitnessUtxo);
                continue;
            }

            BlockTransaction blockTransaction = walletTransactions.get(psbtInput.getInput().getOutpoint().getHash());
            funding.add(blockTransaction == null ? null : blockTransaction.getTransaction());
        }

        return noneReachable(funding);
    }

    /**
     * Whether none of the coins spent can exist before the fork, given the transaction that created each input, in any
     * order, with an entry for every input and null where one could not be found.
     */
    public static boolean noneReachable(Collection<Transaction> fundingTransactions) {
        if(fundingTransactions == null || fundingTransactions.isEmpty()) {
            return false;
        }

        for(Transaction fundingTransaction : fundingTransactions) {
            if(fundingTransaction == null || !optsIn(fundingTransaction)) {
                return false;
            }
        }

        return true;
    }

    /**
     * Whether this transaction carries a signature that opts in, which is what makes it invalid before the fork.
     *
     * Read from the signatures rather than from any declared type, because a transaction that has been broadcast carries
     * nothing else, and one assembled from several signers carries signatures a single declaration could not describe.
     */
    public static boolean optsIn(Transaction transaction) {
        for(TransactionInput input : transaction.getInputs()) {
            for(TransactionSignature signature : signatures(input)) {
                if((signature.sighashFlags & SigHash.UNIFIED_FLAG) != 0) {
                    return true;
                }
            }
        }

        return false;
    }

    /**
     * Both places a signature can sit on a signed input, read together rather than one or the other, so that no
     * arrangement of the two is missed. A push that is not a signature but decodes as one is read as a signature
     * carrying no flags, which is the harmless direction: it cannot make an input look opted in.
     */
    private static List<TransactionSignature> signatures(TransactionInput input) {
        List<TransactionSignature> signatures = new ArrayList<>();
        if(input.getScriptSig() != null) {
            signatures.addAll(input.getScriptSig().getSignatures());
        }
        if(input.hasWitness()) {
            signatures.addAll(input.getWitness().getSignatures());
        }

        return signatures;
    }
}
