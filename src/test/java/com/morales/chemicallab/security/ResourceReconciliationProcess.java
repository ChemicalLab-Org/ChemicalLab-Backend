package com.morales.chemicallab.security;

import com.morales.chemicallab.service.ResourceReconciliationService;
import com.morales.chemicallab.service.EvaluationService;
import java.nio.file.*;
import java.util.concurrent.TimeUnit;

/** Separate JVM against an already expanded disposable fixture; no Hibernate repair. */
public final class ResourceReconciliationProcess {
    public static void main(String[] args) throws Exception {
        if(!args[0].matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/chemicallab_t01_test")||!args[3].matches("t03_[a-f0-9]{32}"))throw new IllegalArgumentException("Owned disposable fixture only");
        System.setProperty("spring.datasource.url",args[0]);System.setProperty("spring.datasource.username",args[1]);System.setProperty("spring.datasource.password",args[2]);
        var f=new WhiteboardSecurityTransportDbTest();f.schema=args[3];
        try(var app=f.startApp("--spring.jpa.hibernate.ddl-auto=none","--app.storage.install-schema=false","--app.audit.install-schema=false")) {
            Files.writeString(Path.of(args[5]),"ready "+ProcessHandle.current().pid());
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(60);
            while(!Files.exists(Path.of(args[6]))) {
                if(System.nanoTime()>deadline)throw new IllegalStateException("Fixture barrier timed out");
                Thread.sleep(20);
            }
            String result;
            if(args.length>8&&args[8].equals("start")) {
                try {result="STARTED:"+app.getBean(EvaluationService.class).startAttempt(args[9],Long.parseLong(args[4]),null).attemptNumber();}
                catch(IllegalArgumentException ex){result="REJECTED";}
            } else result=app.getBean(ResourceReconciliationService.class).apply(Long.parseLong(args[4]));
            Files.writeString(Path.of(args[7]),result);
        }
    }
}
