package com.sparrowwallet.sparrow;

import com.sparrowwallet.drongo.policy.Policy;
import com.sparrowwallet.drongo.policy.PolicyType;
import com.sparrowwallet.drongo.protocol.ScriptType;
import com.sparrowwallet.drongo.wallet.Keystore;
import com.sparrowwallet.drongo.wallet.KeystoreSource;
import com.sparrowwallet.drongo.wallet.Wallet;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * A signer that cannot produce the opt-in is named before the transaction is built.
 *
 * Where the unmarked signers could form a quorum the wallet already declines to claim protection. Where they cannot
 * it said nothing at all, which is the ordinary multisig: one stock signer among upgraded ones.
 */
public class UnmarkedSignerCaveatTest {
    @Test
    public void testAnUnmarkedSignerIsNamed() {
        Wallet wallet = multisig(marked("SeedSigner"), marked("Coldcard"), unmarked("Krux"));

        String caveat = AppServices.unmarkedSignerCaveat(wallet);
        Assertions.assertNotNull(caveat, "the wallet holds a signer that cannot be handed this transaction");
        Assertions.assertTrue(caveat.contains("Krux"), caveat);
        Assertions.assertFalse(caveat.contains("SeedSigner"), "names the ones that cannot sign, not the ones that can");
        Assertions.assertTrue(caveat.contains("first"), "the remedy is an order: a marked signer before these");
    }

    @Test
    public void testEveryMarkedSignerSaysNothing() {
        Wallet wallet = multisig(marked("SeedSigner"), marked("Coldcard"));
        Assertions.assertNull(AppServices.unmarkedSignerCaveat(wallet), "nothing here refuses the declared type");
    }

    /**
     * The text does not depend on the quorum; whether the status shows it does, which the wiring test below pins.
     */
    @Test
    public void testTheTextDoesNotDependOnTheQuorum() {
        Assertions.assertNotNull(AppServices.unmarkedSignerCaveat(
                multisig(marked("SeedSigner"), unmarked("Krux"), unmarked("Jade"))));
        Assertions.assertNotNull(AppServices.unmarkedSignerCaveat(
                multisig(marked("SeedSigner"), marked("Coldcard"), unmarked("Krux"))));
    }

    @Test
    public void testAnUnlabelledKeystoreIsNotNamed() {
        Wallet wallet = multisig(marked("SeedSigner"), unmarked(""));
        Assertions.assertNull(AppServices.unmarkedSignerCaveat(wallet), "there is no name to give");
    }

    @Test
    public void testNoWalletAndNoKeystoresAreNotErrors() {
        Assertions.assertNull(AppServices.unmarkedSignerCaveat(null));
        Assertions.assertNull(AppServices.unmarkedSignerCaveat(multisig()));
    }

    /**
     * Through the status assembly, not the helper alone: a caveat that never reaches the status reads like one that
     * was never written.
     */
    @Test
    public void testItReachesTheStatusTheSendScreenReads() {
        //2-of-3 with one unmarked signer: no quorum of unmarked signers exists, so the wallet claims plain OPTED_IN
        Wallet wallet = multisig(2, marked("SeedSigner"), marked("Coldcard"), unmarked("Krux"));

        AppServices.UnifiedSigHashStatus status =
                AppServices.unifiedSigHashStatus(wallet, UnifiedSigHashDecision.OPTED_IN);

        Assertions.assertEquals(UnifiedSigHashDecision.OPTED_IN, status.decision(),
                "otherwise the quorum caveat covers these signers and this one is suppressed");

        Assertions.assertTrue(status.decision().isOptedIn(), "this quorum is protected whoever signs");
        Assertions.assertTrue(status.caveats().stream().anyMatch(caveat -> caveat.contains("Krux")),
                "the send screen would say nothing about the signer that cannot be handed this: " + status.caveats());
    }

    @Test
    public void testNothingIsSaidWhereTheTransactionDoesNotOptIn() {
        Wallet wallet = multisig(marked("SeedSigner"), unmarked("Krux"));

        AppServices.UnifiedSigHashStatus status =
                AppServices.unifiedSigHashStatus(wallet, UnifiedSigHashDecision.BEFORE_ACTIVATION_HEIGHT);

        Assertions.assertFalse(status.decision().isOptedIn());
        Assertions.assertTrue(status.caveats().isEmpty(), status.caveats().toString());
    }

    private Keystore marked(String label) {
        Keystore keystore = unmarked(label);
        keystore.setUnifiedSigHashSupported(true);
        return keystore;
    }

    private Keystore unmarked(String label) {
        Keystore keystore = new Keystore();
        keystore.setSource(KeystoreSource.HW_AIRGAPPED);
        keystore.setLabel(label);
        return keystore;
    }

    private Wallet multisig(Keystore... keystores) {
        return multisig(1, keystores);
    }

    /**
     * The threshold decides: unmarked signers that cannot meet it leave the wallet claiming plain OPTED_IN.
     */
    private Wallet multisig(int threshold, Keystore... keystores) {
        Wallet wallet = new Wallet();
        wallet.setPolicyType(PolicyType.MULTI_HD);
        wallet.setScriptType(ScriptType.P2WSH);
        for(Keystore keystore : keystores) {
            wallet.getKeystores().add(keystore);
        }
        if(!wallet.getKeystores().isEmpty()) {
            wallet.setDefaultPolicy(Policy.getPolicy(PolicyType.MULTI_HD, ScriptType.P2WSH, wallet.getKeystores(), threshold));
        }

        return wallet;
    }
}
