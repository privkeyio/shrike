package com.sparrowwallet.sparrow.control;

import com.sparrowwallet.drongo.wallet.Keystore;
import com.sparrowwallet.drongo.wallet.Wallet;
import com.sparrowwallet.sparrow.AppServices;
import com.sparrowwallet.sparrow.glyphfont.GlyphUtils;
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
                A device reports its model but not its firmware, so only you can say which of these support the \
                opt-in.

                Leave a signer unmarked if you are unsure. If you mark one that does not support the opt-in, it will \
                refuse to sign rather than fall back. This applies to transactions created from now on.""");
        dialogPane.getStylesheets().add(AppServices.class.getResource("general.css").toExternalForm());
        dialogPane.getButtonTypes().addAll(ButtonType.CANCEL);
        dialogPane.setPrefWidth(560);
        //Sized for the header, a line per keystore and the line counting them, so nothing is clipped and nothing
        //is left as empty space below the buttons
        dialogPane.setPrefHeight(255 + (wallet.getKeystores().size() * 30));
        AppServices.moveToActiveWindowScreen(this);

        Glyph lock = new Glyph(FontAwesome5.FONT_NAME, FontAwesome5.Glyph.LOCK);
        lock.setFontSize(50);
        dialogPane.setGraphic(lock);

        final VBox content = new VBox(10);
        //Indented to line up with the header text above it, rather than sitting against the edge of the pane
        content.setPadding(new Insets(4, 20, 0, 20));
        //Every keystore the owner can speak for, which is the same set the keystore tab shows the field on. A watch
        //only keystore is in the position an airgapped one is: the wallet cannot verify the claim, and listing only
        //hardware here left the send screen offering a remedy this dialog had no control for.
        for(Keystore keystore : wallet.getKeystores()) {
            if(AppServices.canBeMarked(keystore)) {
                CheckBox mark = new CheckBox(keystore.getLabel() + " (" + keystore.getWalletModel().toDisplayString() + ")");
                mark.setSelected(keystore.isUnifiedSigHashSupported());
                marks.put(keystore, mark);
                content.getChildren().add(mark);
            }
        }
        //The header says marking enough of them is what matters without saying how many, which leaves the count to
        //be guessed. Said here, and kept current as they are ticked, so the target and the progress toward it are both visible.
        if(!marks.isEmpty()) {
            Label progress = new Label();
            progress.setWrapText(true);
            progress.setPadding(new Insets(12, 0, 6, 0));
            Runnable update = () -> {
                //Counted the way the decision counts it: a keystore holding its own key opts in without being marked,
                //so it is capable whether or not it appears above. Deriving this from the ticked boxes alone reported
                //a wallet that mixes a seed with a device as less protected than it is.
                long capable = wallet.getKeystores().stream().filter(keystore -> AppServices.signsInProcess(keystore)
                        || (marks.containsKey(keystore) && marks.get(keystore).isSelected())).count();
                long unmarked = wallet.getKeystores().size() - capable;
                Integer threshold = AppServices.readThreshold(wallet);
                int required = threshold == null ? 1 : threshold;

                //Three answers, not two: nothing marked cannot opt in at all, a marked signer in every possible quorum
                //is a guarantee, and anything between opts in but only protects where one of them signs
                boolean any = capable > 0;
                boolean guaranteed = any && unmarked < required;

                //Carries the same glyph and colour the send screen uses for the same answer, so the two agree on sight
                progress.setGraphic(any ? GlyphUtils.getSuccessGlyph() : GlyphUtils.getWarningGlyph());
                progress.getStyleClass().removeAll("success", "failure");
                progress.getStyleClass().add(any ? "success" : "failure");
                progress.setText((guaranteed ? "Transactions will opt in. "
                        : any ? "Transactions opt in when a signer that can opt in takes part. " : "Transactions will not opt in. ")
                        + capable + " of " + wallet.getKeystores().size() + " signers can opt in"
                        + (threshold == null ? "." : ", " + threshold + " needed to sign."));
            };
            marks.values().forEach(mark -> mark.selectedProperty().addListener((observable, was, is) -> update.run()));
            update.run();
            content.getChildren().add(progress);
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
