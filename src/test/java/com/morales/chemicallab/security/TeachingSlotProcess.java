package com.morales.chemicallab.security;

import java.nio.file.*;
import java.sql.*;
import java.util.concurrent.TimeUnit;

/** Separate OS process for the cross-function PostgreSQL exclusion race. Fictitious DB only. */
public final class TeachingSlotProcess {
    public static void main(String[] args) throws Exception {
        if(!args[0].matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/chemicallab_t01_test")||!args[3].matches("t03_[a-f0-9]{32}"))throw new IllegalArgumentException("Owned fixtures only");
        String table=switch(args[4]){case "RESPONSABLE"->"teaching_assignments";case "CO_DOCENTE"->"t11b6_co_assignments";default->throw new IllegalArgumentException();};
        Path ready=Path.of(args[8]),go=Path.of(args[9]),result=Path.of(args[10]);
        try(Connection c=DriverManager.getConnection(args[0]+"?currentSchema="+args[3],args[1],args[2])) {
            c.setAutoCommit(false);c.createStatement().execute("set local statement_timeout='25s'");
            Files.writeString(ready,""+ProcessHandle.current().pid());
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(20);
            while(!Files.exists(go)&&System.nanoTime()<deadline)Thread.sleep(20);
            if(!Files.exists(go))throw new IllegalStateException("Race barrier did not open");
            try {
                long id;
                try(var p=c.prepareStatement("insert into "+table+"(institution_id,class_id,membership_id,valid_from,valid_until,state_since,change_reason) values(?,?,?,'2026-01-01 00:00:10Z','2027-01-01 00:00:00Z','2026-01-01 00:00:03Z','CREATED') returning id")) {
                    p.setLong(1,Long.parseLong(args[5]));p.setLong(2,Long.parseLong(args[6]));p.setLong(3,Long.parseLong(args[7]));
                    try(var rows=p.executeQuery()){rows.next();id=rows.getLong(1);}
                }
                c.createStatement().execute("update "+table+" set status='ACTIVE',state_since='2026-01-01 00:00:10Z',activated_at='2026-01-01 00:00:10Z',change_reason='ACTIVE' where id="+id);
                c.commit();Files.writeString(result,"COMMITTED");
            } catch(SQLException failure) {c.rollback();Files.writeString(result,"REJECTED:"+failure.getSQLState());}
        }
    }
}
