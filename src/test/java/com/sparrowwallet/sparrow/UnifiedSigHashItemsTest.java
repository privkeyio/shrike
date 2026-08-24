package com.sparrowwallet.sparrow;

import com.sparrowwallet.drongo.protocol.SigHash;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;

/**
 * The sighash control's item list, which has to contain whatever it displays.
 *
 * A ComboBox whose value is absent from its items shows that value until the first interaction and then
 * replaces it with a listed one. For an opted-in PSBT the listed types were all legacy, so touching the
 * control dropped the opt-in and there was no item to select to get it back. These pin the mapping the
 * control uses rather than the control itself, which needs a JavaFX toolkit.
 */
public class UnifiedSigHashItemsTest {
    private List<SigHash> itemsFor(List<SigHash> signingTypes, SigHash psbtSigHash) {
        return psbtSigHash.isUnified() ? signingTypes.stream().map(SigHash::withUnified).distinct().toList() : signingTypes;
    }

    @Test
    public void testAnOptedInValueIsAmongTheItems() {
        for(List<SigHash> signingTypes : List.of(SigHash.LEGACY_SIGNING_TYPES, SigHash.TAPROOT_SIGNING_TYPES)) {
            List<SigHash> items = itemsFor(signingTypes, SigHash.UNIFIED_ALL);
            Assertions.assertTrue(items.contains(SigHash.UNIFIED_ALL),
                    "The displayed value must be selectable, or the first interaction silently drops it");
            Assertions.assertTrue(items.stream().allMatch(SigHash::isUnified),
                    "An opted-in PSBT must not offer a mix that drops the opt-in without saying so");
        }
    }

    /**
     * The reverse: a PSBT that did not opt in must not be offered the opt-in here. Opting in on a chain
     * where the rules do not apply produces a transaction the network refuses outright.
     */
    @Test
    public void testALegacyPsbtIsNotOfferedTheOptIn() {
        for(List<SigHash> signingTypes : List.of(SigHash.LEGACY_SIGNING_TYPES, SigHash.TAPROOT_SIGNING_TYPES)) {
            List<SigHash> items = itemsFor(signingTypes, SigHash.ALL);
            Assertions.assertTrue(items.stream().noneMatch(SigHash::isUnified));
            Assertions.assertEquals(signingTypes, items, "A legacy PSBT's choices must be unchanged");
        }
    }

    /**
     * Every choice keeps an opted-in counterpart, so changing what the signature covers never forces the
     * user to give up the opt-in as a side effect.
     *
     * DEFAULT is the one exception and collapses into UNIFIED_ALL rather than gaining a form of its own:
     * it means "append no hash type byte", so there is nothing for the opt-in bit to live in. That is
     * also why the taproot list is one shorter once mapped.
     */
    @Test
    public void testEveryChoiceSurvivesTheMappingExceptDefault() {
        for(List<SigHash> signingTypes : List.of(SigHash.LEGACY_SIGNING_TYPES, SigHash.TAPROOT_SIGNING_TYPES)) {
            List<SigHash> items = itemsFor(signingTypes, SigHash.UNIFIED_ALL);
            for(SigHash signingType : signingTypes) {
                SigHash expected = signingType == SigHash.DEFAULT ? SigHash.ALL : signingType;
                Assertions.assertTrue(items.stream().anyMatch(item -> item.withoutUnified() == expected),
                        signingType + " has no opted-in counterpart in the list");
            }
        }

        Assertions.assertEquals(SigHash.UNIFIED_ALL, SigHash.DEFAULT.withUnified(),
                "DEFAULT cannot carry the opt-in, so it must become the type that means the same thing");
        Assertions.assertEquals(SigHash.LEGACY_SIGNING_TYPES.size(),
                itemsFor(SigHash.TAPROOT_SIGNING_TYPES, SigHash.UNIFIED_ALL).size(),
                "The taproot list loses exactly DEFAULT when mapped");
    }
}
