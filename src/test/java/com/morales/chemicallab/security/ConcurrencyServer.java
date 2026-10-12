package com.morales.chemicallab.security;

import java.nio.file.*;
import java.util.Base64;

/** Separate JVM used only by the guarded T10 PostgreSQL fixture. */
public final class ConcurrencyServer {
    public static void main(String[] args) throws Exception {
        if (!args[0].matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/chemicallab_t01_test")
                || !args[3].matches("t03_[a-f0-9]+")) throw new IllegalArgumentException("Disposable fixture only");
        System.setProperty("spring.datasource.url", args[0]);
        System.setProperty("spring.datasource.username", args[1]);
        System.setProperty("spring.datasource.password", args[2]);
        var f = new WhiteboardSecurityTransportDbTest();
        f.schema = args[3];
        System.arraycopy(Base64.getDecoder().decode(args[4]), 0, f.key, 0, 32);
        try (var app = f.startApp("--spring.jpa.hibernate.ddl-auto=none",
                "--app.storage.install-schema=false", "--app.audit.install-schema=false",
                "--spring.datasource.hikari.connection-init-sql=SET lock_timeout = '30s'")) {
            Files.writeString(Path.of(args[5]), f.origin(app));
            // Parent removes the stop file directory/process even when an assertion fails.
            while (!Files.exists(Path.of(args[6]))) Thread.sleep(100);
        }
    }
}
