package com.morales.chemicallab.security;

import java.nio.file.*;
import java.sql.*;
import java.util.concurrent.TimeUnit;

/** Independent SQL integrity contender; fixtures only, not a public or audited business writer. */
public final class GlobalIdentifierActivationProcess {
    public static void main(String[] args) throws Exception {
        if(!args[0].matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/chemicallab_t01_test")||!args[3].matches("t03_[a-f0-9]{32}"))throw new IllegalArgumentException("Owned fixtures only");
        Path ready=Path.of(args[6]),go=Path.of(args[7]),result=Path.of(args[8]);
        try(Connection c=DriverManager.getConnection(args[0]+"?currentSchema="+args[3],args[1],args[2])) {
            c.setAutoCommit(false);c.createStatement().execute("set local statement_timeout='25s'");Files.writeString(ready,""+ProcessHandle.current().pid());
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(20);while(!Files.exists(go)&&System.nanoTime()<deadline)Thread.sleep(20);
            if(!Files.exists(go))throw new IllegalStateException("Race barrier did not open");
            try {
                c.createStatement().execute("select t11b7_lock()");
                try(var p=c.prepareStatement("update t11b7_identifiers set status='ACTIVE',version=version+1,proof_id=? where id=? and version=1")) {
                    p.setLong(1,Long.parseLong(args[5]));p.setLong(2,Long.parseLong(args[4]));if(p.executeUpdate()!=1)throw new IllegalStateException("Stale contender");
                }
                c.commit();Files.writeString(result,"COMMITTED");
            } catch(SQLException failure){c.rollback();Files.writeString(result,"REJECTED:"+failure.getSQLState());}
        }
    }
}
