package com.sparrowwallet.sparrow;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Windows decides whether installing one release over another is an upgrade by comparing ProductVersion, which
 * build.gradle derives rather than taking from the version this fork reports. Upstream never needs the derivation
 * because its own version moves every release; this fork pins the version to the upstream base, so a build that
 * stopped deriving would install over its predecessor and change nothing, silently and only on Windows.
 */
public class WindowsProductVersionTest {
    private static final Path BUILD_GRADLE = Path.of("build.gradle");

    /** Mirrors the derivation in build.gradle: MAJOR.MINOR.(patch * 1000 + release). */
    private static int[] productVersion(String base, int release) {
        String[] parts = base.split("\\.");
        return new int[] {Integer.parseInt(parts[0]), Integer.parseInt(parts[1]),
                Integer.parseInt(parts[2]) * 1000 + release};
    }

    private static int compare(int[] a, int[] b) {
        for(int i = 0; i < 3; i++) {
            if(a[i] != b[i]) {
                return Integer.compare(a[i], b[i]);
            }
        }
        return 0;
    }

    @Test
    public void everyReleaseOutranksTheOneBeforeIt() {
        List<String[]> ordered = List.of(
                new String[] {"2.5.5", "23"}, new String[] {"2.5.5", "24"}, new String[] {"2.5.5", "99"},
                //A rebase onto a later upstream release, whether the counter keeps counting or restarts
                new String[] {"2.5.6", "1"}, new String[] {"2.5.6", "100"},
                new String[] {"2.5.10", "1"}, new String[] {"2.6.0", "1"}, new String[] {"3.0.0", "1"});

        for(int i = 1; i < ordered.size(); i++) {
            int[] earlier = productVersion(ordered.get(i - 1)[0], Integer.parseInt(ordered.get(i - 1)[1]));
            int[] later = productVersion(ordered.get(i)[0], Integer.parseInt(ordered.get(i)[1]));
            Assertions.assertTrue(compare(later, earlier) > 0,
                    ordered.get(i)[0] + "-blake2b." + ordered.get(i)[1] + " must outrank "
                            + ordered.get(i - 1)[0] + "-blake2b." + ordered.get(i - 1)[1]);
        }
    }

    @Test
    public void thisReleaseOutranksWhatExistingInstallsReport() {
        //Every release up to and including 2.5.5-blake2b.24 shipped an installer reporting a bare 2.5.5
        int[] installed = new int[] {2, 5, 5};
        int[] now = productVersion(SparrowWallet.APP_VERSION, releaseNumber());

        Assertions.assertTrue(compare(now, installed) > 0,
                "an existing install reporting 2.5.5 would not be upgraded by this build");
    }

    @Test
    public void theBuildFieldStaysWithinWhatAnMsiAllows() {
        int[] now = productVersion(SparrowWallet.APP_VERSION, releaseNumber());
        Assertions.assertTrue(now[0] <= 255 && now[1] <= 255, "MAJOR and MINOR are limited to 255");
        Assertions.assertTrue(now[2] <= 65535, "an MSI build field is limited to 65535, got " + now[2]);
    }

    @Test
    public void theBuildStillDerivesTheProductVersion() throws IOException {
        //The derivation is only exercised on a Windows runner, so losing it would not fail any other build
        Assertions.assertTrue(Files.exists(BUILD_GRADLE), "expected to run from the project root");
        String build = Files.readString(BUILD_GRADLE);

        Assertions.assertTrue(build.contains("ext.windowsProductVersion"),
                "build.gradle no longer derives a Windows ProductVersion");
        Assertions.assertTrue(build.contains("appVersion = windowsProductVersion"),
                "the Windows installer no longer takes the derived ProductVersion, so upgrades stop working there");
    }

    private static int releaseNumber() {
        Matcher matcher = Pattern.compile("-blake2b\\.(\\d+)").matcher(SparrowWallet.APP_VERSION_SUFFIX);
        Assertions.assertTrue(matcher.find(), "could not read the release number from " + SparrowWallet.APP_VERSION_SUFFIX);
        return Integer.parseInt(matcher.group(1));
    }
}
