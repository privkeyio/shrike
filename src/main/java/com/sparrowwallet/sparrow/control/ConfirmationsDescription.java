package com.sparrowwallet.sparrow.control;

import com.sparrowwallet.drongo.Network;
import com.sparrowwallet.drongo.protocol.Transaction;
import com.sparrowwallet.drongo.wallet.BlockTransactionHash;
import com.sparrowwallet.sparrow.MaturityEstimate;

/**
 * What to say about a coin's confirmations.
 *
 * A class of its own rather than a method on the cell, because anything nested in a JavaFX control cannot be asked
 * without a display: loading the cell runs Control's static initialiser, which wants a toolkit. The wording is a
 * function of a count, a height and a tip, so outside a cell it can be tested directly.
 */
public class ConfirmationsDescription {
    /**
     * The confirmation count stops being useful for a coinbase long before the coin is spendable.
     *
     * It is bound only as far as BLOCKS_TO_FULLY_CONFIRM and frozen after that, so on a network holding coinbases
     * for weeks it reads the same at a hundred blocks as at six thousand. The height and the tip can say how much
     * is left, so where the long rule applies they are asked instead: the block it comes free at is the verifiable
     * fact, and the duration beside it is the part a reader actually wanted.
     *
     * @param coinbaseHeight the block the coinbase was mined in, or 0 where that is not known or it is not one
     * @param currentBlockHeight the chain tip, or null where that is not known
     */
    public static String get(int confirmations, boolean isCoinbase, int coinbaseHeight, Integer currentBlockHeight) {
        if(confirmations == 0) {
            return "Unconfirmed in mempool";
        }

        int maturity = Network.get().getCoinbaseMaturity();
        if(isCoinbase && coinbaseHeight > 0 && currentBlockHeight != null
                && maturity > Transaction.COINBASE_MATURITY_THRESHOLD) {
            int spendableFrom = coinbaseHeight + maturity;
            if(currentBlockHeight + 1 < spendableFrom) {
                //Not parenthesised, since the caller already wraps all of this in brackets of its own
                return count(confirmations) + ", immature coinbase, spendable from block " + spendableFrom
                        + ", in " + MaturityEstimate.describe(spendableFrom - (currentBlockHeight + 1));
            }

            return count(confirmations);
        }

        //Without a height to measure against, only the count is left, and it is frozen at the depth that fully
        //confirms an ordinary output. On a network holding coinbases for longer than that the count can never reach
        //maturity, so a coinbase is still called immature here rather than quietly passed off as settled.
        return count(confirmations) + (isCoinbase && confirmations < maturity ? ", immature coinbase" : "");
    }

    private static String count(int confirmations) {
        if(confirmations < BlockTransactionHash.BLOCKS_TO_FULLY_CONFIRM) {
            return confirmations + " confirmation" + (confirmations == 1 ? "" : "s");
        }
        return BlockTransactionHash.BLOCKS_TO_FULLY_CONFIRM + "+ confirmations";
    }
}
