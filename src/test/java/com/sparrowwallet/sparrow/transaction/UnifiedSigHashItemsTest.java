package com.sparrowwallet.sparrow.transaction;

import com.sparrowwallet.drongo.protocol.SigHash;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;

/**
 * The sighash control's item list, which has to contain whatever it displays.
 *
 * A ComboBox whose value is absent from its items shows that value until the first interaction and then
 * replaces it with a listed one. For an opted-in PSBT the listed types were all legacy, so touching the
 * control dropped the opt-in and there was no item to select to get it back. These call the mapping the
 * control uses rather than driving the control itself, which needs a JavaFX toolkit.
 */
public class UnifiedSigHashItemsTest {
    private List<SigHash> itemsFor(List<SigHash> signingTypes, SigHash psbtSigHash) {
        return HeadersController.unifiedItemsFor(signingTypes, psbtSigHash);
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

    /**
     * The recommendation has to survive the mapping too. It is marked by identity against the items, so a
     * recommended type that is not among them marks nothing and the user is shown a list with no
     * recommendation at all.
     *
     * This is what caught the taproot case: DEFAULT and ALL both map to UNIFIED_ALL, whose base reads
     * back as ALL, so testing the base against DEFAULT never matched for an opted-in taproot PSBT.
     */
    @Test
    public void testTheRecommendedTypeIsAmongTheItems() {
        for(boolean taprootInput : List.of(true, false)) {
            SigHash requiredSigHash = taprootInput ? SigHash.DEFAULT : SigHash.ALL;
            List<SigHash> signingTypes = taprootInput ? SigHash.TAPROOT_SIGNING_TYPES : SigHash.LEGACY_SIGNING_TYPES;

            for(SigHash psbtSigHash : List.of(SigHash.UNIFIED_ALL, SigHash.ALL)) {
                SigHash recommended = HeadersController.recommendedSigHashFor(requiredSigHash, psbtSigHash);
                Assertions.assertTrue(itemsFor(signingTypes, psbtSigHash).contains(recommended),
                        "Nothing would be marked as recommended for " + psbtSigHash + " on a "
                                + (taprootInput ? "taproot" : "legacy") + " input");
                Assertions.assertEquals(psbtSigHash.isUnified(), recommended.isUnified(),
                        "The recommendation must follow the PSBT into its opted-in form");
            }
        }

        Assertions.assertEquals(SigHash.UNIFIED_ALL,
                HeadersController.recommendedSigHashFor(SigHash.DEFAULT, SigHash.UNIFIED_ALL),
                "An opted-in taproot PSBT recommends the type DEFAULT collapses into");
    }

    /**
     * Every offered type has to be one the fork actually defines, or the control hands the user a way to
     * build a transaction the network will not accept.
     *
     * SignatureHashUnified takes an opted-in hash type only when the low bits are ALL, NONE or SINGLE and
     * no bit falls outside that, ANYONECANPAY and the opt-in itself; the same range is enforced on the
     * ECDSA side by IsDefinedHashtypeSignature. That leaves exactly six bytes. The two the opt-in bit can
     * otherwise reach, 0x20 and 0xa0, carry no output type and are refused by both.
     */
    @Test
    public void testEveryOfferedTypeIsDefinedByTheFork() {
        for(List<SigHash> signingTypes : List.of(SigHash.LEGACY_SIGNING_TYPES, SigHash.TAPROOT_SIGNING_TYPES)) {
            List<SigHash> items = itemsFor(signingTypes, SigHash.UNIFIED_ALL);
            for(SigHash item : items) {
                int value = item.intValue();
                Assertions.assertEquals(0, value & ~(0x1f | 0x80 | SigHash.UNIFIED_FLAG),
                        item + " sets a bit the unified signature hash does not define");
                int outputType = value & 0x1f;
                Assertions.assertTrue(outputType >= SigHash.ALL.intValue() && outputType <= SigHash.SINGLE.intValue(),
                        item + " has no output type the unified signature hash accepts");
            }

            Assertions.assertEquals(SigHash.UNIFIED_SIGNING_TYPES, items,
                    "The offered types must be exactly the six the fork defines");
        }
    }

    /**
     * The wallet may produce ANYONECANPAY, because that is the type the user picked and refusing it would just be a
     * device that mysteriously will not sign. What it must never do is reach for it on its own account: a signature
     * that stands alone is the one legacy type that survives being lifted onto the chain that kept SHA256d, so the
     * user has to have chosen it. This pins the one type the send screen puts forward by itself.
     */
    @Test
    public void testTheRecommendedTypeIsNeverAnyoneCanPay() {
        for(SigHash required : List.of(SigHash.ALL, SigHash.DEFAULT)) {
            for(SigHash declared : List.of(SigHash.ALL, SigHash.DEFAULT, SigHash.UNIFIED_ALL,
                    SigHash.ANYONECANPAY_ALL, SigHash.UNIFIED_ANYONECANPAY_ALL)) {
                SigHash recommended = HeadersController.recommendedSigHashFor(required, declared);
                Assertions.assertFalse(recommended.anyoneCanPay(),
                        "recommended " + recommended + " where the PSBT declared " + declared);
            }
        }
    }
}
