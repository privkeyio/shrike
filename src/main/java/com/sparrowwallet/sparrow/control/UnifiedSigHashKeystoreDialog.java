package com.sparrowwallet.sparrow.control;

import com.sparrowwallet.drongo.wallet.Keystore;
import com.sparrowwallet.drongo.wallet.Wallet;
import com.sparrowwallet.sparrow.AppServices;
import com.sparrowwallet.sparrow.glyphfont.FontAwesome5;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.VBox;
import org.controlsfx.glyphfont.Glyph;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Marks the devices in a wallet as signing the unified opt-in signature hash, from the send screen where the user
 * finds out they are not getting replay protection.
 *
 * The setting lives on the keystore and is edited in the wallet settings like any other keystore field. This is the
 * same setting reached from where it matters: leaving a send to look for it in a tab is how a setting goes unused,
 * and the wallet is not rewritten for this change, so it does not need the password the settings save asks for.
 *
 * Returns the keystores whose mark changed, or null where the dialog was cancelled. Nothing is applied here: the
 * caller applies and posts, so that one path writes the change however it was reached.
 */
public class UnifiedSigHashKeystoreDialog extends Dialog<List<Keystore>> {
    private final Map<Keystore, CheckBox> marks = new LinkedHashMap<>();

    public UnifiedSigHashKeystoreDialog(Wallet wallet) {
        final DialogPane dialogPane = getDialogPane();
        AppServices.setStageIcon(dialogPane.getScene().getWindow());

        setTitle("Replay Protection");
        dialogPane.setHeaderText("""
                A signature that opts in cannot be replayed against nodes that have not adopted the fork. \
                Opting in needs enough devices in this wallet to meet its signing threshold, and a device reports its model but not \
                its firmware, so only you can say.

                Leave a device unmarked if you are unsure. Marking one that does not support it does not fall \
                back: it signs nothing and reports a failure of its own.

                This applies to transactions created from now on. A transaction already created keeps the hash \
                type it was built with, so create it again to use the new setting.""");
        dialogPane.getStylesheets().add(AppServices.class.getResource("general.css").toExternalForm());
        dialogPane.getButtonTypes().addAll(ButtonType.CANCEL);
        dialogPane.setPrefWidth(560);
        dialogPane.setPrefHeight(400);
        AppServices.moveToActiveWindowScreen(this);

        Glyph lock = new Glyph(FontAwesome5.FONT_NAME, FontAwesome5.Glyph.LOCK);
        lock.setFontSize(50);
        dialogPane.setGraphic(lock);

        final VBox content = new VBox(10);
        content.setPadding(new Insets(10, 0, 0, 0));
        for(Keystore keystore : wallet.getKeystores()) {
            if(keystore.getSource().isHardware()) {
                CheckBox mark = new CheckBox(keystore.getLabel() + " (" + keystore.getWalletModel().toDisplayString() + ")");
                mark.setSelected(keystore.isUnifiedSigHashSupported());
                marks.put(keystore, mark);
                content.getChildren().add(mark);
            }
        }
        dialogPane.setContent(content);

        final ButtonType okButtonType = new ButtonType("Apply", ButtonBar.ButtonData.OK_DONE);
        dialogPane.getButtonTypes().add(okButtonType);

        setResultConverter(dialogButton -> dialogButton == okButtonType ? getChanged() : null);
    }

    /**
     * Whether there is anything here to decide. A wallet with no device has nothing to mark, and the reason it is
     * not opting in lies elsewhere.
     */
    public boolean hasKeystores() {
        return !marks.isEmpty();
    }

    private List<Keystore> getChanged() {
        List<Keystore> changed = new ArrayList<>();
        for(Map.Entry<Keystore, CheckBox> entry : marks.entrySet()) {
            if(entry.getKey().isUnifiedSigHashSupported() != entry.getValue().isSelected()) {
                changed.add(entry.getKey());
            }
        }

        return changed;
    }

    /** The mark the user chose for a keystore the dialog offered. */
    public boolean isMarked(Keystore keystore) {
        CheckBox mark = marks.get(keystore);
        return mark != null && mark.isSelected();
    }
}
