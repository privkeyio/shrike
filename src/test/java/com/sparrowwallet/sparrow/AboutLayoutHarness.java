package com.sparrowwallet.sparrow;

import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.layout.Region;
import org.controlsfx.glyphfont.GlyphFontRegistry;
import com.sparrowwallet.sparrow.glyphfont.FontAwesome5;
import com.sparrowwallet.sparrow.glyphfont.FontAwesome5Brands;
import java.util.concurrent.CountDownLatch;


/**
 * Whether every paragraph of the About dialog fits at the height the dialog opens at.
 *
 * A wrapped label given less height than its text needs is not laid out short, it is drawn with an ellipsis, so the
 * text simply goes missing and nothing about the layout complains. That is what happened when a fourth paragraph was
 * added: every one of them lost its last line. Run this after changing that text.
 *
 * A harness rather than a test because it needs a display, which the build does not have.
 */
public class AboutLayoutHarness {
    public static void main(String[] args) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        Platform.startup(() -> {
            try {
                GlyphFontRegistry.register(new FontAwesome5());
                GlyphFontRegistry.register(new FontAwesome5Brands());
                for(double height : new double[]{460, 480, 500, 520}) {
                    FXMLLoader loader = new FXMLLoader(Class.forName("com.sparrowwallet.sparrow.AboutController").getResource("about.fxml"));
                    Parent root = loader.load();
                    new Scene(root);
                    ((Region)root).resize(600, height);
                    root.applyCss();
                    root.layout();

                    //A wrapped label that is shorter than the text needs is one that will be drawn with an ellipsis
                    boolean truncated = false;
                    double lastBottom = 0;
                    for(Node node : root.lookupAll(".label")) {
                        if(node instanceof Label label && label.isWrapText() && label.getText() != null && label.getText().length() > 40) {
                            double needed = label.prefHeight(label.getWidth());
                            if(label.getHeight() + 0.5 < needed) {
                                truncated = true;
                            }
                            lastBottom = Math.max(lastBottom, label.localToScene(label.getBoundsInLocal()).getMaxY());
                        }
                    }
                    System.out.println(String.format("%.0f high -> %s, text ends at y=%.0f", height,
                            truncated ? "TRUNCATED" : "fits", lastBottom));
                }
            } catch(Throwable t) {
                System.out.println("FAILED: " + t);
            } finally {
                done.countDown();
            }
        });
        done.await();
        Platform.exit();
        System.exit(0);
    }
}
