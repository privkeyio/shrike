package com.sparrowwallet.sparrow.control;

import com.sparrowwallet.drongo.OsType;
import com.sparrowwallet.sparrow.event.UnifiedSigHashScheduleEvent;
import com.sparrowwallet.sparrow.glyphfont.GlyphUtils;
import javafx.geometry.Insets;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;

/**
 * The status bar indicator for a unified signature hash schedule disagreement.
 *
 * A distinct type rather than a plain Label so that this handler can find its own node in the status
 * bar's right items, which is how TorStatusLabel and UsbStatusButton identify theirs. It is deliberately
 * not a Hyperlink: the version update handler removes any Hyperlink it finds among those items, so one
 * here would be taken down the next time an update was announced.
 */
public class UnifiedSigHashStatusLabel extends Label {
    public UnifiedSigHashStatusLabel(UnifiedSigHashScheduleEvent event) {
        setPadding(OsType.getCurrent() == OsType.WINDOWS ? new Insets(0, 0, 1, 3) : new Insets(1, 0, 0, 3));
        setGraphic(GlyphUtils.getWarningGlyph());
        //Shown only on a disagreement, which is the state where nothing this wallet sends opts in. That is worth
        //words rather than an unlabelled icon: the tooltip explains why, but a warning triangle on its own leaves
        //the one consequence that matters to be discovered by hovering.
        setText("No replay protection");
        update(event);
    }

    public void update(UnifiedSigHashScheduleEvent event) {
        setTooltip(new Tooltip(event.getDescription()));
    }
}
