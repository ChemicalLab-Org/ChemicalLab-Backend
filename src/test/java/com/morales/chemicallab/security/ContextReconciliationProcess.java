package com.morales.chemicallab.security;

import java.nio.file.*;
import java.sql.*;
import java.util.concurrent.TimeUnit;

/** Independent fixture SQL contender; service tests separately prove mandatory T08 atomicity. */
public final class ContextReconciliationProcess {
    public static void main(String[] args) throws Exception {
        if(!args[0].matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/chemicallab_t01_test")||!args[3].matches("t03_[a-f0-9]{32}"))throw new IllegalArgumentException("Owned fixtures only");
        Path ready=Path.of(args[5]),go=Path.of(args[6]),result=Path.of(args[7]);
        try(Connection c=DriverManager.getConnection(args[0]+"?currentSchema="+args[3],args[1],args[2])) {
            c.setAutoCommit(false);c.createStatement().execute("set local statement_timeout='50s'");Files.writeString(ready,""+ProcessHandle.current().pid());
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(25);while(!Files.exists(go)&&System.nanoTime()<deadline)Thread.sleep(20);
            if(!Files.exists(go))throw new IllegalStateException("Race barrier did not open");
            c.createStatement().execute("select t11p05_lock()");
            try(var p=c.prepareStatement("insert into t11b10_commands(operation_id,action,request) values(gen_random_uuid(),'APPLY',jsonb_build_object('proposal',?::bigint))")){p.setLong(1,Long.parseLong(args[4]));p.executeUpdate();}
            String status;try(var p=c.prepareStatement("select t11p05_status(?)")){p.setLong(1,Long.parseLong(args[4]));try(var rows=p.executeQuery()){rows.next();status=rows.getString(1);}}
            c.commit();Files.writeString(result,status);
        }
    }
}
