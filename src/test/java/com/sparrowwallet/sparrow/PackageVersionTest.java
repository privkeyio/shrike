package com.sparrowwallet.sparrow;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The packaged version has to move when the fork release moves.
 *
 * Every build declared Debian revision 1, so 2.5.5-blake2b.7 and 2.5.5-blake2b.11 both packaged as 2.5.5-1.
 * apt compares those as equal and reports no upgrade, leaving users on an old build with nothing to tell them.
 * The templates now take the revision from the release number, and these pin that so it cannot be hardcoded
 * back to a constant.
 */
public class PackageVersionTest {
    private static final Pattern SUFFIX = Pattern.compile("APP_VERSION_SUFFIX\\s*=\\s*\"-blake2b\\.(\\d+)\"");

    private static final List<String> TEMPLATES = List.of(
            "src/main/deploy/package/linux/control",
            "src/main/deploy/package/linux-headless/control",
            "src/main/deploy/package/linux/shrike.spec",
            "src/main/deploy/package/linux-headless/shrikeserver.spec");

    @Test
    public void testTheReleaseNumberIsReadable() throws Exception {
        Matcher matcher = SUFFIX.matcher(Files.readString(
                Path.of("src/main/java/com/sparrowwallet/sparrow/SparrowWallet.java")));
        Assertions.assertTrue(matcher.find(), "the build reads the Debian revision from this, so it must parse");
        Assertions.assertTrue(Integer.parseInt(matcher.group(1)) > 0);
    }

    @Test
    public void testNoPackagingTemplatePinsTheRevision() throws Exception {
        for(String template : TEMPLATES) {
            for(String line : Files.readAllLines(Path.of(template))) {
                String trimmed = line.trim();
                if(trimmed.startsWith("Release:") || trimmed.startsWith("Version:")) {
                    Assertions.assertTrue(trimmed.contains("${release}") || trimmed.equals("Version: ${version}"),
                            template + " pins a revision rather than taking it from the release number: " + trimmed);
                }
            }
        }
    }

    @Test
    public void testTheBuildSubstitutesTheRevision() throws Exception {
        String build = Files.readString(Path.of("build.gradle"));
        Assertions.assertTrue(build.contains("replace('${release}', blake2bRelease)"),
                "the resource templates carry ${release}, so the build has to substitute it");
        Assertions.assertTrue(build.contains("'--linux-app-release', blake2bRelease"),
                "the package filename should carry the same revision as the control file");
    }
}
