package com.sparrowwallet.sparrow.wallet;

import com.sparrowwallet.drongo.KeyDerivation;
import com.sparrowwallet.drongo.policy.Policy;
import com.sparrowwallet.drongo.policy.PolicyType;
import com.sparrowwallet.drongo.protocol.ScriptType;
import com.sparrowwallet.drongo.wallet.DeterministicSeed;
import com.sparrowwallet.drongo.wallet.Keystore;
import com.sparrowwallet.drongo.wallet.KeystoreSource;
import com.sparrowwallet.drongo.wallet.Wallet;
import com.sparrowwallet.drongo.wallet.WalletModel;
import com.sparrowwallet.sparrow.glyphfont.FontAwesome5;
import com.sparrowwallet.sparrow.glyphfont.FontAwesome5Brands;
import com.sparrowwallet.sparrow.io.Storage;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.scene.layout.Pane;
import javafx.scene.text.Font;
import javafx.stage.Stage;
import org.controlsfx.glyphfont.GlyphFontRegistry;

import java.io.File;

/**
 * Renders the keystore tab so the Replay protection row can be read for each source, which no unit test can do.
 *
 * The control is drawn wherever the signing happens somewhere the wallet cannot see, and its label has to name
 * the right thing for each: a device where there is one, the signer otherwise.
 */
public class KeystoreTabHarness {
    public static void main(String[] args) throws Exception {
        KeystoreSource source = KeystoreSource.valueOf(args.length > 0 ? args[0] : "HW_AIRGAPPED");

        Platform.startup(() -> {
            //Exactly what SparrowDesktop.initializeFonts does. Without it the scene falls back to the system
            //font, which is wider, and field labels truncate here that do not truncate in the app
            GlyphFontRegistry.register(new FontAwesome5());
            GlyphFontRegistry.register(new FontAwesome5Brands());
            Font.loadFont(com.sparrowwallet.sparrow.AppServices.class.getResourceAsStream("/font/FragmentMono-Regular.ttf"), 13);
            Font.loadFont(com.sparrowwallet.sparrow.AppServices.class.getResourceAsStream("/font/FragmentMono-Italic.ttf"), 11);

            try {
                Wallet wallet = new Wallet();
                wallet.setName("Harness");
                wallet.setPolicyType(PolicyType.SINGLE_HD);
                wallet.setScriptType(ScriptType.P2WPKH);

                //Built from a seed so the keystore carries a real xpub, then stripped: initializeView only shows
                //the detail form for a keystore that has one, and offers the source picker otherwise
                DeterministicSeed seed = new DeterministicSeed(
                        "absent essay fox snake vast pumpkin height crouch silent bulb excuse razor",
                        "", 0, DeterministicSeed.Type.BIP39);
                Keystore keystore = Keystore.fromSeed(seed, PolicyType.SINGLE_HD, ScriptType.P2WPKH.getDefaultDerivation());
                keystore.setLabel("Keystore 1");
                keystore.setSeed(null);
                keystore.setSource(source);
                keystore.setWalletModel(source == KeystoreSource.SW_WATCH ? WalletModel.SPARROW : WalletModel.SEEDSIGNER);
                wallet.getKeystores().add(keystore);
                wallet.setDefaultPolicy(Policy.getPolicy(PolicyType.SINGLE_HD, ScriptType.P2WPKH, wallet.getKeystores(), 1));

                File file = File.createTempFile("keystore-harness", ".mv.db");
                file.deleteOnExit();
                WalletForm walletForm = new WalletForm(new Storage(file), wallet);

                FXMLLoader loader = new FXMLLoader(KeystoreController.class.getResource("keystore.fxml"));
                Pane pane = loader.load();
                KeystoreController controller = loader.getController();
                controller.setKeystore(walletForm, keystore);
                controller.initializeView();

                Stage stage = new Stage();
                stage.setTitle("Keystore " + source);
                Scene scene = new Scene(pane, 940, 460);
                //Both, as the app loads them: general.css is what sizes the field label column, so without it
                //the labels lay out differently here than they do in a real window
                scene.getStylesheets().add(KeystoreController.class.getResource("/com/sparrowwallet/sparrow/general.css").toExternalForm());
                scene.getStylesheets().add(KeystoreController.class.getResource("/com/sparrowwallet/sparrow/wallet/wallet.css").toExternalForm());
                //The theme the committed screenshots were taken in, so this is comparable with them
                scene.getStylesheets().add(KeystoreController.class.getResource("/com/sparrowwallet/sparrow/darktheme.css").toExternalForm());
                stage.setScene(scene);
                stage.show();
                System.out.println("TAB_SHOWN " + source);
            } catch(Exception e) {
                e.printStackTrace();
            }
        });
    }
}
