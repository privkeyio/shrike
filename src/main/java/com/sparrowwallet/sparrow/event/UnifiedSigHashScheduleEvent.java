package com.sparrowwallet.sparrow.event;

/**
 * A disagreement between the unified signature hash activation height this build ships and the one the
 * connected node reports, or word that there is no longer one.
 *
 * This is a state rather than an occurrence. The wallet keeps signing the legacy way for as long as the
 * disagreement stands, which may be every transaction for the life of the connection, so it is carried
 * by a persistent indicator rather than by StatusEvent, whose text clears itself after a timeout and is
 * replaced by the next event to arrive.
 */
public class UnifiedSigHashScheduleEvent {
    private final Integer walletActivationHeight;
    private final Integer nodeActivationHeight;
    private final String network;

    private UnifiedSigHashScheduleEvent(Integer walletActivationHeight, Integer nodeActivationHeight, String network) {
        this.walletActivationHeight = walletActivationHeight;
        this.nodeActivationHeight = nodeActivationHeight;
        this.network = network;
    }

    /**
     * The node schedules activation at a height this build has no counterpart for on the network.
     */
    public static UnifiedSigHashScheduleEvent scheduleUnknown(int nodeActivationHeight, String network) {
        return new UnifiedSigHashScheduleEvent(null, nodeActivationHeight, network);
    }

    /**
     * Both have a height and they differ.
     */
    public static UnifiedSigHashScheduleEvent scheduleMismatch(int walletActivationHeight, int nodeActivationHeight) {
        return new UnifiedSigHashScheduleEvent(walletActivationHeight, nodeActivationHeight, null);
    }

    /**
     * No disagreement stands, so any indicator should be taken down. Posted when the height is forgotten,
     * which happens on disconnect: the value describes the connection it came from.
     */
    public static UnifiedSigHashScheduleEvent resolved() {
        return new UnifiedSigHashScheduleEvent(null, null, null);
    }

    public boolean isDisagreement() {
        return nodeActivationHeight != null;
    }

    /**
     * Whether this build has no height for the network at all, as opposed to one that disagrees.
     */
    public boolean isScheduleUnknown() {
        return isDisagreement() && walletActivationHeight == null;
    }

    public Integer getWalletActivationHeight() {
        return walletActivationHeight;
    }

    public Integer getNodeActivationHeight() {
        return nodeActivationHeight;
    }

    /**
     * The disagreement in full, for a tooltip. Null where there is none.
     */
    public String getDescription() {
        if(!isDisagreement()) {
            return null;
        }

        if(isScheduleUnknown()) {
            return "This build has no unified signature hash activation height for " + network
                    + ", but the connected node schedules one at height " + nodeActivationHeight
                    + ". Transactions are being signed without replay protection until this build is updated.";
        }

        return "This build expects the unified signature hash to activate at height " + walletActivationHeight
                + ", but the connected node reports " + nodeActivationHeight
                + ". Transactions are being signed without replay protection until the two agree.";
    }
}
