package com.morales.chemicallab.security.files;

import java.util.Arrays;
import java.util.LinkedHashMap;

/** Run through PropertiesLauncher after package; never substitutes classes/ for the outer JAR. */
public final class PackagedFormatsProbe {
    public static void main(String[] args) throws Exception {
        var validator = new FileValidationService();
        var command = validator.workerCommand();
        if (!command.contains("-jar") || !command.contains("--file-validation-worker"))
            throw new AssertionError("Probe must exercise a child of the runnable JAR");
        var fixtures = new LinkedHashMap<String, byte[]>();
        fixtures.put("application/pdf", FileFixtures.pdf());
        fixtures.put("application/vnd.ms-powerpoint", FileFixtures.ppt());
        fixtures.put("application/vnd.openxmlformats-officedocument.presentationml.presentation", FileFixtures.pptx());
        fixtures.put("image/png", FileFixtures.png());
        fixtures.put("image/jpeg", FileFixtures.jpeg());
        for (var entry : fixtures.entrySet()) {
            var result = validator.historical(entry.getValue(), null, "synthetic", false);
            if (!result.contentType().equals(entry.getKey()) || result.data().length == 0)
                throw new AssertionError("Incorrect packaged validation: " + entry.getKey());
            boolean rejected = false;
            try { validator.historical(Arrays.copyOf(entry.getValue(), 12), null, "synthetic", false); }
            catch (IllegalArgumentException expected) { rejected = true; }
            if (!rejected) throw new AssertionError("Truncated format accepted: " + entry.getKey());
            System.out.println("PASS outer-JAR child: valid + truncated " + entry.getKey());
        }
        System.out.println("PASS 10 packaged format checks; bounded child JVM command verified");
    }
}
