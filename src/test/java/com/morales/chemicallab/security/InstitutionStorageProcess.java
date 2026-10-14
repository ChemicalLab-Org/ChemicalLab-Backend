package com.morales.chemicallab.security;

import java.nio.file.*;
import java.sql.*;
import java.util.concurrent.TimeUnit;

/** Independent SQL integrity contender. Not a production or audited business writer. */
public final class InstitutionStorageProcess {
    public static void main(String[] args) throws Exception {
        if(!args[0].matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/chemicallab_t01_test")||!args[3].matches("t03_[a-f0-9]{32}"))throw new IllegalArgumentException("Owned fixtures only");
        Path ready=Path.of(args[5]),go=Path.of(args[6]),result=Path.of(args[7]);
        try(Connection c=DriverManager.getConnection(args[0]+"?currentSchema="+args[3],args[1],args[2])) {
            c.setAutoCommit(false);c.createStatement().execute("set local statement_timeout='40s'");Files.writeString(ready,""+ProcessHandle.current().pid());
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(25);while(!Files.exists(go)&&System.nanoTime()<deadline)Thread.sleep(20);
            if(!Files.exists(go))throw new IllegalStateException("Race barrier did not open");
            try {
                c.createStatement().execute("select t11b9_lock()");
                try(var p=c.prepareStatement("insert into t11b8_revisions(resource_id,institution_id,parent_id,revision_number,payload,payload_checksum,operation_id) select r.resource_id,r.institution_id,r.id,(select max(revision_number)+1 from t11b8_revisions where resource_id=r.resource_id),r.payload,r.payload_checksum,gen_random_uuid() from t11b8_revisions r where r.id=?")){p.setLong(1,Long.parseLong(args[4]));p.executeUpdate();}
                c.createStatement().execute("insert into t11b9_commands(operation_id,kind,request) values(gen_random_uuid(),'SYNC','{\"fixture\":\"RACE\"}')");
                c.commit();Files.writeString(result,"COMMITTED");
            }catch(SQLException failure){c.rollback();Files.writeString(result,"REJECTED:"+failure.getSQLState());}
        }
    }
}
