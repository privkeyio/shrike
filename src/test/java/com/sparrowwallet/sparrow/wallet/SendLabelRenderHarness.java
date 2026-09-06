package com.sparrowwallet.sparrow.wallet;

import com.sparrowwallet.drongo.KeyPurpose;
import com.sparrowwallet.drongo.Network;
import com.sparrowwallet.drongo.policy.Policy;
import com.sparrowwallet.drongo.policy.PolicyType;
import com.sparrowwallet.drongo.protocol.Script;
import com.sparrowwallet.drongo.protocol.ScriptType;
import com.sparrowwallet.drongo.protocol.Sha256Hash;
import com.sparrowwallet.drongo.protocol.Transaction;
import com.sparrowwallet.drongo.protocol.TransactionOutput;
import com.sparrowwallet.drongo.wallet.DeterministicSeed;
import com.sparrowwallet.drongo.wallet.Keystore;
import com.sparrowwallet.drongo.wallet.KeystoreSource;
import com.sparrowwallet.drongo.wallet.Wallet;
import com.sparrowwallet.drongo.wallet.WalletNode;
import com.sparrowwallet.drongo.wallet.WalletTransaction;
import com.sparrowwallet.sparrow.SparrowWallet;
import com.sparrowwallet.sparrow.glyphfont.FontAwesome5;
import com.sparrowwallet.sparrow.glyphfont.FontAwesome5Brands;
import javafx.application.Platform;
import javafx.scene.control.Label;
import org.controlsfx.glyphfont.GlyphFontRegistry;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;

/**
 * What the send screen's replay protection label actually says once rendered.
 *
 * SendOptInDetailTest covers the sentence. This covers the rest of what a user reads: the headline, the glyph, and
 * whether the label offers the one action it can. The transaction view has the same harness, and every wrong reading
 * found so far was found by rendering a state rather than by reading the code.
 *
 * A harness rather than a test because glyphs need a display.
 */
public class SendLabelRenderHarness {
    private static Wallet wallet(PolicyType policyType, ScriptType scriptType, String[] words, int marked) throws Exception {
        Wallet wallet = new Wallet();
        wallet.setPolicyType(policyType);
        wallet.setScriptType(scriptType);

        for(int i = 0; i < words.length; i++) {
            Keystore keystore = Keystore.fromSeed(new DeterministicSeed(words[i], "", 0, DeterministicSeed.Type.BIP39),
                    policyType, scriptType.getDefaultDerivation());
            if(marked >= 0) {
                keystore.setSource(KeystoreSource.HW_USB);
                keystore.setLabel("Signer " + (i + 1));
                keystore.setUnifiedSigHashSupported(i < marked);
            }
            wallet.getKeystores().add(keystore);
        }

        wallet.setDefaultPolicy(Policy.getPolicy(policyType, scriptType, wallet.getKeystores(),
                policyType == PolicyType.MULTI_HD ? 2 : null));
        wallet.getNode(KeyPurpose.RECEIVE);
        return wallet;
    }

    private static WalletTransaction walletTransaction(Wallet wallet) {
        //A fixed script, since the label never looks at the outputs and a wallet with no policy cannot derive one
        Script spk = ScriptType.P2WPKH.getOutputScript(com.sparrowwallet.drongo.crypto.ECKey.fromPrivate(
                com.sparrowwallet.drongo.Utils.hexToBytes("11".repeat(32))).getPubKey());

        Transaction transaction = new Transaction();
        transaction.setVersion(2);
        transaction.addInput(Sha256Hash.ZERO_HASH, 0, new Script(new byte[0]));
        transaction.addOutput(90_000L, spk);
        TransactionOutput output = transaction.getOutputs().get(0);

        return new WalletTransaction(wallet, transaction, Collections.emptyList(), List.of(Collections.emptyMap()),
                Collections.emptyList(), List.of(new WalletTransaction.Output(output)), 10_000L);
    }

    /** The production method, on the production field, so what is read back is what a user would see. */
    private static void render(String name, Wallet wallet) throws Exception {
        SendController controller = new SendController();
        Label label = new Label();

        Field field = SendController.class.getDeclaredField("optInStatus");
        field.setAccessible(true);
        field.set(controller, label);

        Method render = SendController.class.getDeclaredMethod("renderOptInStatus", WalletTransaction.class);
        render.setAccessible(true);
        render.invoke(controller, wallet == null ? null : walletTransaction(wallet));

        System.out.println("  " + name);
        System.out.println("      \"" + label.getText() + "\""
                + (label.getGraphic() == null ? " (no glyph)" : " [" + String.join(",", label.getGraphic().getStyleClass()) + "]")
                + (label.getStyleClass().contains("actionable") ? " (actionable)" : "")
                + (label.isVisible() ? "" : " (hidden)"));
        if(label.getTooltip() != null) {
            System.out.println("      tooltip: " + label.getTooltip().getText());
        }
    }

    public static void main(String[] args) throws Exception {
        System.setProperty(SparrowWallet.APP_HOME_PROPERTY, Files.createTempDirectory("shrike-send").toString());
        Network.set(Network.MAINNET);

        String[] one = {"absent essay fox snake vast pumpkin height crouch silent bulb excuse razor"};
        String[] three = {one[0],
                "sell arrive brand fluid cousin twin trap bar hen fine bicycle rack",
                "quantum lens tag pencil kingdom obey noise pigeon oyster shoulder ordinary tilt"};

        CountDownLatch done = new CountDownLatch(1);
        Platform.startup(() -> {
            try {
                GlyphFontRegistry.register(new FontAwesome5());
                GlyphFontRegistry.register(new FontAwesome5Brands());
                com.sparrowwallet.sparrow.AppServices.initialize(null);

                //A tip past the activation height, so the chain is settled and what varies is the keystores. Offline,
                //every wallet answers "the chain cannot be seen from here" and none of the rest is reachable.
                com.sparrowwallet.drongo.protocol.BlockHeader header = new com.sparrowwallet.drongo.protocol.BlockHeader(
                        1, Sha256Hash.ZERO_HASH, Sha256Hash.ZERO_HASH, null, 0, 0x207fffffL, 0);
                Field headerV2 = com.sparrowwallet.drongo.protocol.BlockHeader.class.getDeclaredField("headerV2");
                headerV2.setAccessible(true);
                headerV2.setBoolean(header, true);
                com.sparrowwallet.sparrow.AppServices.setAnnouncedTip(
                        new com.sparrowwallet.sparrow.ChainTip(Network.get().getBlake2bHeight() + 10, header));

                render("no transaction yet", null);
                render("software seed, signs here", wallet(PolicyType.SINGLE_HD, ScriptType.P2WPKH, one, -1));
                render("one device, not marked", wallet(PolicyType.SINGLE_HD, ScriptType.P2WPKH, one, 0));
                render("one device, marked", wallet(PolicyType.SINGLE_HD, ScriptType.P2WPKH, one, 1));
                render("2 of 3, one signer marked", wallet(PolicyType.MULTI_HD, ScriptType.P2WSH, three, 1));
                render("2 of 3, every signer marked", wallet(PolicyType.MULTI_HD, ScriptType.P2WSH, three, 3));
                render("wallet with no keystores", new Wallet());
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
