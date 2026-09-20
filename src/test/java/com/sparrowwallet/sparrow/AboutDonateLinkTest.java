package com.sparrowwallet.sparrow;

import javafx.fxml.FXMLLoader;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Hyperlink;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * FXML binds onAction by name at load time, so a handler that does not exist, or whose signature does not match,
 * fails only when someone clicks the link. Nothing else here exercises that.
 */
public class AboutDonateLinkTest {
    private static final String DONATE_URL = "https://shrikewallet.com/donate";

    @Test
    public void theAboutMarkupNamesAHandlerThatExists() throws Exception {
        String fxml = new String(AboutController.class.getResourceAsStream("about.fxml").readAllBytes());

        Matcher matcher = Pattern.compile("<Hyperlink text=\"([^\"]+)\" onAction=\"#(\\w+)\"").matcher(fxml);
        Assertions.assertTrue(matcher.find(), "about.fxml no longer carries a hyperlink with an action");
        Assertions.assertEquals(DONATE_URL, matcher.group(1), "the link text is not the donate page");

        Method handler = AboutController.class.getMethod(matcher.group(2), javafx.event.ActionEvent.class);
        Assertions.assertNotNull(handler, "about.fxml names a handler the controller does not declare");
    }

    @Test
    public void theHandlerOpensThePageTheLinkShows() throws Exception {
        //The text a user reads and the address that opens have to be the same, or the link lies about where it goes
        String source = new String(AboutController.class.getResourceAsStream("about.fxml").readAllBytes());
        Assertions.assertTrue(source.contains(DONATE_URL), "about.fxml does not show " + DONATE_URL);

        String controller = java.nio.file.Files.readString(
                java.nio.file.Path.of("src/main/java/com/sparrowwallet/sparrow/AboutController.java"));
        Assertions.assertTrue(controller.contains("showDocument(\"" + DONATE_URL + "\")"),
                "the handler opens something other than the address the link shows");
    }
}
