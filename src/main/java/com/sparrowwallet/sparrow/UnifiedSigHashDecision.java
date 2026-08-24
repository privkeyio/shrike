package com.sparrowwallet.sparrow;

/**
 * Whether a transaction being built opts in to the unified signature hash, and where it does not, why.
 *
 * The reason exists because it is only knowable where the decision is made. A PSBT records the hash type
 * its inputs carry and nothing about what was considered, so a PSBT that did not opt in looks the same
 * whether this wallet declined or a co-signer built it on a chain that has no fork at all. Anything
 * wanting to show a reason has to be handed one from here rather than working it out again later.
 */
public enum UnifiedSigHashDecision {
    OPTED_IN(null),

    /**
     * The tip is not a v2 header, so the proof of work change is not live and neither is this.
     */
    CHAIN_NOT_ACTIVATED("the chain has not activated it"),

    /**
     * A v2 tip but no height to compare against, so there is nothing to decide on.
     */
    CHAIN_HEIGHT_UNKNOWN("the current block height is not known"),

    /**
     * This build ships no activation height for the network. Declining here is a stale table rather than
     * a judgement, which is why it is worth saying rather than leaving the user to infer it.
     */
    BUILD_HAS_NO_SCHEDULE("this build has no activation height for this network"),

    /**
     * This build and the connected node disagree on the height, and there is no way to tell which is right.
     */
    SCHEDULE_MISMATCH("this build and the connected node disagree on the activation height"),

    /**
     * Both agree on a height the chain has not reached yet.
     */
    BEFORE_ACTIVATION_HEIGHT("the chain has not reached the activation height"),

    /**
     * No keys of this wallet's own, so nothing here will produce a signature.
     */
    NO_SIGNING_KEYS("this wallet holds no keys of its own"),

    /**
     * At least one keystore is not a software seed. A device that has not implemented the opt-in either
     * refuses the hash type or signs the legacy message while the PSBT declares the new one, and a PSBT
     * carries one hash type for every signer, so one such keystore decides for the whole wallet.
     */
    EXTERNAL_SIGNER("a signer in this wallet does not support it");

    private final String reason;

    UnifiedSigHashDecision(String reason) {
        this.reason = reason;
    }

    public boolean isOptedIn() {
        return this == OPTED_IN;
    }

    /**
     * Why the wallet declined, as a clause. Null where it did not.
     */
    public String getReason() {
        return reason;
    }

    /**
     * What the signature does, in terms of the consequence rather than the hash type that carries it.
     *
     * The sighash control names the type already, and the byte is not what a person is deciding about:
     * the choice is between a signature that cannot be replayed on another chain and commits to the
     * amounts it spends, and one that predates both guarantees.
     */
    public String getSummary() {
        return summaryFor(isOptedIn());
    }

    /**
     * As above, for a hash type read back off a PSBT this wallet may not have built.
     */
    public static String summaryFor(boolean optedIn) {
        return optedIn ? "Replay protected" : "Not replay protected";
    }
}
