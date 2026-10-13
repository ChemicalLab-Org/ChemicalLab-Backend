package com.morales.chemicallab.security;

import java.nio.file.*;
import java.sql.*;
import java.util.concurrent.TimeUnit;

/** Independent OS process and PostgreSQL transaction; no schema installer. */
public final class IdentityReservationProcess {
    public static void main(String[] a) throws Exception {
        if(!a[0].matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/chemicallab_t01_test") || !a[3].matches("t03_[a-f0-9]{32}"))
            throw new IllegalArgumentException("Owned fixture only");
        try(Connection c=DriverManager.getConnection(a[0]+"?currentSchema="+a[3],a[1],a[2])) {
            c.setAutoCommit(false);
            Files.writeString(Path.of(a[7]),"ready "+ProcessHandle.current().pid());
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(45);
            while(!Files.exists(Path.of(a[8]))) {if(System.nanoTime()>deadline)throw new IllegalStateException("Barrier timeout");Thread.sleep(20);}
            String result;
            try(var q=c.prepareStatement("insert into t11b5_academic_codes(institution_id,membership_id,account_id,code,evidence) values(?,?,?,'RACE','FIXTURE-RACE')")) {
                q.setLong(1,Long.parseLong(a[4]));q.setLong(2,Long.parseLong(a[5]));q.setLong(3,Long.parseLong(a[6]));q.executeUpdate();c.commit();result="COMMITTED";
            } catch(SQLException e) {c.rollback();if(!"23505".equals(e.getSQLState()))throw e;result="REJECTED:23505";}
            Files.writeString(Path.of(a[9]),result);
        }
    }
}
