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
     * No tip to read at all, which is every offline session and the moment before the first one arrives.
     *
     * Distinct from the chain having answered and said no. Reporting that here would state something about the
     * chain that was never checked, and the two are not the same thing to a user deciding whether to sign now or
     * connect first.
     */
    CHAIN_UNSEEN("the chain cannot be seen from here"),

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
     * At least one device in this wallet is not marked as supporting the opt-in. Nothing a device sends says
     * which firmware it runs, so this is what its owner told the wallet rather than something detected, and it
     * is off until they say otherwise. A PSBT carries one hash type for every signer, so one unmarked device
     * decides for the whole wallet.
     *
     * The only decision here with a remedy the user can act on, which is why it carries one.
     */
    EXTERNAL_SIGNER("a signer in this wallet is not marked as supporting it",
            "If its firmware does support it, mark the device under Replay protection in the keystore tab of the wallet settings."),

    /**
     * A keystore holding neither a key of this wallet's own nor a device, so there is nothing to mark and marking
     * it changes nothing.
     *
     * Separate from EXTERNAL_SIGNER because that remedy sends the user to a control the keystore tab only shows
     * for hardware sources. Reporting it for a watch only keystore describes a checkbox that is not there, which
     * leaves the user hunting for it rather than changing the thing that actually decides this.
     */
    NO_DEVICE_TO_MARK("a keystore in this wallet has no device to mark",
            "A watch only keystore cannot opt in whatever it is marked as. If a device signs for this wallet, "
                    + "re-import it with that device as an airgapped hardware wallet, so it can be marked under Replay protection.");

    private final String reason;
    private final String remedy;

    UnifiedSigHashDecision(String reason) {
        this(reason, null);
    }

    UnifiedSigHashDecision(String reason, String remedy) {
        this.reason = reason;
        this.remedy = remedy;
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
     * What the user can do about it, or null where nothing they control decides this. A chain that has not
     * activated is not something to act on, so saying nothing is the honest answer for most of these.
     */
    public String getRemedy() {
        return remedy;
    }

    /**
     * What the signature does, in terms of the consequence rather than the hash type that carries it.
     *
     * The sighash control names the type already, and the byte is not what a person is deciding about:
     * the choice is between a signature that cannot be replayed against nodes that have not adopted the
     * fork and commits to the amounts it spends, and one that predates both guarantees.
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
