package com.sparrowwallet.sparrow.net;

import com.sparrowwallet.sparrow.AppServices;
import com.sparrowwallet.sparrow.SparrowWallet;
import com.sparrowwallet.sparrow.event.VersionUpdatedEvent;
import javafx.concurrent.ScheduledService;
import javafx.concurrent.Task;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.regex.Pattern;

public class VersionCheckService extends ScheduledService<VersionUpdatedEvent> {
    private static final Logger log = LoggerFactory.getLogger(VersionCheckService.class);

    private static final String VERSION_CHECK_URL = "https://shrikewallet.com/version";

    //Exactly what this fork publishes and nothing else. An alphabet wider than this leaves the feed room to choose
    //its own words: "2.5.6-seed.shrike.support" and "2.5.6-CALL.1800.555.0199" both read as a version otherwise,
    //and both reach the status bar as "Shrike <version> available". Each component is bounded so that no accepted
    //version can overflow the int the comparison parses it into.
    private static final Pattern VERSION_PATTERN = Pattern.compile("[0-9]{1,6}(\\.[0-9]{1,6}){0,3}(-blake2b\\.[0-9]{1,6})?");

    private static final int MAX_VERSION_LENGTH = 32;

    @Override
    protected Task<VersionUpdatedEvent> createTask() {
        return new Task<>() {
            protected VersionUpdatedEvent call() {
                try {
                    VersionCheck versionCheck = getVersionCheck();
                    if(versionCheck != null && isWellFormed(versionCheck.version)) {
                        if(isNewer(versionCheck.version, SparrowWallet.APP_VERSION + SparrowWallet.APP_VERSION_SUFFIX)) {
                            return new VersionUpdatedEvent(versionCheck.version);
                        }
                    } else {
                        log.warn("Invalid version check file");
                    }
                } catch(IOException e) {
                    log.error("Error retrieving version check file", e);
                }

                return null;
            }
        };
    }

    private VersionCheck getVersionCheck() throws IOException {
        if(log.isInfoEnabled()) {
            log.info("Requesting application version check from " + VERSION_CHECK_URL);
        }

        HttpClientService httpClientService = AppServices.getHttpClientService();
        try {
            return httpClientService.requestJson(VERSION_CHECK_URL, VersionCheck.class, null);
        } catch(Exception e) {
            throw new IOException(e);
        }
    }

    /**
     * Compares a release of this fork against another.
     *
     * Drongo's Version cuts a version string at its first non-digit, so every release of this fork reads as the
     * upstream base it is built on and compares equal. The counter that actually moves lives in the suffix, so it
     * is read here, under the base version rather than instead of it: 2.5.6-blake2b.1 is newer than 2.5.5-blake2b.30.
     */
    static boolean isNewer(String candidate, String current) {
        if(!isWellFormed(candidate)) {
            log.warn("Ignoring malformed version from the version check file");
            return false;
        }

        try {
            return compare(candidate, current) > 0;
        } catch(IllegalArgumentException e) {
            log.error("Invalid versions to compare: " + candidate + " to " + current, e);
            return false;
        }
    }

    /**
     * The version is rendered into the status bar as "Shrike <version> available", so anything the feed sends ends up
     * in the wallet's own chrome. Validating only the numeric head would let a tail through: "2.5.6 restore your seed
     * at ..." compares as 2.5.6 and carries the rest onto the screen. The whole string has to answer for itself.
     */
    static boolean isWellFormed(String version) {
        return version != null && version.length() <= MAX_VERSION_LENGTH && VERSION_PATTERN.matcher(version).matches();
    }

    static int compare(String a, String b) {
        int base = compareNumeric(baseOf(a), baseOf(b));
        return base != 0 ? base : Integer.compare(counterOf(a), counterOf(b));
    }

    private static String baseOf(String version) {
        String base = version.replaceAll("[^0-9.].*", "");
        if(!base.matches("[0-9]+(\\.[0-9]+)*")) {
            throw new IllegalArgumentException("Invalid version format: " + version);
        }

        return base;
    }

    /** The trailing integer of the suffix, so -blake2b.24 reads as 24. A release with no counter sorts first. */
    private static int counterOf(String version) {
        int dash = version.indexOf('-');
        if(dash < 0) {
            return 0;
        }

        String suffix = version.substring(dash + 1);
        int lastDot = suffix.lastIndexOf('.');
        if(lastDot < 0 || lastDot == suffix.length() - 1) {
            return 0;
        }

        try {
            return Integer.parseInt(suffix.substring(lastDot + 1));
        } catch(NumberFormatException e) {
            return 0;
        }
    }

    private static int compareNumeric(String a, String b) {
        String[] aParts = a.split("\\.");
        String[] bParts = b.split("\\.");
        int length = Math.max(aParts.length, bParts.length);
        for(int i = 0; i < length; i++) {
            int aPart = i < aParts.length ? Integer.parseInt(aParts[i]) : 0;
            int bPart = i < bParts.length ? Integer.parseInt(bParts[i]) : 0;
            if(aPart != bPart) {
                return aPart < bPart ? -1 : 1;
            }
        }

        return 0;
    }


    //Package private so a test can serve this shape over a real HTTP server and confirm it parses
    static class VersionCheck {
        public String version;
    }
}
