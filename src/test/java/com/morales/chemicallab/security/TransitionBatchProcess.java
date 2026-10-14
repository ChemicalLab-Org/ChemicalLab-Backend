package com.morales.chemicallab.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.sql.*;
import java.util.concurrent.TimeUnit;

/** Separate JVM/connection. Test barriers deliberately surround the real PostgreSQL commit. */
public final class TransitionBatchProcess {
    public static void main(String[] args) throws Exception {
        if (!args[0].matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/chemicallab_t01_test") || !args[2].matches("t03_[a-f0-9]{32}"))
            throw new IllegalArgumentException("Owned fictitious PostgreSQL only");
        var json = new ObjectMapper();
        var request = json.readTree(Files.readString(Path.of(args[3])));
        Path ready=Path.of(args[4]),go=Path.of(args[5]),result=Path.of(args[6]);
        String mode=args[7];
        try (Connection c=DriverManager.getConnection(args[0]+"?currentSchema="+args[2],args[1],System.getenv("P06_FIXTURE_PASSWORD"))) {
            c.setAutoCommit(false);
            try (var q=c.createStatement()) { q.execute("set local lock_timeout='20s'");q.execute("set local statement_timeout='55s'"); }
            int backend;try(var q=c.createStatement();var rs=q.executeQuery("select pg_backend_pid()")){rs.next();backend=rs.getInt(1);}
            if(mode.equals("race")) { Files.writeString(ready,""+backend);await(go); }
            String answer;
            try(var q=c.prepareStatement("select t11p06_execute(?::uuid,?,?::jsonb)::text")) {
                q.setString(1,request.get("operation").asText());q.setString(2,request.get("action").asText());q.setString(3,request.get("request").toString());
                try(var rs=q.executeQuery()){rs.next();answer=rs.getString(1);}
            }
            if(mode.equals("before-commit")){Files.writeString(ready,""+backend);await(go);}
            c.commit();
            if(mode.equals("lost-response")){Files.writeString(ready,""+backend);await(go);}
            Files.writeString(result,answer);
        }
    }
    private static void await(Path path) throws Exception {
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(40);
        while(!Files.exists(path)&&System.nanoTime()<end)Thread.sleep(20);
        if(!Files.exists(path))throw new IllegalStateException("Fixture barrier expired");
    }
}
