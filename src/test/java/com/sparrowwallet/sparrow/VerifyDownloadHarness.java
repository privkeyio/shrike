package com.sparrowwallet.sparrow;

import com.sparrowwallet.drongo.pgp.PGPUtils;
import com.sparrowwallet.drongo.pgp.PGPVerificationResult;

import java.io.FileInputStream;
import java.io.InputStream;

/**
 * Runs the Verify Download check over the files this project actually publishes.
 *
 * The dialog offers Sparrow's manifest naming, and its key is whatever file the user picks, so whether our own
 * SHA256SUMS and signing key verify through it is a question about the code rather than about the release.
 */
public class VerifyDownloadHarness {
    public static void main(String[] args) throws Exception {
        try(InputStream key = new FileInputStream(args[0]);
            InputStream content = new FileInputStream(args[1]);
            InputStream sig = new FileInputStream(args[2])) {
            PGPVerificationResult result = PGPUtils.verify(key, content, sig);
            System.out.println("VERIFIED=" + true);
            System.out.println("SIGNER=" + result.userId());
            System.out.println("FINGERPRINT=" + result.fingerprint());
        } catch(Exception e) {
            System.out.println("VERIFIED=false");
            System.out.println("REASON=" + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }
}
