package com.morales.chemicallab.security;

import java.nio.file.*;
import java.sql.*;
import java.util.concurrent.TimeUnit;

/** Independent SQL integrity contender, never a production/audited business writer. */
public final class AcademicRevisionProcess {
    public static void main(String[] args) throws Exception {
        if(!args[0].matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/chemicallab_t01_test")||!args[3].matches("t03_[a-f0-9]{32}"))throw new IllegalArgumentException("Owned fixtures only");
        Path ready=Path.of(args[6]),go=Path.of(args[7]),result=Path.of(args[8]);
        try(Connection c=DriverManager.getConnection(args[0]+"?currentSchema="+args[3],args[1],args[2])) {
            c.setAutoCommit(false);c.createStatement().execute("set local statement_timeout='25s'");Files.writeString(ready,""+ProcessHandle.current().pid());
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(20);while(!Files.exists(go)&&System.nanoTime()<deadline)Thread.sleep(20);
            if(!Files.exists(go))throw new IllegalStateException("Race barrier did not open");
            try {
                c.createStatement().execute("select t11b8_lock()");
                String query=switch(args[4]) {
                    case "PUBLISH" -> "update t11b8_revisions set status='PUBLISHED',version=2 where id=? and version=1";
                    case "EDIT" -> "update t11b8_revisions set payload=jsonb_set(payload,'{resource,title}','\"Race edit\"'),payload_checksum=md5(jsonb_set(payload,'{resource,title}','\"Race edit\"')::text),version=2 where id=? and version=1";
                    case "NEXT" -> "insert into t11b8_revisions(resource_id,institution_id,parent_id,revision_number,payload,payload_checksum,operation_id) select r.resource_id,r.institution_id,r.id,(select max(revision_number)+1 from t11b8_revisions where resource_id=r.resource_id),r.payload,r.payload_checksum,gen_random_uuid() from t11b8_revisions r where r.id=?";
                    case "START" -> """
                        insert into t11b8_fixture_attempts(resource_id,institution_id,account_id,recipient_id,revision_id,attempt_number,configuration,question_order,started_at,operation_id)
                         select rec.resource_id,rec.institution_id,rec.account_id,rec.id,d.revision_id,
                          (select count(*)+1 from t11b8_fixture_attempts a where a.resource_id=rec.resource_id and a.account_id=rec.account_id),
                          rev.payload->'resource','[]'::jsonb,statement_timestamp(),gen_random_uuid()
                          from t11b8_recipients rec join t11b8_deliveries d on d.id=rec.delivery_id join t11b8_revisions rev on rev.id=d.revision_id where rec.id=?
                        """;
                    default -> throw new IllegalArgumentException("Unknown fixture mode");
                };
                try(var p=c.prepareStatement(query)){p.setLong(1,Long.parseLong(args[5]));if(p.executeUpdate()!=1)throw new SQLException("Stale contender","40001");}
                c.commit();Files.writeString(result,"COMMITTED");
            } catch(SQLException failure){c.rollback();Files.writeString(result,"REJECTED:"+failure.getSQLState());}
        }
    }
}
