package com.sparrowwallet.sparrow.net;

import com.sparrowwallet.drongo.Version;
import com.sparrowwallet.sparrow.SparrowWallet;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;

public class VersionCheckServiceTest {
    @Test
    public void aLaterCounterIsNewer() {
        Assertions.assertTrue(VersionCheckService.isNewer("2.5.5-blake2b.24", "2.5.5-blake2b.23"));
        Assertions.assertFalse(VersionCheckService.isNewer("2.5.5-blake2b.23", "2.5.5-blake2b.24"));
        Assertions.assertFalse(VersionCheckService.isNewer("2.5.5-blake2b.24", "2.5.5-blake2b.24"));
    }

    @Test
    public void theCounterIsReadNumericallyRatherThanAsText() {
        //9 sorts after 10 as text, which is the bug a string compare would ship
        Assertions.assertTrue(VersionCheckService.isNewer("2.5.5-blake2b.10", "2.5.5-blake2b.9"));
        Assertions.assertFalse(VersionCheckService.isNewer("2.5.5-blake2b.9", "2.5.5-blake2b.10"));
    }

    @Test
    public void theBaseVersionOutranksTheCounter() {
        //A rebase onto a later upstream release is newer however far the counter has run on the older base
        Assertions.assertTrue(VersionCheckService.isNewer("2.5.6-blake2b.1", "2.5.5-blake2b.30"));
        Assertions.assertFalse(VersionCheckService.isNewer("2.5.5-blake2b.30", "2.5.6-blake2b.1"));
    }

    /**
     * Drongo's Version cuts a version string at its first non-digit, so every release of this fork reads as the
     * upstream base it is built on. Restoring upstream's check unchanged would have compared 2.5.5 against 2.5.5
     * forever and never once notified anybody, which is why the comparison is made here instead.
     */
    @Test
    public void drongosVersionCannotTellTwoReleasesOfThisForkApart() {
        Version a = new Version("2.5.5-blake2b.23");
        Version b = new Version("2.5.5-blake2b.24");
        Assertions.assertEquals(0, a.compareTo(b), "if this ever fails, the local comparison can be retired");

        Assertions.assertTrue(VersionCheckService.isNewer("2.5.5-blake2b.24", "2.5.5-blake2b.23"));
    }

    @Test
    public void thisReleaseIsNotOlderThanItself() {
        String current = SparrowWallet.APP_VERSION + SparrowWallet.APP_VERSION_SUFFIX;
        Assertions.assertFalse(VersionCheckService.isNewer(current, current),
                "the running version must never advertise itself as an update");
    }

    @Test
    public void aFeedCannotPutItsOwnTextInTheStatusBar() {
        //The version is rendered into the wallet chrome as "Shrike <version> available", so anything the feed can
        //smuggle through becomes wallet-authored text on screen. Validating the numeric head is not enough, and
        //neither is a permissive suffix alphabet: the last two here read as a version but say what an attacker chose.
        for(String hostile : List.of(
                "2.5.6 CRITICAL: restore your seed at evil.example",
                "2.5.6 https://evil.example",
                "2.5.6\nrestore your seed",
                "2.5.6-blake2b.25 <b>urgent</b>",
                "2.5.6-seed.shrike.support",
                "2.5.6-CALL.1800.555.0199")) {
            Assertions.assertFalse(VersionCheckService.isNewer(hostile, "2.5.5-blake2b.24"), "accepted: " + hostile);
            Assertions.assertFalse(VersionCheckService.isWellFormed(hostile), "well formed: " + hostile);
        }
    }

    @Test
    public void onlyTheShapeThisForkPublishesIsAVersion() {
        //Whitespace, a stray carriage return, a v prefix, non-ASCII digits, and characters that reorder or hide
        //themselves next to the ones around them
        for(String refused : List.of(
                " 2.5.6", "2.5.6 ", "2.5.6\r", "2.5.6\t", "v2.5.6", "2.5.6-", "-2.5.6", "2..5.6", "",
                "\uFF12.\uFF15.\uFF16", "\u0662.\u0665.\u0666",
                "2.5.6-blake\u202E2b.25", "2.5.6-blake\u200D2b.25", "2.5.6\u0000",
                "2.5.6-rc.1", "2.5.6-blake2b.25.1", "2.5.6.7.8-blake2b.1")) {
            Assertions.assertFalse(VersionCheckService.isWellFormed(refused), "accepted: " + escape(refused));
            Assertions.assertFalse(VersionCheckService.isNewer(refused, "2.5.5-blake2b.24"), "newer: " + escape(refused));
        }
    }

