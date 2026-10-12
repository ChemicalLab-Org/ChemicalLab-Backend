package com.morales.chemicallab.security.files;

import java.nio.file.*;

/** Invoked through Boot PropertiesLauncher after package, to exercise the outer runnable-jar worker path. */
public class PackagedFileProbe {
    public static void main(String[] args) throws Exception {
        var result=new FileValidationService().historical(Files.readAllBytes(Path.of(args[0])),null,"fictitious",false);
        Files.write(Path.of(args[1]),result.data());
        System.out.println("Packaged validator: " + result.contentType());
    }
}
