package com.sparrowwallet.sparrow.net;

import com.sparrowwallet.drongo.Network;
import com.sparrowwallet.sparrow.BlockSummary;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Fetches fee rates and a block summary through the wallet's own code, against the live host.
 *
 * The unit tests assert which sources are offered. This asserts the one that is left actually answers, which is
 * the part that would have caught the original defect: mempool.space answered too, just for the other chain.
 */
public class LiveFeeRatesHarness {
    public static void main(String[] args) throws Exception {
        Network.set(Network.MAINNET);

        Map<Integer, Double> defaults = new LinkedHashMap<>();
        defaults.put(1, 1.0);
        defaults.put(3, 1.0);
        defaults.put(6, 1.0);

        FeeRatesSource source = FeeRatesSource.getDefault();
        System.out.println("SOURCE=" + source.getName());

        Map<Integer, Double> rates = source.getBlockTargetFeeRates(defaults);
        System.out.println("RATES=" + rates);

        Map<Integer, BlockSummary> recent = source.getRecentBlockSummaries();
        Integer tip = recent.keySet().stream().max(Integer::compareTo).orElse(null);
        System.out.println("TIP_HEIGHT=" + tip);
        System.out.println("BLOCKS_RETURNED=" + recent.size());
    }
}
