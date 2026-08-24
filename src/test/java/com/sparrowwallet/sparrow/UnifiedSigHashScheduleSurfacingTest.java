package com.sparrowwallet.sparrow;

import com.google.common.eventbus.Subscribe;
import com.sparrowwallet.sparrow.event.UnifiedSigHashScheduleEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

/**
 * Whether a disagreement between this build's activation height and the node's reaches the user.
 *
 * The decision itself is covered by UnifiedSigHashPolicyTest; what is checked here is that declining to
 * opt in is announced rather than only logged, that it is announced once per disagreement rather than
 * once per transaction, and that it is withdrawn when the disagreement goes.
 *
 * These run without a JavaFX toolkit because EventBus dispatches synchronously on the calling thread and
 * a post with no subscriber registered is a no-op. The status bar half of this — that the indicator is
 * added to the right items, found again by type, and removed — is not reachable without a toolkit and is
 * not tested here rather than being restated by a test that would pass whatever the handler did.
 */
public class UnifiedSigHashScheduleSurfacingTest {
    private static final int RC2_HEIGHT = 149537;
    private static final int RC1_HEIGHT = 149460;

    private final EventCollector collector = new EventCollector();

    public static class EventCollector {
        private final List<UnifiedSigHashScheduleEvent> events = new ArrayList<>();

        @Subscribe
        public void schedule(UnifiedSigHashScheduleEvent event) {
            events.add(event);
        }
    }

    @BeforeEach
    public void register() {
        //Cleared before registering, so the resolved event that clearing posts is not collected
        AppServices.clearNodeHardforkHeight();
        collector.events.clear();
        EventManager.get().register(collector);
    }

    @AfterEach
    public void unregister() {
        EventManager.get().unregister(collector);
        AppServices.clearNodeHardforkHeight();
    }

    private List<UnifiedSigHashScheduleEvent> disagreements() {
        return collector.events.stream().filter(UnifiedSigHashScheduleEvent::isDisagreement).toList();
    }

    @Test
    public void testNodeScheduleThisBuildDoesNotHaveIsSurfaced() {
        AppServices.isUnifiedSigHashActive(null, RC2_HEIGHT, RC2_HEIGHT);

        Assertions.assertEquals(1, disagreements().size(), "Declining to opt in has to reach the user, not only the log");
        UnifiedSigHashScheduleEvent event = disagreements().get(0);
        Assertions.assertTrue(event.isScheduleUnknown());
        Assertions.assertNull(event.getWalletActivationHeight());
        Assertions.assertEquals(RC2_HEIGHT, event.getNodeActivationHeight());
    }

    @Test
    public void testDisagreeingHeightsAreSurfaced() {
        AppServices.isUnifiedSigHashActive(RC2_HEIGHT, RC1_HEIGHT, RC2_HEIGHT);

        Assertions.assertEquals(1, disagreements().size());
        UnifiedSigHashScheduleEvent event = disagreements().get(0);
        Assertions.assertFalse(event.isScheduleUnknown(), "Both heights are known here, they simply differ");
        Assertions.assertEquals(RC2_HEIGHT, event.getWalletActivationHeight());
        Assertions.assertEquals(RC1_HEIGHT, event.getNodeActivationHeight());
    }

    @Test
    public void testAgreementSurfacesNothing() {
        Assertions.assertTrue(AppServices.isUnifiedSigHashActive(RC2_HEIGHT, RC2_HEIGHT, RC2_HEIGHT));
        Assertions.assertTrue(collector.events.isEmpty(), "Agreement is not something to show the user");
    }

    /**
     * The Electrum bound. An Electrum server reports no height, so there is nothing to disagree with and
     * nothing to show, however stale this build's own height is.
     */
    @Test
    public void testNoNodeHeightSurfacesNothing() {
        AppServices.isUnifiedSigHashActive(null, null, RC2_HEIGHT);
        AppServices.isUnifiedSigHashActive(RC2_HEIGHT, null, 1);

        Assertions.assertTrue(collector.events.isEmpty(), "With no height from the node there is no disagreement to report");
    }

    @Test
    public void testDisagreementIsSurfacedOncePerConnectionNotPerTransaction() {
        for(int i = 0; i < 5; i++) {
            AppServices.isUnifiedSigHashActive(RC2_HEIGHT, RC1_HEIGHT, RC2_HEIGHT);
        }

        Assertions.assertEquals(1, disagreements().size(), "A stale build disagrees on every send, and the indicator is already up");
    }

    @Test
    public void testForgettingTheNodeHeightWithdrawsTheIndicator() {
        AppServices.isUnifiedSigHashActive(RC2_HEIGHT, RC1_HEIGHT, RC2_HEIGHT);
        Assertions.assertEquals(1, disagreements().size());

        AppServices.clearNodeHardforkHeight();

        UnifiedSigHashScheduleEvent last = collector.events.get(collector.events.size() - 1);
        Assertions.assertFalse(last.isDisagreement(), "The disagreement was with a node that has gone");
        Assertions.assertNull(last.getDescription());
    }

    @Test
    public void testReconnectingSurfacesTheDisagreementAgain() {
        AppServices.isUnifiedSigHashActive(RC2_HEIGHT, RC1_HEIGHT, RC2_HEIGHT);
        AppServices.clearNodeHardforkHeight();
        AppServices.isUnifiedSigHashActive(RC2_HEIGHT, RC1_HEIGHT, RC2_HEIGHT);

        Assertions.assertEquals(2, disagreements().size(), "A new connection is a new occasion to say so");
    }

    @Test
    public void testADifferentDisagreementIsSurfacedSeparately() {
        AppServices.isUnifiedSigHashActive(RC2_HEIGHT, RC1_HEIGHT, RC2_HEIGHT);
        AppServices.isUnifiedSigHashActive(RC2_HEIGHT, 149999, RC2_HEIGHT);

        Assertions.assertEquals(2, disagreements().size(), "A different pair of heights is a different disagreement");
        Assertions.assertEquals(149999, disagreements().get(1).getNodeActivationHeight());
    }

    @Test
    public void testUnknownScheduleDescriptionNamesTheNetworkAndTheNodeHeight() {
        String description = UnifiedSigHashScheduleEvent.scheduleUnknown(RC2_HEIGHT, "Mainnet").getDescription();

        Assertions.assertTrue(description.contains("Mainnet"), description);
        Assertions.assertTrue(description.contains(String.valueOf(RC2_HEIGHT)), description);
        Assertions.assertTrue(description.contains("replay protection"), description);
    }

    @Test
    public void testMismatchDescriptionNamesBothHeights() {
        String description = UnifiedSigHashScheduleEvent.scheduleMismatch(RC2_HEIGHT, RC1_HEIGHT).getDescription();

        Assertions.assertTrue(description.contains(String.valueOf(RC2_HEIGHT)), description);
        Assertions.assertTrue(description.contains(String.valueOf(RC1_HEIGHT)), description);
    }

    @Test
    public void testResolvedCarriesNothingToShow() {
        UnifiedSigHashScheduleEvent resolved = UnifiedSigHashScheduleEvent.resolved();

        Assertions.assertFalse(resolved.isDisagreement());
        Assertions.assertFalse(resolved.isScheduleUnknown());
        Assertions.assertNull(resolved.getDescription());
    }
}
