package com.sparrowwallet.sparrow;

/**
 * How long a wait counted in blocks is likely to take.
 *
 * The rule is written in blocks, so any time put on it rests on how fast they arrive. Ten minutes a block is what
 * the protocol aims at rather than what it reaches, so the figure is rounded to whole days and hedged to say so.
 * Kept apart from anything with a display behind it, so the wording can be asked directly in a test.
 */
public class MaturityEstimate {
    private static final long MINUTES_PER_BLOCK = 10;
    private static final long MINUTES_PER_DAY = 60 * 24;

    public static String describe(int blocks) {
        if(blocks <= 0) {
            return "";
        }

        long minutes = blocks * MINUTES_PER_BLOCK;
        if(minutes < MINUTES_PER_DAY) {
            return "less than a day";
        }

        long days = Math.round((double)minutes / MINUTES_PER_DAY);
        return "about " + days + " day" + (days == 1 ? "" : "s");
    }
}
