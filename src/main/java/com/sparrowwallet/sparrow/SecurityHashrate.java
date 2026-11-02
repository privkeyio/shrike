package com.sparrowwallet.sparrow;

import com.sparrowwallet.drongo.Network;
import com.sparrowwallet.drongo.Utils;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.Locale;

/**
 * The share of the network hashrate that matches the coins a wallet holds: the network hashrate times the balance,
 * divided by the coins issued so far.
 *
 * Read from the chain alone. The network hashrate is the work a block at the tip's target is expected to take, being
 * 2^256 / (target + 1) as in GetBlockProof, spread over the ten minutes a block is aimed at. The target is retargeted
 * from the time the last period took, so this is a two week average rather than a reading of the last few blocks.
 * The coins issued are the subsidies of every block to the tip, halving as the chain does.
 * Kept apart from anything with a display behind it, so the figures can be asked directly in a test.
 */
public class SecurityHashrate {
    private static final long TARGET_SPACING_SECONDS = 600;
    private static final long INITIAL_SUBSIDY = 50 * 100_000_000L;
    private static final BigInteger TWO_TO_256 = BigInteger.ONE.shiftLeft(256);
    private static final String[] UNITS = {"H/s", "kH/s", "MH/s", "GH/s", "TH/s", "PH/s", "EH/s", "ZH/s"};

    /** The network hashrate a block at the given compact target implies, in hashes per second. */
    public static double getNetworkHashrate(long compactBits) {
        BigInteger target = Utils.decodeCompactBits(compactBits);
        if(target.signum() <= 0) {
            return 0;
        }

        BigInteger work = TWO_TO_256.divide(target.add(BigInteger.ONE));
        return new BigDecimal(work).divide(BigDecimal.valueOf(TARGET_SPACING_SECONDS), 0, RoundingMode.HALF_UP).doubleValue();
    }

    /** The satoshis issued by the subsidies of every block from genesis to the given height. */
    public static long getIssuedSupply(int height) {
        long interval = Network.get() == Network.REGTEST ? 150 : 210_000;
        long supply = 0;
        long start = 0;
        for(int halvings = 0; halvings < 64 && start <= height; halvings++, start += interval) {
            supply += Math.min(height - start + 1, interval) * (INITIAL_SUBSIDY >> halvings);
        }

        return supply;
    }

    /** The hashrate matching a balance in satoshis, for a tip at the given height with the given compact target. */
    public static double getSecurityHashrate(long balance, int height, long compactBits) {
        long supply = getIssuedSupply(height);
        if(balance <= 0 || supply <= 0) {
            return 0;
        }

        return getNetworkHashrate(compactBits) * balance / supply;
    }

    /** A hashrate in the largest unit that keeps it at one or more, to three significant figures. */
    public static String format(double hashesPerSecond) {
        int unit = 0;
        while(hashesPerSecond >= 1000 && unit < UNITS.length - 1) {
            hashesPerSecond /= 1000;
            unit++;
        }

        String pattern = hashesPerSecond >= 100 || unit == 0 ? "%.0f %s" : (hashesPerSecond >= 10 ? "%.1f %s" : "%.2f %s");
        return String.format(Locale.ROOT, pattern, hashesPerSecond, UNITS[unit]);
    }
}