    @Test
    public void aComponentTooLargeForAnIntIsNotAnUpdate() {
        //Refused by the shape rather than left to overflow inside the comparison
        Assertions.assertFalse(VersionCheckService.isWellFormed("2.5.99999999999999999999"));
        Assertions.assertFalse(VersionCheckService.isNewer("2.5.99999999999999999999", "2.5.5-blake2b.24"));
    }

    @Test
    public void theLengthCapIsWhereItSays() {
        //Shape-valid at both lengths, so only the cap can be what refuses the longer one
        String atLimit = "999999.999999.999999-blake2b.999";
        Assertions.assertEquals(32, atLimit.length());
        Assertions.assertTrue(VersionCheckService.isWellFormed(atLimit), "32 characters must be accepted");
        Assertions.assertFalse(VersionCheckService.isWellFormed(atLimit + "9"), "33 characters must be refused");
    }

    /**
     * The property behind every case above: an accepted version can carry no character an attacker chose. Hand
     * picked hostile strings were twice not enough here, so this sweeps the whole BMP a character at a time and
     * asserts that nothing outside this fork's own shape survives, whatever it is.
     */
    @Test
    public void noAcceptedVersionCanCarryAChosenCharacter() {
        String valid = "2.5.5-blake2b.24";

        for(int c = 0; c <= 0xFFFF; c++) {
            String ch = String.valueOf((char)c);

            //Appended, prepended, and substituted into the middle of an otherwise valid version
            for(String candidate : List.of(valid + ch, ch + valid, valid.substring(0, 6) + ch + valid.substring(6))) {
                if(VersionCheckService.isWellFormed(candidate)) {
                    Assertions.assertTrue(candidate.matches("[0-9.]*(-blake2b\\.[0-9]+)?[0-9.]*"),
                            "accepted a version carrying U+" + String.format("%04X", c) + ": " + escape(candidate));
                }
            }
        }
    }

    @Test
    public void everyAcceptedVersionIsMadeOfDigitsDotsAndTheWordBlake2b() {
        //Whatever the feed sends, what reaches the status bar is drawn from this alphabet only
        for(String candidate : List.of(
                "2.5.5", "2.5.5-blake2b.24", "2.5.6-blake2b.1", "999999.999999.999999-blake2b.999",
                "2.5.6-seed.shrike.support", "2.5.6-CALL.1800.555.0199", "2.5.6 restore your seed",
                "2.5.6-rc.1", "v2.5.6", "2.5.6-BLAKE2B.1", "2.5.6-blake2b", "2.5.6-blake2b.")) {
            if(VersionCheckService.isWellFormed(candidate)) {
                //Remove the one literal word the shape allows; what remains must be digits, dots and the hyphen
                String remainder = candidate.replace("blake2b", "");
                Assertions.assertTrue(remainder.matches("[0-9.\\-]*"),
                        "accepted a version with characters outside the published shape: " + candidate);
            }
        }
    }

    private static String escape(String s) {
        StringBuilder out = new StringBuilder();
        for(char c : s.toCharArray()) {
            out.append(c < 0x20 || c > 0x7e ? String.format("\\u%04X", (int)c) : c);
        }
        return out.toString();
    }

    @Test
    public void theVersionsWeActuallyPublishAreAccepted() {
        for(String good : List.of("2.5.5", "2.5.5-blake2b.24", "2.5.6-blake2b.1", "2.5.5-blake2b.100")) {
            Assertions.assertTrue(VersionCheckService.isWellFormed(good), "refused: " + good);
        }
    }

    @Test
    public void aVersionItCannotReadIsNotAnUpdate() {
        Assertions.assertFalse(VersionCheckService.isNewer("not-a-version", "2.5.5-blake2b.24"));
        Assertions.assertFalse(VersionCheckService.isNewer("", "2.5.5-blake2b.24"));
    }

    @Test
    public void aReleaseWithNoCounterSortsUnderOneWithIt() {
        Assertions.assertTrue(VersionCheckService.isNewer("2.5.5-blake2b.1", "2.5.5"));
        Assertions.assertFalse(VersionCheckService.isNewer("2.5.5", "2.5.5-blake2b.1"));
    }
}
