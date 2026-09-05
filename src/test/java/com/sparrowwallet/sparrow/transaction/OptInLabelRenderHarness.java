package com.sparrowwallet.sparrow.transaction;

import com.sparrowwallet.drongo.KeyPurpose;
import com.sparrowwallet.drongo.Network;
import com.sparrowwallet.drongo.policy.Policy;
import com.sparrowwallet.drongo.policy.PolicyType;
import com.sparrowwallet.drongo.protocol.Script;
import com.sparrowwallet.drongo.protocol.ScriptType;
import com.sparrowwallet.drongo.protocol.Sha256Hash;
import com.sparrowwallet.drongo.protocol.SigHash;
import com.sparrowwallet.drongo.protocol.Transaction;
import com.sparrowwallet.drongo.protocol.TransactionOutput;
import com.sparrowwallet.drongo.psbt.PSBT;
import com.sparrowwallet.drongo.psbt.PSBTInput;
import com.sparrowwallet.drongo.wallet.DeterministicSeed;
import com.sparrowwallet.drongo.wallet.Keystore;
import com.sparrowwallet.drongo.wallet.Wallet;
import com.sparrowwallet.drongo.wallet.WalletNode;
import com.sparrowwallet.sparrow.SparrowWallet;
import com.sparrowwallet.sparrow.glyphfont.FontAwesome5;
import com.sparrowwallet.sparrow.glyphfont.FontAwesome5Brands;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.layout.Region;
import org.controlsfx.glyphfont.GlyphFontRegistry;

import java.nio.file.Files;
import java.util.concurrent.CountDownLatch;

/**
 * What the replay protection labels actually say once the transaction view has drawn them.
 *
 * OptInStatusTest covers the decision; this covers the wiring between that decision and the two labels a user reads,
 * which no unit test can reach. Run it after changing either.
 *
 * A harness rather than a test because it needs a display, which the build does not have.
 */
public class OptInLabelRenderHarness {
    private static Wallet wallet() throws Exception {
        Wallet wallet = new Wallet();
        wallet.setPolicyType(PolicyType.SINGLE_HD);
        wallet.setScriptType(ScriptType.P2WPKH);
        DeterministicSeed seed = new DeterministicSeed(
                "absent essay fox snake vast pumpkin height crouch silent bulb excuse razor", "", 0, DeterministicSeed.Type.BIP39);
        wallet.getKeystores().add(Keystore.fromSeed(seed, PolicyType.SINGLE_HD, ScriptType.P2WPKH.getDefaultDerivation()));
        wallet.setDefaultPolicy(Policy.getPolicy(PolicyType.SINGLE_HD, ScriptType.P2WPKH, wallet.getKeystores(), null));
        wallet.getNode(KeyPurpose.RECEIVE);
        return wallet;
    }

    private static PSBT signedPsbt(Wallet wallet, SigHash sigHash) throws Exception {
        WalletNode node = wallet.getNode(KeyPurpose.RECEIVE).getChildren().iterator().next();
        Script spk = wallet.getOutputScript(node);

        Transaction transaction = new Transaction();
        transaction.setVersion(2);
        transaction.addInput(Sha256Hash.ZERO_HASH, 0, new Script(new byte[0]));
        transaction.addOutput(90_000L, spk);

        PSBT psbt = new PSBT(transaction);
        PSBTInput psbtInput = psbt.getPsbtInputs().get(0);
        psbtInput.setWitnessUtxo(new TransactionOutput(null, 100_000L, spk.getProgram()));
        psbtInput.setSigHash(sigHash);
        psbtInput.getDerivedPublicKeys().put(node.getPubKey(), wallet.getKeystores().get(0).getKeyDerivation()
                .extend(node.getDerivation()));
        psbtInput.sign(wallet.getKeystores().get(0).getKey(node));

        return psbt;
    }

    private static void render(String name, PSBT psbt, Wallet signingWallet) throws Exception {
        TransactionData data = new TransactionData(name, psbt);
        data.setSigningWallet(signingWallet);
        HeadersForm form = new HeadersForm(data);
        Node contents = form.getContents();
        new Scene((javafx.scene.Parent)contents);
        ((Region)contents).resize(900, 700);
        contents.applyCss();
        ((Region)contents).layout();

        for(String id : new String[] {"#signingWalletOptIn", "#signaturesOptIn"}) {
            Node node = contents.lookup(id);
            if(node instanceof Label label) {
                System.out.println("  " + name + " " + id + " -> \"" + label.getText() + "\""
                        + (label.getGraphic() == null ? " (no glyph)" : " (glyph)")
                        + (label.getTooltip() == null ? "" : System.lineSeparator()
                            + "      tooltip: " + label.getTooltip().getText()));
            }
        }
    }

    public static void main(String[] args) throws Exception {
        System.setProperty(SparrowWallet.APP_HOME_PROPERTY, Files.createTempDirectory("shrike-render").toString());
        Network.set(Network.MAINNET);

        CountDownLatch done = new CountDownLatch(1);
        Platform.startup(() -> {
            try {
                GlyphFontRegistry.register(new FontAwesome5());
                GlyphFontRegistry.register(new FontAwesome5Brands());
                //The view asks the application for the wallets that are open, so there has to be one to ask
                com.sparrowwallet.sparrow.AppServices.initialize(null);

                Wallet wallet = wallet();
                render("opted-in, wallet present", signedPsbt(wallet, SigHash.UNIFIED_ALL), wallet);
                render("legacy, wallet present", signedPsbt(wallet, SigHash.ALL), wallet);
                render("opted-in, no wallet to vouch", signedPsbt(wallet, SigHash.UNIFIED_ALL), null);

                //A PSBT carrying a derivation this wallet cannot follow, on an input it does own. The input is found
                //by its script, so the derivation is never consulted and the honest answer stands: the guard around
                //that lookup must not cost a transaction its reading. Where the lookup does throw, the labels fall
                //back to saying nothing was checked, which is what the fail closed path in updateOptInStatus is for.
                PSBT undrivable = signedPsbt(wallet, SigHash.UNIFIED_ALL);
                java.util.List<com.sparrowwallet.drongo.crypto.ChildNumber> path =
                        new java.util.ArrayList<>(wallet.getKeystores().get(0).getKeyDerivation().getDerivation());
                path.add(new com.sparrowwallet.drongo.crypto.ChildNumber(0, false));
                path.add(new com.sparrowwallet.drongo.crypto.ChildNumber(0, true));
                undrivable.getPsbtInputs().get(0).getDerivedPublicKeys().put(
                        com.sparrowwallet.drongo.crypto.ECKey.fromPrivate(
                                com.sparrowwallet.drongo.Utils.hexToBytes("33".repeat(32))),
                        new com.sparrowwallet.drongo.KeyDerivation(
                                wallet.getKeystores().get(0).getKeyDerivation().getMasterFingerprint(),
                                com.sparrowwallet.drongo.KeyDerivation.writePath(path)));
                render("opted-in, junk derivation attached to its own input", undrivable, wallet);
            } catch(Throwable t) {
                System.out.println("RENDER FAILED: " + t);
                t.printStackTrace(System.out);
            } finally {
                done.countDown();
            }
        });
        done.await();
        Platform.exit();
        System.exit(0);
    }
}
