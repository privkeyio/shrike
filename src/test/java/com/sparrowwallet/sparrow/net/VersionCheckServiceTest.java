package com.sparrowwallet.sparrow.net;

import com.sparrowwallet.drongo.Version;
import com.sparrowwallet.sparrow.SparrowWallet;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

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
