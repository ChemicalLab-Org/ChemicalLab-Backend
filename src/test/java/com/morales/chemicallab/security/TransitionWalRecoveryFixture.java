package com.morales.chemicallab.security;

import java.net.ServerSocket;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.*;

/** Own primary + physical base backup + archived WAL + isolated target. No application DB is overwritten.
 * Every native directory/port/process is created by this fixture; no service or existing cluster is stopped.
 */
final class TransitionWalRecoveryFixture {
    final TransitionBatchDbTest t;
    final Path dir,primary,base,archive;
    final int primaryPort,recoveryPort;
    final String user;
    final boolean windows=System.getProperty("os.name").startsWith("Windows");
    boolean primaryStarted,recoveryStarted;
    TransitionWalRecoveryFixture(TransitionBatchDbTest test) throws Exception {
        t=test;dir=Files.createDirectories(Path.of("target/t11b11-wal",t.f.schema)).toAbsolutePath();
        primary=dir.resolve("primary");base=dir.resolve("base");archive=Files.createDirectories(dir.resolve("archive"));
        primaryPort=port();recoveryPort=port();user=t.f.setting("spring.datasource.username");
    }
    static int port() throws Exception {try(var s=new ServerSocket(0,0,java.net.InetAddress.getByName("127.0.0.1"))){return s.getLocalPort();}}
    void exercise() throws Exception {
        try {
            nativeTool("initdb",List.of("-D",primary.toString(),"-U",user,"--auth=trust","--locale=C","--encoding=UTF8","--no-instructions"));
            String archiveCommand,restoreCommand;
            if(windows) {
                Path copier=dir.resolve("archive.cmd");
                Files.writeString(copier,"@echo off\r\nif exist \"%~2\" (\r\n  fc /b \"%~1\" \"%~2\" >nul\r\n  if errorlevel 1 exit /b 1\r\n  exit /b 0\r\n)\r\ncopy /b \"%~1\" \"%~2\" >nul\r\n");
                archiveCommand="cmd /c \"\""+copier.toString().replace('\\','/')+"\" \"%p\" \""+archive.toString().replace('\\','/')+"/%f\"\"";
                restoreCommand="copy /b /Y \""+archive.toString().replace('\\','/')+"/%f\" \"%p\"";
            } else {
                archiveCommand="test ! -f '"+archive+"/%f' && cp '%p' '"+archive+"/%f' || cmp -s '%p' '"+archive+"/%f'";
                restoreCommand="cp '"+archive+"/%f' '%p'";
            }
            Files.writeString(primary.resolve("postgresql.auto.conf"),"listen_addresses='127.0.0.1'\nport="+primaryPort+"\nmax_connections=30\nshared_buffers='16MB'\nwal_level=replica\narchive_mode=on\narchive_command="+literal(archiveCommand)+"\n");
            start(primary,"primary");primaryStarted=true;
            nativeTool("createdb",List.of("-h","127.0.0.1","-p",""+primaryPort,"-U",user,"chemicallab_t01_test"));
            try(var c=connect(primaryPort,false);var q=c.createStatement()){q.execute("create extension btree_gist");}
            var dump=new ArrayList<>(List.of("-h","127.0.0.1","-p",String.valueOf(t.f.setting("spring.datasource.url").split(":")[3].split("/")[0]),"-U",user,"-d","chemicallab_t01_test","-n",t.f.schema,"-n",t.ctl,"--no-owner","--no-privileges","-Fc","-f",dir.resolve("fixture.dump").toString()));
            nativeTool("pg_dump",dump);
            var restore=new ArrayList<>(connectionArgs(primaryPort,"chemicallab_t01_test"));restore.addAll(List.of("--exit-on-error","--single-transaction",dir.resolve("fixture.dump").toString()));
            nativeTool("pg_restore",restore);
            Map<String,String> before;
            try(var c=connect(primaryPort,true);var q=c.createStatement()) {
                q.execute(t.expansion);coverage(c);
                q.execute("create unlogged table "+t.ctl+".fixture_uncovered(id integer)");
                try{assertThatThrownBy(()->coverage(c)).hasMessageContaining("unlogged");}finally{q.execute("drop table "+t.ctl+".fixture_uncovered");}
                before=signatures(c);
            }
            var backup=new ArrayList<>(List.of("-h","127.0.0.1","-p",""+primaryPort,"-U",user,"-D",base.toString(),"--wal-method=stream","--checkpoint=fast"));
            nativeTool("pg_basebackup",backup);nativeTool("pg_verifybackup",List.of(base.toString()));
            Map<String,String> after;String target="P06_"+UUID.randomUUID().toString().replace("-","");String lsn,wal;
            String request=t.f.json.writeValueAsString(Map.of("plan",t.plan,"ordinal",1,"epoch",1));
            UUID operation=t.unit(1);Map<String,Object> changes=new LinkedHashMap<>();
            try(var c=connect(primaryPort,true)) {
                execute(c,operation,"STEP",request);
                c.setAutoCommit(false);
                try(var q=c.createStatement()) {
                    long concept=number(q,"select min(id) from concept_contents");
                    long owner=number(q,"select t.user_id from concept_contents c join teacher_profiles t on t.id=c.created_by_teacher_id where c.id="+concept);
                    long initialBytes=number(q,"select coalesce(sum(octet_length(file_data)),0) from concept_materials");
                    long material=number(q,"insert into concept_materials(concept_content_id,type,title,file_data,active,created_at) values("+concept+",'FILE','P06 retained',decode('010203','hex'),true,localtimestamp) returning id");
                    long deleted=number(q,"insert into concept_materials(concept_content_id,type,title,file_data,active,created_at) values("+concept+",'FILE','P06 deleted',decode('0405','hex'),true,localtimestamp) returning id");
                    q.executeUpdate("update concept_materials set file_data=decode('010203040506','hex'),title='P06 updated' where id="+material);
                    q.executeUpdate("delete from concept_materials where id="+deleted);
                    long period=number(q,"insert into academic_periods(institution_id,local_code,name,valid_from,valid_until,state_since,change_reason) values("+t.b.r.institution+",'P06-WAL','Fictitious WAL period','2026-01-01','2027-01-01','2026-01-01','CREATED') returning id");
                    q.executeUpdate("update academic_periods set status='OPEN',state_since='2026-01-02',change_reason='OPEN' where id="+period);
                    long tombstone=number(q,"insert into whiteboard_object_ownership(board_id,object_kind,object_id,owner_user_id,deleted) values("+t.f.board+",'TEXT','P06-WAL-OBJECT',"+t.f.student.getId()+",false) returning id");
                    q.executeUpdate("update whiteboard_object_ownership set deleted=true where id="+tombstone);
                    q.executeUpdate("insert into system_logs(event_type,category,severity,action,description,target_type,target_id,outcome,correlation_id,created_at) values('ADMIN_ACTION','ADMIN','INFO','ADMIN_ACTION','ADMIN_ACTION','ConceptContent',"+concept+",'SUCCESS',gen_random_uuid(),localtimestamp)");
                    assertThat(number(q,"select count(*) from academic_period_events where entity_id="+period)).isEqualTo(2);
                    assertThat(number(q,"select used from storage_usage where scope='ACCOUNT' and owner_id="+owner+" and resource='MATERIAL_BYTES'"))
                        .isEqualTo(number(q,"select coalesce(sum(octet_length(m.file_data)),0) from concept_materials m join concept_contents c on c.id=m.concept_content_id join teacher_profiles tp on tp.id=c.created_by_teacher_id where tp.user_id="+owner));
                    assertThat(number(q,"select coalesce(sum(octet_length(file_data)),0) from concept_materials")).isEqualTo(initialBytes+6);
                    changes.put("retainedMaterial",material);changes.put("deletedMaterial",deleted);changes.put("period",period);changes.put("tombstone",tombstone);
                }
                c.commit();c.setAutoCommit(true);coverage(c);
                after=signatures(c);assertThat(after).isNotEqualTo(before);
                try(var q=c.createStatement()) {
                    lsn=string(q,"select pg_create_restore_point('"+target+"')::text");
                    wal=string(q,"select pg_walfile_name('"+lsn+"'::pg_lsn)");
                    q.execute("select pg_switch_wal()");
                    q.executeUpdate("insert into concept_content_examples(concept_content_id,example_order,example) select min(id),987654,'AFTER_RECOVERY_TARGET' from concept_contents");
                }
            }
            long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(45);while(!Files.exists(archive.resolve(wal))&&System.nanoTime()<end)Thread.sleep(100);
            assertThat(Files.exists(archive.resolve(wal))).as("required post-backup WAL archived: %s",wal).isTrue();
            Files.writeString(base.resolve("postgresql.auto.conf"),"listen_addresses='127.0.0.1'\nport="+recoveryPort+"\nmax_connections=30\nshared_buffers='16MB'\narchive_mode=off\nrestore_command="+literal(restoreCommand)+"\nrecovery_target_name="+literal(target)+"\nrecovery_target_action='promote'\n");
            Files.writeString(base.resolve("recovery.signal"),"");
            start(base,"recovery");recoveryStarted=true;
            Map<String,String> recovered;
            try(var c=connect(recoveryPort,true);var q=c.createStatement()) {
                assertThat(string(q,"select pg_is_in_recovery()::text")).isEqualTo("false");
                q.execute(t.expansion);recovered=signatures(c);assertThat(recovered).isEqualTo(after);
                assertThat(number(q,"select count(*) from concept_content_examples where example='AFTER_RECOVERY_TARGET'")).isZero();
                assertThat(number(q,"select count(*) from "+t.ctl+".checkpoints where operation_id='"+operation+"'")).isEqualTo(1);
                // Same persisted request after recovery: query the operation first, then replay without a second effect.
                assertThat(string(q,"select t11p06_lookup('"+operation+"') is not null")).isEqualTo("t");
                String first=execute(c,operation,"STEP",request);String second=execute(c,operation,"STEP",request);assertThat(second).isEqualTo(first);
                assertThat(signatures(c)).isEqualTo(after);
                assertThatThrownBy(()->q.executeUpdate("update system_logs set outcome='SUCCESS'")).hasMessageContaining("append-only");
                assertThatThrownBy(()->q.executeUpdate("delete from "+t.ctl+".checkpoints")).hasMessageContaining("generated checkpoint");
            }
            stop(base,"recovery");recoveryStarted=false;start(base,"recovery-retry");recoveryStarted=true;
            try(var c=connect(recoveryPort,true);var q=c.createStatement()){q.execute(t.expansion);assertThat(signatures(c)).isEqualTo(after);}
            var evidence=new LinkedHashMap<String,Object>();evidence.put("mechanism","PostgreSQL physical base backup + archived WAL to named restore point");
            evidence.put("before",before);evidence.put("after",after);evidence.put("recovered",recovered);evidence.put("changes",changes);
            evidence.put("target",target);evidence.put("targetLsn",lsn);evidence.put("requiredArchivedWal",wal);evidence.put("tables",after.keySet().stream().filter(k->!k.startsWith("sequence:")).count());
            evidence.put("ports",List.of(primaryPort,recoveryPort));evidence.put("unloggedRejected",true);evidence.put("retryDuplicatedEffects",false);
            t.evidence("wal-recovery",evidence);
        } finally {
            if(recoveryStarted)stop(base,"recovery-final");if(primaryStarted)stop(primary,"primary-final");
        }
    }
    void coverage(Connection c) throws Exception {
        try(var q=c.createStatement()) {
            if(!string(q,"select current_setting('wal_level')||':'||current_setting('archive_mode')||':'||current_setting('fsync')||':'||current_setting('full_page_writes')||':'||current_setting('synchronous_commit')").equals("replica:on:on:on:on"))
                throw new IllegalStateException("Incomplete durable WAL configuration");
            if(number(q,"select count(*) from pg_class where relnamespace in ('"+t.f.schema+"'::regnamespace,'"+t.ctl+"'::regnamespace) and relkind in('r','m') and relpersistence<>'p'")!=0)
                throw new IllegalStateException("unlogged writer outside WAL coverage");
        }
    }
    Map<String,String> signatures(Connection c) throws Exception {
        Map<String,String> values=new TreeMap<>();List<String> tables=new ArrayList<>(),sequences=new ArrayList<>();
        try(var q=c.createStatement();var rs=q.executeQuery("select n.nspname||'.'||relname,relkind from pg_class r join pg_namespace n on n.oid=r.relnamespace where n.nspname in ('"+t.f.schema+"','"+t.ctl+"') and relkind in('r','S') order by 1")) {
            while(rs.next())(rs.getString(2).equals("r")?tables:sequences).add(rs.getString(1));
        }
        try(var q=c.createStatement()) {
            for(String table:tables)values.put(table,string(q,t.b.r.signature(table)));
            for(String seq:sequences)values.put("sequence:"+seq,string(q,"select last_value::text||':'||is_called::text from "+seq));
        }
        return values;
    }
    String execute(Connection c,UUID operation,String action,String request) throws Exception {
        try(var q=c.prepareStatement("select t11p06_execute(?,?,?::jsonb)::text")) {
            q.setObject(1,operation);q.setString(2,action);q.setString(3,request);try(var rs=q.executeQuery()){rs.next();return rs.getString(1);}
        }
    }
    Connection connect(int port,boolean schema) throws Exception {
        return DriverManager.getConnection("jdbc:postgresql://127.0.0.1:"+port+"/chemicallab_t01_test"+(schema?"?currentSchema="+t.f.schema:""),user,"FictitiousP06");
    }
    List<String> connectionArgs(int port,String db){return List.of("-h","127.0.0.1","-p",""+port,"-U",user,"-d",db);}
    void start(Path data,String label) throws Exception {nativeTool("pg_ctl",List.of("-D",data.toString(),"-l",dir.resolve(label+".log").toString(),"-w","-t","45","start"));}
    void stop(Path data,String label) throws Exception {nativeTool("pg_ctl",List.of("-D",data.toString(),"-m","fast","-w","-t","45","stop"));}
    void nativeTool(String name,List<String> args) throws Exception {
        String executable=windows?Path.of(System.getProperty("t11b3.pgBin","C:\\Program Files\\PostgreSQL\\18\\bin"),name+".exe").toString():name;
        var command=new ArrayList<String>();command.add(executable);command.addAll(args);
        Path log=dir.resolve(name+"-"+UUID.randomUUID()+".log");
        var builder=new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile());builder.environment().put("PGPASSWORD",t.f.setting("spring.datasource.password"));
        Process process=builder.start();
        try {assertThat(process.waitFor(60,TimeUnit.SECONDS)).as("native tool %s timed out: %s",name,log).isTrue();assertThat(process.exitValue()).as("native %s: %s",name,Files.readString(log)).isZero();}
        finally {if(process.isAlive())process.destroyForcibly();}
    }
    static String literal(String value){return "'"+value.replace("'","''")+"'";}
    static long number(Statement q,String sql) throws Exception {try(var rs=q.executeQuery(sql)){rs.next();return rs.getLong(1);}}
    static String string(Statement q,String sql) throws Exception {try(var rs=q.executeQuery(sql)){rs.next();return rs.getString(1);}}
}
