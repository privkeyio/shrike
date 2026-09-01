package com.sparrowwallet.sparrow;

import com.sparrowwallet.drongo.policy.Policy;
import com.sparrowwallet.drongo.policy.PolicyType;
import com.sparrowwallet.drongo.protocol.ScriptType;
import com.sparrowwallet.drongo.wallet.Keystore;
import com.sparrowwallet.drongo.wallet.KeystoreSource;
import com.sparrowwallet.drongo.wallet.Wallet;
import com.sparrowwallet.drongo.wallet.WalletModel;
import com.sparrowwallet.sparrow.control.UnifiedSigHashKeystoreDialog;
import com.sparrowwallet.sparrow.glyphfont.FontAwesome5;
import com.sparrowwallet.sparrow.glyphfont.FontAwesome5Brands;
import javafx.application.Platform;
import org.controlsfx.glyphfont.GlyphFontRegistry;

/**
 * Shows the Replay protection dialog against a synthetic 2-of-3 so the rendered strings can be read, which no unit
 * test here can do. Platform.startup rather than Application, so it runs with JavaFX on the classpath.
 */
public class UnifiedSigHashDialogHarness {
    public static void main(String[] args) throws Exception {
        Platform.startup(() -> {
            //Registered the way SparrowDesktop does at startup, so the status glyph renders here as it does in the app
            GlyphFontRegistry.register(new FontAwesome5());
            GlyphFontRegistry.register(new FontAwesome5Brands());

            Wallet wallet = new Wallet();
            wallet.setPolicyType(PolicyType.MULTI_HD);
            wallet.setScriptType(ScriptType.P2WSH);
            String[] labels = {"Coldcard Mk4", "SeedSigner", "Jade (old firmware)"};
            WalletModel[] models = {WalletModel.COLDCARD, WalletModel.SEEDSIGNER, WalletModel.JADE};
            for(int i = 0; i < 3; i++) {
                Keystore keystore = new Keystore();
                keystore.setLabel(labels[i]);
                keystore.setSource(KeystoreSource.HW_AIRGAPPED);
                keystore.setWalletModel(models[i]);
                keystore.setUnifiedSigHashSupported(i < 1);
                wallet.getKeystores().add(keystore);
            }
            wallet.setDefaultPolicy(Policy.getPolicy(PolicyType.MULTI_HD, ScriptType.P2WSH, wallet.getKeystores(), 2));

            try {
                UnifiedSigHashKeystoreDialog dialog = new UnifiedSigHashKeystoreDialog(wallet);
                dialog.setOnShown(event -> System.out.println("DIALOG_SHOWN"));
                dialog.show();
            } catch(Throwable t) {
                t.printStackTrace();
                System.exit(2);
            }
        });
        Thread.sleep(60000);
        System.exit(0);
    }
}
