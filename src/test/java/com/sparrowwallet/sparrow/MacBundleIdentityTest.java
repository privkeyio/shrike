package com.sparrowwallet.sparrow;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The macOS bundle has to name this application, not the one it was forked from.
 *
 * jpackage copies this plist verbatim from the resource directory, so it overrides what the image name would
 * have produced. Left as upstream's, CFBundleExecutable read Sparrow while the bundle shipped Contents/MacOS
 * /Shrike, and Finder reports an app whose executable is missing as damaged or incomplete. It launched only
 * by running the binary directly, which is not something a user should have to discover.
 *
 * CFBundleIdentifier matters separately: two bundles claiming com.sparrowwallet.sparrow leaves LaunchServices
 * to choose between this and an installed Sparrow.
 */
public class MacBundleIdentityTest {
    private static final Path PLIST = Path.of("src/main/deploy/package/macos/Info.plist");

    private String value(String key) throws Exception {
        Matcher matcher = Pattern.compile("<key>" + key + "</key>\\s*<string>([^<]*)</string>")
                .matcher(Files.readString(PLIST));
        Assertions.assertTrue(matcher.find(), key + " is not set in the bundle plist");
        return matcher.group(1);
    }

    @Test
    public void testTheBundleNamesAnExecutableItShips() throws Exception {
        Assertions.assertEquals(SparrowWallet.APP_NAME, value("CFBundleExecutable"),
                "Finder runs this, and the bundle ships Contents/MacOS/" + SparrowWallet.APP_NAME);
    }

    @Test
    public void testTheBundleNamesAnIconItShips() throws Exception {
        String icon = value("CFBundleIconFile");
        Assertions.assertEquals(SparrowWallet.APP_NAME + ".icns", icon,
                "jpackage names the copied icon after the application");
    }

    @Test
    public void testTheBundleHasAnIdentityOfItsOwn() throws Exception {
        for(String key : new String[] {"CFBundleIdentifier", "CFBundleName"}) {
            Assertions.assertFalse(value(key).toLowerCase().contains("sparrow"),
                    key + " still claims upstream's identity, which collides with an installed Sparrow");
        }
    }

    @Test
    public void testNothingInTheBundleStillNamesUpstream() throws Exception {
        String contents = Files.readString(PLIST);
        Assertions.assertFalse(contents.toLowerCase().contains("sparrow"),
                "the bundle plist still refers to upstream somewhere");
    }
}
