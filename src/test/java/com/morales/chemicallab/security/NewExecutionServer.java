package com.morales.chemicallab.security;

import java.nio.file.*;
import java.util.Base64;

/** Separate HTTP/JDBC process, only disposable loopback fixtures. */
public final class NewExecutionServer {
    public static void main(String[] args) throws Exception {
        if(!args[0].matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/chemicallab_t01_test") || !args[3].matches("t03_[a-f0-9]{32}"))throw new IllegalArgumentException("Fixture only");
        System.setProperty("spring.datasource.url",args[0]);System.setProperty("spring.datasource.username",args[1]);System.setProperty("spring.datasource.password",args[2]);
        var f=new WhiteboardSecurityTransportDbTest();f.schema=args[3];System.arraycopy(Base64.getDecoder().decode(args[4]),0,f.key,0,32);
        try(var app=f.startApp("--spring.jpa.hibernate.ddl-auto=none","--app.storage.install-schema=false","--app.audit.install-schema=false",
            "--app.evaluations.institutional.enabled=true","--app.evaluations.institutional.new-execution-enabled=true","--app.evaluations.institutional.fixture-schema="+f.schema)) {
            Files.writeString(Path.of(args[5]),f.origin(app));while(!Files.exists(Path.of(args[6])))Thread.sleep(100);
        }
    }
}
