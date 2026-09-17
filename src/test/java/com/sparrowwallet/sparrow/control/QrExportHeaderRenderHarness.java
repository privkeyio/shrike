package com.sparrowwallet.sparrow.control;

import com.sparrowwallet.drongo.KeyPurpose;
import com.sparrowwallet.drongo.Network;
import com.sparrowwallet.drongo.policy.Policy;
import com.sparrowwallet.drongo.policy.PolicyType;
import com.sparrowwallet.drongo.protocol.*;
import com.sparrowwallet.drongo.psbt.PSBT;
import com.sparrowwallet.drongo.psbt.PSBTInput;
import com.sparrowwallet.drongo.wallet.*;
import com.sparrowwallet.sparrow.AppServices;
import com.sparrowwallet.sparrow.SparrowWallet;
import com.sparrowwallet.sparrow.glyphfont.FontAwesome5;
import com.sparrowwallet.sparrow.glyphfont.FontAwesome5Brands;
import com.sparrowwallet.hummingbird.registry.CryptoPSBT;
import javafx.application.Platform;
import javafx.embed.swing.SwingFXUtils;
import javafx.scene.Node;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.image.WritableImage;
import org.controlsfx.glyphfont.GlyphFontRegistry;

import javax.imageio.ImageIO;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;

/**
 * What the QR export dialog actually shows above the code.
 *
 * PsbtForExportTest covers the wording; nothing there proves a DialogPane whose only override is createButton draws a
 * header at all. A harness rather than a test because it needs a display. Run it after changing either, the way the
 * other render harnesses here are run, with an argument for where to write the two snapshots.
 */
public class QrExportHeaderRenderHarness {
    private static volatile boolean failed;

    private static Wallet quorum() throws Exception {
        String[] mnemonics = {
                "absent essay fox snake vast pumpkin height crouch silent bulb excuse razor",
                "sample vibrant sound quantum ripple hidden pluck raven mirror ocean fabric noodle",
                "vault cruise pistol trigger pilot scan hidden major fringe course fiber quiz"};

        Wallet wallet = new Wallet();
        wallet.setPolicyType(PolicyType.MULTI_HD);
        wallet.setScriptType(ScriptType.P2WSH);
        for(String mnemonic : mnemonics) {
            DeterministicSeed seed = new DeterministicSeed(mnemonic, "", 0, DeterministicSeed.Type.BIP39);
            wallet.getKeystores().add(Keystore.fromSeed(seed, PolicyType.MULTI_HD, ScriptType.P2WSH.getDefaultDerivation()));
        }
        wallet.getKeystores().getLast().setSource(KeystoreSource.HW_AIRGAPPED);
        wallet.getKeystores().getLast().setLabel("Krux");
        wallet.setDefaultPolicy(Policy.getPolicy(PolicyType.MULTI_HD, ScriptType.P2WSH, wallet.getKeystores(), 2));
        wallet.getNode(KeyPurpose.RECEIVE);
        return wallet;
    }

    private static PSBT psbt(Wallet wallet) {
        WalletNode node = wallet.getNode(KeyPurpose.RECEIVE).getChildren().iterator().next();
        Script spk = wallet.getOutputScript(node);
        Transaction transaction = new Transaction();
        transaction.setVersion(2);
        transaction.addInput(Sha256Hash.ZERO_HASH, 0, new Script(new byte[0]));
        transaction.addOutput(90_000L, spk);
        PSBT psbt = new PSBT(transaction);
        PSBTInput psbtInput = psbt.getPsbtInputs().getFirst();
        psbtInput.setWitnessUtxo(new TransactionOutput(null, 100_000L, spk.getProgram()));
        psbtInput.setWitnessScript(ScriptType.MULTISIG.getOutputScript(
                wallet.getDefaultPolicy().getNumSignaturesRequired(), node.getPubKeys()));
        psbtInput.setSigHash(SigHash.UNIFIED_ALL);
        return psbt;
    }

    private static void render(String name, Wallet wallet, PSBT psbt, Path out) throws Exception {
        String description = AppServices.exportDescription(wallet, psbt);
        System.out.println("  " + name);
        System.out.println("    exportDescription: " + description);

        CryptoPSBT cryptoPSBT = new CryptoPSBT(psbt.getForExport().serialize(true, false));
        QRDisplayDialog dialog = new QRDisplayDialog(cryptoPSBT.toUR());
        DialogPane pane = dialog.getDialogPane();
        if(description != null) {
            pane.setHeaderText(description);
        }

        //The Dialog already owns a Scene for this pane; a second one is refused
        pane.resize(760, 640);
        pane.applyCss();
        pane.layout();

        String shown = null;
        for(Node node : pane.lookupAll(".label")) {
            if(node instanceof Label label && description != null && description.equals(label.getText())) {
                shown = label.getText();
                break;
            }
        }
        System.out.println("    rendered header:   " + (shown == null ? "NOT FOUND" : shown));
        if(description != null && shown == null) {
            failed = true;
        }

        WritableImage image = pane.snapshot(null, null);
        ImageIO.write(SwingFXUtils.fromFXImage(image, null), "png", new File(out.toString()));
        System.out.println("    wrote " + out);
    }

    public static void main(String[] args) throws Exception {
        System.setProperty(SparrowWallet.APP_HOME_PROPERTY, Files.createTempDirectory("shrike-render").toString());
        Network.set(Network.MAINNET);
        Path dir = Path.of(args.length > 0 ? args[0] : "build/qr-render");
        Files.createDirectories(dir);

        CountDownLatch done = new CountDownLatch(1);
        Platform.startup(() -> {
            try {
                GlyphFontRegistry.register(new FontAwesome5());
                GlyphFontRegistry.register(new FontAwesome5Brands());
                AppServices.initialize(null);

                Wallet wallet = quorum();
                WalletNode node = wallet.getNode(KeyPurpose.RECEIVE).getChildren().iterator().next();

                PSBT unsigned = psbt(wallet);
                render("before any signature", wallet, unsigned, dir.resolve("before.png"));

                PSBT signed = psbt(wallet);
                signed.getPsbtInputs().getFirst().sign(wallet.getKeystores().getFirst().getKey(node));
                render("after a marked signer, as exported", wallet,
                        AppServices.psbtForExport(wallet, signed), dir.resolve("after.png"));
            } catch(Throwable e) {
                failed = true;
                e.printStackTrace();
            } finally {
                done.countDown();
            }
        });

        done.await();
        Platform.exit();
        System.exit(failed ? 1 : 0);
    }
}
