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
     * Opted in, and protected only where one of the marked signers is among those that sign.
     *
     * The hash type is opted into per signature, so a transaction carrying one opted-in signature cannot be replayed
     * whatever the rest carry, and a device that has not been marked is handed the base type rather than turned away.
     * Where the signers that have not been marked are numerous enough to meet the threshold between them, they could
     * form a quorum on their own and nothing in that transaction would opt in. Saying so is the difference between a
     * guarantee and a likelihood, and the user is the one choosing who signs.
     */
    OPTED_IN_IF_MARKED_SIGNS(null, null,
            "Protected only if one of the marked signers is among those that sign. The unmarked ones can sign this "
                    + "transaction, but a quorum made up entirely of them would not opt in."),

    /**
     * Opted in on the height compiled into this build, with nothing to corroborate it.
     *
     * An Electrum server has no getdeploymentinfo to ask, so it reports no activation height and the cross check
     * that would catch a stale build cannot run. Opting in anyway is right, since declining because a server
     * cannot answer would forgo the protection on every Electrum connection. It is a weaker position than one a
     * node has confirmed, though, and reporting the two identically hides which of them the user has.
     *
     * Still opted in, so isOptedIn covers it and the signature is the same one. Only the confidence differs, which
     * is why this carries a caveat rather than a reason: nothing declined.
     */
    OPTED_IN_UNCORROBORATED(null, null,
            "The activation height could not be checked against the connected node, which reports none. This rests "
                    + "on the height compiled into this build alone, so a build with a stale height would not be noticed here."),

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
     * The tip the server announced contradicts the schedule this build ships.
     *
     * The activation height and the header version activate together, so a tip at or above the height has to be a v2
     * header. One that is not means the server is not following the chain this build is, whether it is behind, on
     * another chain, or answering dishonestly. The announced tip arrives from a subscription and nothing
     * authenticates it, so a server that wanted the wallet to stop opting in could simply keep announcing v1 headers;
     * reported as the disagreement it is rather than as the chain not having activated, which would be a statement
     * about the chain rather than about the answer.
     */
    TIP_CONTRADICTS_SCHEDULE("the connected server announced a tip that contradicts the activation height in this build",
            "The tip it reports is at or past the activation height but is not a fork header. Check the server is "
                    + "following the fork and is fully synced before sending."),

    /**
     * Both agree on a height the chain has not reached yet.
     */
    BEFORE_ACTIVATION_HEIGHT("the chain has not reached the activation height"),

    /**
     * No keys of this wallet's own, so nothing here will produce a signature.
     */
    NO_SIGNING_KEYS("this wallet holds no keys of its own"),

    /**
     * No device in this wallet is marked as supporting the opt-in. Nothing a device sends says which firmware it
     * runs, so this is what its owner told the wallet rather than something detected, and it is off until they
     * say otherwise. One marked signer would be enough, since a transaction carrying a single opted-in signature
     * cannot be replayed whatever the rest carry, so this is reached only where none of them is.
     *
     * The only decision here with a remedy the user can act on, which is why it carries one.
     */
    EXTERNAL_SIGNER("no signer is marked as supporting it",
            "If the signer for it does support it, mark it under Replay protection in the keystore tab of the wallet settings."),

    /**
     * A keystore that neither signs from a key this wallet holds nor has a signer its owner can speak for.
     *
     * No source reaches this today: a software seed and a payment code sign here, and a device or a watch only
     * keystore can be marked. testEverySourceEitherSignsHereOrCanBeMarked pins that. It is kept rather than
     * removed because a source added later would otherwise fall into EXTERNAL_SIGNER, whose remedy names a
     * control the keystore tab would not show for it, leaving the user hunting for a checkbox that is not there.
     */
    NO_DEVICE_TO_MARK("a keystore in this wallet has no signer to speak for",
            "This keystore neither signs here nor has a signer that can be marked, so nothing in the wallet "
                    + "settings will change this.");

    private final String reason;
    private final String remedy;
    private final String caveat;

    UnifiedSigHashDecision(String reason) {
        this(reason, null, null);
    }

    UnifiedSigHashDecision(String reason, String remedy) {
        this(reason, remedy, null);
    }

    UnifiedSigHashDecision(String reason, String remedy, String caveat) {
        this.reason = reason;
        this.remedy = remedy;
        this.caveat = caveat;
    }

    public boolean isOptedIn() {
        return this == OPTED_IN || this == OPTED_IN_IF_MARKED_SIGNS || this == OPTED_IN_UNCORROBORATED;
    }

    /**
     * What qualifies an opt-in that was taken without confirmation, or null where nothing qualifies it.
     *
     * Distinct from a reason, which explains a decline. This one signed the same way a corroborated opt-in does.
     */
    public String getCaveat() {
        return caveat;
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
        //The conditional opt-in is the one answer the wallet cannot promise: it holds only where a signer that can
        //opt in is among those that actually sign, and the user chooses that after this is displayed. Saying
        //"Replay protected" here states as settled a thing that is still up to them.
        if(this == OPTED_IN_IF_MARKED_SIGNS) {
            return "Replay protected if a marked signer signs";
        }

        return summaryFor(isOptedIn());
    }

    /**
     * Whether the opt-in this reports is settled, rather than resting on who ends up signing.
     *
     * Drives the glyph: a guarantee earns the success mark, a condition does not, and the two reading the same on
     * sight was how a partially marked multisig looked identical to a fully marked one.
     */
    public boolean isGuaranteed() {
        return isOptedIn() && this != OPTED_IN_IF_MARKED_SIGNS;
    }

    /**
     * As above, for a hash type read back off a PSBT this wallet may not have built.
     */
    public static String summaryFor(boolean optedIn) {
        return optedIn ? "Replay protected" : "Not replay protected";
    }
}
