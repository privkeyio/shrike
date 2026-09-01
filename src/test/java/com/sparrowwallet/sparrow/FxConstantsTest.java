package com.sparrowwallet.sparrow;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Every enum constant named in FXML has to still exist.
 *
 * fx:constant resolves reflectively at load, and a missing field becomes a LoadException that the settings
 * controller rethrows as "Can't find pane", so the screen simply cannot open. Removing three fee rate sources
 * left them named in general.fxml and took the whole General settings screen with them, which compiles, passes
 * every unit test, and is only visible by opening the screen.
 */
public class FxConstantsTest {
    private static final Pattern CONSTANT = Pattern.compile("<([A-Za-z]+)\\s+fx:constant=\"([A-Za-z0-9_]+)\"");

    @Test
    public void testEveryConstantNamedInFxmlExists() throws Exception {
        List<String> missing = new ArrayList<>();

        try(var paths = Files.walk(Path.of("src/main/resources"))) {
            for(Path path : paths.filter(p -> p.toString().endsWith(".fxml")).toList()) {
                String contents = Files.readString(path);
                Matcher matcher = CONSTANT.matcher(contents);
                while(matcher.find()) {
                    String simpleName = matcher.group(1);
                    String constant = matcher.group(2);
                    Class<?> type = resolve(contents, simpleName);
                    if(type == null) {
                        continue;
                    }
                    try {
                        type.getField(constant);
                    } catch(NoSuchFieldException e) {
                        missing.add(path.getFileName() + ": " + simpleName + "." + constant);
                    }
                }
            }
        }

        Assertions.assertTrue(missing.isEmpty(),
                "FXML names constants that no longer exist, so those screens cannot open: " + missing);
    }

    /** The import in the FXML itself says which class a tag refers to. */
    private Class<?> resolve(String contents, String simpleName) {
        Matcher imported = Pattern.compile("<\\?import ([a-z0-9_.]+\\." + simpleName + ")\\?>").matcher(contents);
        if(!imported.find()) {
            return null;
        }
        try {
            return Class.forName(imported.group(1));
        } catch(ClassNotFoundException e) {
            return null;
        }
    }
}
