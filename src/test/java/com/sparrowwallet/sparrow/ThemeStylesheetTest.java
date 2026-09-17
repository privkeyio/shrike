package com.sparrowwallet.sparrow;

import com.sparrowwallet.sparrow.io.Config;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ThemeStylesheetTest {
    private static Theme entryTheme;
    private static String entryHome;

    @BeforeAll
    public static void useTemporaryHome() throws Exception {
        entryHome = System.getProperty(SparrowWallet.APP_HOME_PROPERTY);
        System.setProperty(SparrowWallet.APP_HOME_PROPERTY, Files.createTempDirectory("shrike-theme").toString());
        entryTheme = Config.get().getTheme();
    }

    @AfterAll
    public static void restoreHome() {
        //Config is a singleton shared with every other test in this JVM, so put back what was found
        Config.get().setTheme(entryTheme);
        if(entryHome == null) {
            System.clearProperty(SparrowWallet.APP_HOME_PROPERTY);
        } else {
            System.setProperty(SparrowWallet.APP_HOME_PROPERTY, entryHome);
        }
    }

    private static List<String> themeStylesheets(List<String> stylesheets) {
        return stylesheets.stream().filter(css -> css.endsWith("theme.css")).toList();
    }

    @Test
    public void bothThemesResolveToAStylesheet() {
        Config.get().setTheme(Theme.LIGHT);
        Assertions.assertTrue(AppServices.getThemeStylesheet().endsWith("lighttheme.css"));
        Config.get().setTheme(Theme.DARK);
        Assertions.assertTrue(AppServices.getThemeStylesheet().endsWith("darktheme.css"));
    }

    /** The rules that carry the fork's red. Dark carries many more, but those adapt to its ground rather than brand it. */
    private static final List<String> BRAND_RULES = List.of(
            ".root|-fx-accent",
            ".root|-fx-default-button",
            ".root|-fx-faint-focus-color",
            ".root .hyperlink|-fx-text-fill",
            ".root .hyperlink:visited|-fx-text-fill",
            ".root .placeholder .hyperlink|-fx-text-fill");

    @Test
    public void theHyperlinkColorUsesThePropertyThatAppliesIt() throws Exception {
        //A Hyperlink is a Labeled: -fx-fill does nothing to its text, so the red was declared and never applied and
        //every unvisited link fell back to Modena's blue. The themes now outrank this rule, so a regression here
        //would only show wherever general.css is attached without one.
        Map<String, String> general = colorDeclarations("general.css");
        Assertions.assertNotNull(general.get(".hyperlink|-fx-text-fill"),
                "general.css must set the hyperlink color with -fx-text-fill, not -fx-fill");
    }

    @Test
    public void bothThemesDeclareEveryBrandRule() throws Exception {
        Map<String, String> light = colorDeclarations("lighttheme.css");
        Map<String, String> dark = colorDeclarations("darktheme.css");

        //A rule declared in one theme and not the other is the bug this all started as: the light theme set only the
        //accent, so its links kept Modena's blue while the dark theme's were red, and a user spotted it.
        for(String rule : BRAND_RULES) {
            Assertions.assertNotNull(dark.get(rule), rule + " is not declared by the dark theme");
            Assertions.assertNotNull(light.get(rule), rule + " is not declared by the light theme");
        }
    }

    @Test
    public void lightTakesTheBrighterShadeOfEachBackground() throws Exception {
        Map<String, String> light = colorDeclarations("lighttheme.css");
        Map<String, String> dark = colorDeclarations("darktheme.css");

        //Upstream carries every color at two shades and puts the brighter on the light ground. These are its steps,
        //which this theme follows with the fork's red in place of Sparrow's blue. Handing light the dark shade makes
        //the menu bar read as a heavy block, which is what happened when this file first copied the dark accent.
        Map<String, Double> upstreamStep = Map.of(".root|-fx-accent", 2.17d, ".root|-fx-default-button", 2.85d);

        for(Map.Entry<String, Double> entry : upstreamStep.entrySet()) {
            double step = relativeLuminance(light.get(entry.getKey())) / relativeLuminance(dark.get(entry.getKey()));
            Assertions.assertTrue(step > entry.getValue() * 0.9d,
                    entry.getKey() + " steps " + String.format("%.2f", step) + "x from dark to light; upstream steps "
                            + entry.getValue() + "x");
        }
    }

    @Test
    public void lightThemeTextIsReadableOnItsGround() throws Exception {
        Map<String, String> light = colorDeclarations("lighttheme.css");

        //Text shades are per theme rather than shared precisely because a color readable on charcoal is not readable
        //on white. Dark's #D65641 reaches only 3.6:1 here, which is why the light theme states its own tone.
        //Modena's -fx-base panel, the darkest ground a light-mode link sits on, so the weakest case rather than a
        //flattering one. Upstream's own light link manages only 3.25:1 here, so this bar is stricter than upstream's.
        String ground = "#ECECEC";
        for(String rule : List.of(".root .hyperlink|-fx-text-fill", ".root .placeholder .hyperlink|-fx-text-fill")) {
            double contrast = contrastRatio(light.get(rule), ground);
            Assertions.assertTrue(contrast >= 4.0d,
                    rule + " is " + light.get(rule) + " at " + String.format("%.2f", contrast) + ":1 on " + ground
                            + "; the dark theme's shade manages only 3.1:1 there, which is why light states its own");
        }
    }

    private static double contrastRatio(String color, String against) {
        double a = relativeLuminance(color);
        double b = relativeLuminance(against);
        return (Math.max(a, b) + 0.05d) / (Math.min(a, b) + 0.05d);
    }

    private static double relativeLuminance(String hex) {
        String rgb = hex.trim().replace("#", "");
        double[] channel = new double[3];
        for(int i = 0; i < 3; i++) {
            double value = Integer.parseInt(rgb.substring(i * 2, i * 2 + 2), 16) / 255d;
            channel[i] = value <= 0.03928d ? value / 12.92d : Math.pow((value + 0.055d) / 1.055d, 2.4d);
        }
        return 0.2126d * channel[0] + 0.7152d * channel[1] + 0.0722d * channel[2];
    }

    /** Maps "selector|property" to the declared value, for the color properties the themes share. */
    private static Map<String, String> colorDeclarations(String stylesheet) throws Exception {
        String css;
        try(var in = AppServices.class.getResourceAsStream(stylesheet)) {
            Assertions.assertNotNull(in, stylesheet + " is not on the classpath");
            css = new String(in.readAllBytes());
        }
        Map<String, String> declarations = new LinkedHashMap<>();
        //A comment ahead of a rule would otherwise be read as part of its selector
        css = css.replaceAll("(?s)/\\*.*?\\*/", "");
        Matcher rules = Pattern.compile("([^{}]+)\\{([^}]*)}").matcher(css);
        while(rules.find()) {
            String selector = rules.group(1).trim().replaceAll("\\s+", " ");
            Matcher properties = Pattern.compile("(-fx-accent|-fx-default-button|-fx-faint-focus-color|-fx-text-fill)\\s*:([^;]+);").matcher(rules.group(2));
            while(properties.find()) {
                declarations.put(selector + "|" + properties.group(1), properties.group(2).trim());
            }
        }
        return declarations;
    }

    @Test
    public void togglingLeavesExactlyOneThemeStylesheet() {
        List<String> stylesheets = new ArrayList<>();

        Config.get().setTheme(Theme.LIGHT);
        AppServices.applyThemeStylesheet(stylesheets);
        Assertions.assertEquals(1, themeStylesheets(stylesheets).size());
        Assertions.assertTrue(themeStylesheets(stylesheets).getFirst().endsWith("lighttheme.css"));

        Config.get().setTheme(Theme.DARK);
        AppServices.applyThemeStylesheet(stylesheets);
        Assertions.assertEquals(1, themeStylesheets(stylesheets).size(), "the previous theme was left behind");
        Assertions.assertTrue(themeStylesheets(stylesheets).getFirst().endsWith("darktheme.css"));

        //Back again, and repeatedly, since the handler runs on every change
        Config.get().setTheme(Theme.LIGHT);
        AppServices.applyThemeStylesheet(stylesheets);
        AppServices.applyThemeStylesheet(stylesheets);
        Assertions.assertEquals(1, themeStylesheets(stylesheets).size(), "reapplying accumulated stylesheets");
        Assertions.assertTrue(themeStylesheets(stylesheets).getFirst().endsWith("lighttheme.css"));
    }

    @Test
    public void otherStylesheetsSurviveAThemeChange() {
        List<String> stylesheets = new ArrayList<>();
        String appCss = AppServices.class.getResource("app.css").toExternalForm();
        stylesheets.add(appCss);

        Config.get().setTheme(Theme.LIGHT);
        AppServices.applyThemeStylesheet(stylesheets);
        Config.get().setTheme(Theme.DARK);
        AppServices.applyThemeStylesheet(stylesheets);

        Assertions.assertTrue(stylesheets.contains(appCss), "app.css was removed with the theme");
    }
}
