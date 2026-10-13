package com.morales.chemicallab.security;

import com.morales.chemicallab.service.BoardActivityReconciliationService;
import java.nio.file.*;
import java.util.concurrent.TimeUnit;

/** A separate JVM, an already expanded owned schema, and no Hibernate/schema installer. */
public final class BoardActivityReconciliationProcess {
    public static void main(String[] args) throws Exception {
        if(!args[0].matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/chemicallab_t01_test") || !args[3].matches("t03_[a-f0-9]{32}"))
            throw new IllegalArgumentException("Owned fixture only");
        System.setProperty("spring.datasource.url",args[0]);
        System.setProperty("spring.datasource.username",args[1]);
        System.setProperty("spring.datasource.password",args[2]);
        var f=new WhiteboardSecurityTransportDbTest(); f.schema=args[3];
        try(var app=f.startApp("--spring.jpa.hibernate.ddl-auto=none","--app.storage.install-schema=false","--app.audit.install-schema=false")) {
            Files.writeString(Path.of(args[5]),"ready "+ProcessHandle.current().pid());
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(60);
            while(!Files.exists(Path.of(args[6]))) {
                if(System.nanoTime()>deadline) throw new IllegalStateException("Barrier timed out");
                Thread.sleep(20);
            }
            Files.writeString(Path.of(args[7]),app.getBean(BoardActivityReconciliationService.class).apply(Long.parseLong(args[4])));
        }
    }
}
