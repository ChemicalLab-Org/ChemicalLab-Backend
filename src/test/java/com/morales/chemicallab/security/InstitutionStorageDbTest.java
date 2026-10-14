package com.morales.chemicallab.security;

import com.morales.chemicallab.entity.*;
import com.morales.chemicallab.entity.institution.*;
import com.morales.chemicallab.service.*;
import com.morales.chemicallab.security.audit.AuditUnavailableException;
import org.junit.jupiter.api.*;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.*;

/** Real PostgreSQL; every case owns a fresh disposable schema and prior frozen chain. */
class InstitutionStorageDbTest {
    AcademicRevisionDbTest b8;
    ResourceReconciliationDbTest r;
    WhiteboardSecurityTransportDbTest f;
    InstitutionStorageMaintenanceService service;
    String expansion;
    long resource,foreignResource;
    AcademicRevision published;
    JdbcTemplate sql(){return f.sql();}
    long count(String q,Object... args){return b8.previous.previous.previous.base.tx().execute(s->{sql().execute("set local time zone 'UTC'");return sql().queryForObject(q,Long.class,args);});}
    UUID op(){return UUID.randomUUID();}
    @BeforeEach void start(TestInfo info) throws Exception {
        b8=new AcademicRevisionDbTest();r=b8.r;f=b8.f;b8.start();b8.fixture();
        service=f.app.getBean(InstitutionStorageMaintenanceService.class);
        resource=b8.fresh(3);published=b8.published(resource);
        long foreignMember=r.membership(r.foreign,f.teacher.getId(),MembershipRoleGrant.InstitutionalRole.DOCENTE);
        foreignResource=b8.service.createNew(AcademicRevisionMaintenanceService.Kind.EVALUATION,r.foreign,f.teacher.getId(),foreignMember,b8.payload(3,"Same chemistry"),op());
        b8.published(foreignResource);
        expansion=r.script("t11b9-v1");
        if(info.getTestMethod().orElseThrow().getName().startsWith("installationRollback")) {
            b8.previous.previous.previous.base.tx().executeWithoutResult(s->{sql().execute(expansion);s.setRollbackOnly();});
            assertThat(sql().queryForObject("select to_regclass(current_schema()||'.t11b9_schema_version') is null",Boolean.class)).isTrue();
        }
        sql().execute(expansion);
        for(long institution:List.of(r.institution,r.foreign))for(String category:List.of("ORIGINAL","MATERIAL","CAPTURE","REVISION","AUXILIARY","GLOBAL_IDENTITY"))
            service.limit(institution,category,1000000,268435456,op());
    }
    @AfterEach void stop() throws Exception {SecurityContextHolder.clearContext();if(f!=null)f.cleanup();}
    long baseline(){return service.baseline(service.checksum(),"FIXTURE-HISTORICAL",op());}
    boolean current(){return Boolean.TRUE.equals(sql().queryForObject("select source_current from t11b9_observation",Boolean.class));}
    Map<String,String> state() {
        var tables=sql().queryForList("select relname from pg_class where relnamespace=current_schema()::regnamespace and relkind='r' order by relname",String.class);
        return b8.previous.previous.signatures(tables.toArray(String[]::new));
    }
    long used(long institution,String category,String unit){return count("select "+unit+"_used from t11b9_counters where scope='INSTITUTION' and owner_id=? and category=?",institution,category);}

    @Test void independentSourceRecountAndSeparateSchoolsWithSharedAccount() {
        baseline();assertThat(current()).isTrue();
        for(var contract:sql().queryForList("select * from t11b9_contract order by source_table")) {
            String table=(String)contract.get("source_table");String predicate=table.equals("system_logs")?" where target_type is distinct from 'StorageMeasurement'":"";
            long actual=count("select count(*) from "+table+predicate);
            long bytes=count("select coalesce(sum(octet_length(convert_to(to_jsonb(x)::text,'UTF8'))),0) from "+table+" x"+predicate);
            assertThat(count("select coalesce(sum(rows_used),0) from t11b9_items where source_table=?",table)).as(table+" rows").isEqualTo(actual);
            assertThat(count("select coalesce(sum(bytes_used),0) from t11b9_items where source_table=?",table)).as(table+" bytes").isEqualTo(bytes);
        }
        for(long institution:List.of(r.institution,r.foreign)) {
            long expected=count("select count(*) from t11b8_revisions where institution_id=?",institution);
            assertThat(count("select count(*) from t11b9_items where source_table='t11b8_revisions' and owner_id=?",institution)).isEqualTo(expected);
            assertThat(count("select bytes_used from t11b9_items where source_table='t11b8_revisions' and source_pk=jsonb_build_object('id',?::bigint)",b8.draft(institution==r.institution?resource:foreignResource)))
                .isEqualTo(count("select octet_length(convert_to(to_jsonb(x)::text,'UTF8')) from t11b8_revisions x where resource_id=?",institution==r.institution?resource:foreignResource));
        }
        assertThat(sql().queryForMap("select * from t11b9_observation")).containsEntry("live_authoritative",false);
    }
    @Test void sameBaselineAndReconciliationOperationRepeatWithoutConsumptionOrSuccess() {
        UUID operation=op();String checksum=service.checksum();long id=service.baseline(checksum,"FIXTURE-HISTORICAL",operation);var before=state();
        assertThat(service.baseline(checksum,"FIXTURE-HISTORICAL",operation)).isEqualTo(id);assertThat(state()).isEqualTo(before);
        UUID verify=op();long result=service.reconcile(verify);before=state();assertThat(service.reconcile(verify)).isEqualTo(result);assertThat(state()).isEqualTo(before);
        assertThat(service.result(result)).contains("\"differences\": []","ROW_JSON_UTF8_V1");
        assertThatThrownBy(()->service.baseline(checksum,"FIXTURE-OTHER",operation)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void historicalExcessPreservesFullConsumptionAndNewGrowthIsRejected() {
        service.limit(r.institution,"REVISION",1,1,op());baseline();
        assertThat(used(r.institution,"REVISION","bytes")).isGreaterThan(1);var before=state();
        assertThatThrownBy(()->service.nextDraft(published.id(),op())).isInstanceOf(DataAccessException.class).hasMessageContaining("quota exceeded");assertThat(state()).isEqualTo(before);
    }
    @Test void byteLimitRejectsGrowthEvenWithAvailableRowCapacity() {
        baseline();service.limit(r.institution,"REVISION",1000000,used(r.institution,"REVISION","bytes"),op());var before=state();
        assertThatThrownBy(()->service.nextDraft(published.id(),op())).isInstanceOf(DataAccessException.class).hasMessageContaining("quota exceeded");assertThat(state()).isEqualTo(before);
    }
    @Test void baselineRequiresExactApprovalAndCannotOverwriteExistingMeasurement() {
        var before=state();assertThatThrownBy(()->service.baseline("0".repeat(32),"FIXTURE-HISTORICAL",op())).isInstanceOf(DataAccessException.class);
        assertThat(state()).isEqualTo(before);baseline();before=state();
        assertThatThrownBy(()->service.baseline(service.checksum(),"FIXTURE-HISTORICAL",op())).isInstanceOf(DataAccessException.class);assertThat(state()).isEqualTo(before);
    }
    @Test void storedCopiesCountAndClassReferencesDoNotMultiplyPayload() {
        long a=b8.delivered(published,r.classId),b=b8.delivered(published,r.otherClass);b8.admitted(a,r.episode);b8.admitted(b,r.otherEpisode);
        baseline();long original=used(r.institution,"REVISION","rows");long bytes=used(r.institution,"REVISION","bytes");
        UUID operation=op();var copy=service.nextDraft(published.id(),operation);
        assertThat(used(r.institution,"REVISION","rows")).isEqualTo(original+1);
        assertThat(used(r.institution,"REVISION","bytes")-bytes).isEqualTo(count("select octet_length(convert_to(to_jsonb(x)::text,'UTF8')) from t11b8_revisions x where id=?",copy.id()));
        assertThat(copy.payload()).isEqualTo(published.payload());var before=state();assertThat(service.nextDraft(published.id(),operation).id()).isEqualTo(copy.id());assertThat(state()).isEqualTo(before);
        assertThat(current()).isTrue();assertThat(count("select count(*) from t11b9_items where source_table='t11b8_revisions' and source_pk=jsonb_build_object('id',?::bigint)",published.id())).isEqualTo(1);
    }
    @Test void negativeByteDeltaIsMeasuredAndAppendOnlyHistoryStillConsumes() {
        var draft=b8.service.nextDraft(published.id(),op());draft=b8.service.edit(draft.id(),1,b8.payload(3,"Long title "+"x".repeat(120)),op());baseline();
        long revisionBytes=used(r.institution,"REVISION","bytes"),historyBytes=used(r.institution,"AUXILIARY","bytes");
        service.edit(draft.id(),2,b8.payload(3,"Short"),op());assertThat(used(r.institution,"REVISION","bytes")).isLessThan(revisionBytes);
        assertThat(used(r.institution,"AUXILIARY","bytes")).isGreaterThan(historyBytes);assertThat(current()).isTrue();
        assertThat(count("select count(*) from t11b9_deltas where source_table='t11b8_revisions' and bytes_delta<0")).isEqualTo(1);
    }
    @Test void legacyAccountGuardStillRejectsEvenWhenInstitutionHasCapacity() {
        baseline();sql().update("update storage_limits set maximum=1 where resource='REVISION_BYTES'");var before=state();
        assertThatThrownBy(()->service.nextDraft(published.id(),op())).isInstanceOf(DataAccessException.class).hasMessageContaining("STORAGE_QUOTA_EXCEEDED");assertThat(state()).isEqualTo(before);
    }
    @Test void mandatoryAuditFailureRollsBackBusinessBothCountersHistoryAndSuccess() {
        baseline();long id=used(r.institution,"REVISION","rows");var before=state();
        // Keep the table readable by measurement while making the required T08 insert fail.
        sql().execute("create function fixture_b9_audit_fail() returns trigger language plpgsql as $$ begin if NEW.target_type='StorageMeasurement' then raise exception 'fixture audit unavailable'; end if;return NEW;end $$");
        sql().execute("create trigger fixture_b9_audit_fail before insert on system_logs for each row execute function fixture_b9_audit_fail()");
        try{assertThatThrownBy(()->service.nextDraft(published.id(),op())).isInstanceOf(AuditUnavailableException.class);}
        finally{sql().execute("drop trigger fixture_b9_audit_fail on system_logs");sql().execute("drop function fixture_b9_audit_fail()");}
        assertThat(state()).isEqualTo(before);assertThat(used(r.institution,"REVISION","rows")).isEqualTo(id);
        service.nextDraft(published.id(),op());assertThat(current()).isTrue();
    }
    @Test void legacyMutationIsStaleAndReconciliationRecordsDifferencesWithoutRepair() {
        baseline();sql().update("update evaluations set title='Legacy drift' where id=?",count("select evaluation_id from t11b8_resources where id=?",resource));
        assertThat(current()).isFalse();var before=b8.previous.previous.signatures("t11b9_items","t11b9_counters","evaluations");
        long verification=service.reconcile(op());assertThat(service.result(verification)).contains("evaluations","source_checksum").doesNotContain("Legacy drift");
        assertThat(b8.previous.previous.signatures(before.keySet().toArray(String[]::new))).isEqualTo(before);
        assertThatThrownBy(()->service.nextDraft(published.id(),op())).isInstanceOf(IllegalStateException.class).hasMessageContaining("stale");
    }
    @Test void withdrawalReadmissionRoleRevocationAndTeacherChangesDoNotReleaseResources() {
        baseline();var before=b8.previous.previous.signatures("t11b9_counters","t11b9_items","t11b8_revisions","t11b3_concept_revisions","t11b3_evaluation_revisions");
        r.academic.transitionEnrollment(r.episode,Enrollment.Status.WITHDRAWN,r.from.plusSeconds(30));
        r.institutions.transitionMembership(r.member,InstitutionMembership.Status.ENDED,r.from.plusSeconds(31));
        r.institutions.transitionMembership(r.member,InstitutionMembership.Status.ACTIVE,r.from.plusSeconds(32));
        long role=count("select id from membership_role_grants where membership_id=? and role='DOCENTE'",r.authorMember);r.institutions.revokeRole(role,r.from.plusSeconds(33));
        sql().update("update student_profiles set teacher_id=? where user_id=?",count("select id from teacher_profiles where user_id=?",f.otherTeacher.getId()),f.student.getId());
        assertThat(b8.previous.previous.signatures(before.keySet().toArray(String[]::new))).isEqualTo(before);
        // Source audit/history growth is visible, with no fictional deletion or institution movement.
        assertThat(current()).isFalse();service.reconcile(op());assertThat(b8.previous.previous.signatures(before.keySet().toArray(String[]::new))).isEqualTo(before);
    }
    @Test void globalUnresolvedAndConflictingDataKeepConsumptionWithoutInventedSchool() throws Exception {
        long ambiguous=r.approved(ResourceReconciliationService.SourceKind.EVALUATION,r.evaluation,r.map(List.of(),List.of()).replace("\"institutionId\":"+r.institution,"\"institutionId\":"+r.foreign));
        assertThat(r.service.apply(ambiguous)).isEqualTo("CONFLICT");
        b8.previous.username(f.teacher);baseline();
        for(String scope:List.of("GLOBAL","UNRESOLVED","CONFLICT"))assertThat(count("select coalesce(sum(rows_used),0) from t11b9_counters where scope=? and owner_id=0",scope)).isGreaterThan(0);
        assertThat(count("select count(*) from t11b9_items where source_table like 't11b7_%' and scope<>'GLOBAL'")).isZero();
        assertThat(count("select count(*) from t11b9_items where scope<>'INSTITUTION' and owner_id<>0")).isZero();
    }
    @Test void physicalMaterialAndCaptureCopiesMeasuredOnceByTheirTypedSources() throws Exception {
        long concept=b8.previous.previous.concept(r.institution,r.authorMember);
        byte[] file;try(var pdf=new org.apache.pdfbox.pdmodel.PDDocument();var output=new java.io.ByteArrayOutputStream()) {
            pdf.addPage(new org.apache.pdfbox.pdmodel.PDPage());pdf.save(output);file=output.toByteArray();
        }
        sql().update("insert into concept_materials(concept_content_id,type,original_file_name,content_type,file_data,file_size,active) values(?,'FILE','fixture.pdf','application/pdf',?,?,true)",concept,file,file.length);
        String payload=sql().queryForObject("select (t11b3_snapshot('CONCEPT',?)-'assignments')::text",String.class,concept);
        long copiedResource=b8.service.createNew(AcademicRevisionMaintenanceService.Kind.CONCEPT,r.institution,f.teacher.getId(),r.authorMember,payload,op());
        var copied=b8.published(copiedResource);
        sql().update("update whiteboard_sessions set final_snapshot_data=decode('01020304','hex'),status='CLOSED' where id=?",f.board);
        var b4=f.app.getBean(BoardActivityReconciliationService.class);
        String mapping=f.json.writeValueAsString(Map.of("institutionId",r.institution,"classId",r.classId,"authorAccountId",f.teacher.getId(),"authorMembershipId",r.authorMember,"participants",List.of()));
        long p=b4.propose(BoardActivityReconciliationService.SourceKind.BOARD,f.board,mapping);
        b4.approve(p,sql().queryForObject("select md5(source_checksum||proposal_checksum) from t11b4_reconciliations where id=?",String.class,p),"FIXTURE-REVIEW");assertThat(b4.apply(p)).isEqualTo("APPLIED");baseline();
        assertThat(count("select bytes_used from t11b9_items where source_table='concept_materials' and owner_id=?",r.institution))
            .isEqualTo(count("select octet_length(convert_to(to_jsonb(x)::text,'UTF8')) from concept_materials x where concept_content_id=?",concept));
        assertThat(count("select rows_used from t11b9_items where source_table='whiteboard_sessions' and source_pk=jsonb_build_object('id',?::bigint)",f.board)).isEqualTo(1);
        assertThat(count("select owner_id from t11b9_items where source_table='whiteboard_sessions' and source_pk=jsonb_build_object('id',?::bigint)",f.board)).isEqualTo(r.institution);
        long copies=used(r.institution,"REVISION","rows"),bytes=used(r.institution,"REVISION","bytes");
        var successor=service.nextDraft(copied.id(),op());assertThat(successor.payload()).isEqualTo(copied.payload());
        assertThat(used(r.institution,"REVISION","rows")).isEqualTo(copies+1);
        assertThat(used(r.institution,"REVISION","bytes")-bytes).isEqualTo(count("select octet_length(convert_to(to_jsonb(x)::text,'UTF8')) from t11b8_revisions x where id=?",successor.id()));
    }
    @Test void directCounterReleaseTamperingAndTruncateAreRejected() {
        baseline();var before=state();
        for(String query:List.of("update t11b9_counters set rows_used=0","delete from t11b9_items","truncate t11b9_deltas cascade","update t11b9_items set owner_id=0","insert into t11b9_counters values('GLOBAL',0,'FAKE',0,0)"))
            assertThatThrownBy(()->sql().execute(query)).isInstanceOf(DataAccessException.class);
        assertThat(state()).isEqualTo(before);
    }
    @Test void physicalRemovalReleasesOnlyExistingSourceWhileStoredSnapshotsSurvive() throws Exception {
        long concept=b8.previous.previous.concept(r.institution,r.authorMember);
        long material=sql().queryForObject("insert into concept_materials(concept_content_id,type,url,active) values(?,'LINK','https://fixture.invalid',true) returning id",Long.class,concept);
        // The copied legacy revision predates this link. Its own stored bytes stay unchanged.
        baseline();long bytes=used(r.institution,"MATERIAL","bytes");long legacy=count("select used from storage_usage where scope='ACCOUNT' and owner_id=? and resource='MATERIAL_COUNT'",f.teacher.getId());
        var history=b8.previous.previous.signatures("t11b3_concept_revisions","t11b3_reconciliations","t11b3_reconciliation_events");
        UUID operation=op();service.removeFixtureMaterial(material,operation);assertThat(used(r.institution,"MATERIAL","bytes")).isLessThan(bytes);
        assertThat(count("select rows_used from t11b9_items where source_table='concept_materials' and source_pk=jsonb_build_object('id',?::bigint)",material)).isZero();
        assertThat(count("select used from storage_usage where scope='ACCOUNT' and owner_id=? and resource='MATERIAL_COUNT'",f.teacher.getId())).isEqualTo(legacy-1);
        assertThat(b8.previous.previous.signatures(history.keySet().toArray(String[]::new))).isEqualTo(history);assertThat(current()).isTrue();
        var before=state();service.removeFixtureMaterial(material,operation);assertThat(state()).isEqualTo(before);
        assertThatThrownBy(()->service.removeFixtureMaterial(material,op())).isInstanceOf(IllegalArgumentException.class);assertThat(state()).isEqualTo(before);
    }
    @Test void suppliedFakeDeltasCannotDiscountMeasuredSources() {
        baseline();var before=b8.previous.previous.signatures("t11b9_counters","concept_materials","t11b8_revisions");
        sql().update("insert into t11b9_commands(operation_id,kind,request) values(?,'SYNC','{\"rowsDelta\":-99999,\"bytesDelta\":-9999999}')",op());
        assertThat(b8.previous.previous.signatures(before.keySet().toArray(String[]::new))).isEqualTo(before);
    }
    @Test void divergentCounterIsReportedAgainstIndependentSourcesWithoutRepair() {
        baseline();var before=state();
        b8.previous.previous.previous.base.tx().executeWithoutResult(s->{
            sql().execute("alter table t11b9_counters disable trigger t11b9_guard");
            sql().update("update t11b9_counters set rows_used=rows_used+7 where scope='INSTITUTION' and owner_id=? and category='REVISION'",r.institution);
            sql().execute("alter table t11b9_counters enable trigger t11b9_guard");
            assertThat(current()).isFalse();long corrupted=used(r.institution,"REVISION","rows");
            long id=service.reconcile(op());assertThat(service.result(id)).contains("counterDifferences","storedRows","measuredRows");
            assertThat(used(r.institution,"REVISION","rows")).isEqualTo(corrupted);
            s.setRollbackOnly();
        });
        assertThat(state()).isEqualTo(before);assertThat(current()).isTrue();
    }
    @Test void canonicalBytesAndChecksumsDoNotDependOnSessionTimezoneOrByteaOutput() {
        baseline();String expected=service.checksum();
        b8.previous.previous.previous.base.tx().executeWithoutResult(s->{
            sql().execute("set local time zone 'Pacific/Auckland'");sql().execute("set local bytea_output='escape'");
            assertThat(service.checksum()).isEqualTo(expected);assertThat(current()).isTrue();
        });
    }
    @Test void installationRollbackRepeatPredecessorAndSuccessorDivergencePreserveLedgers() {
        baseline();var before=state();sql().execute(expansion);sql().execute(b8.expansion);assertThat(state()).isEqualTo(before);
        for(String table:List.of("t11b8_revisions","t11b9_items")) {
            b8.previous.previous.previous.base.tx().executeWithoutResult(s->{sql().execute("alter table "+table+" disable trigger "+(table.startsWith("t11b8")?"t11b8_guard":"t11b9_guard"));
                assertThatThrownBy(()->sql().execute(expansion)).isInstanceOf(DataAccessException.class).hasMessageContaining("divergence");s.setRollbackOnly();});
        }
        assertThat(state()).isEqualTo(before);sql().execute(expansion);
    }
    @Test void twoIndependentProcessesCompeteForLastRevisionAndNativeRestoreCertifies() throws Exception {
        baseline();long rows=used(r.institution,"REVISION","rows");service.limit(r.institution,"REVISION",rows+1,268435456,op());
        Path dir=Files.createDirectories(Path.of("target","t11b9-processes",f.schema));Path go=dir.resolve("go");var children=new ArrayList<Process>();
        try(Connection barrier=DriverManager.getConnection(f.setting("spring.datasource.url")+"?currentSchema="+f.schema,f.setting("spring.datasource.username"),f.setting("spring.datasource.password"))) {
            barrier.setAutoCommit(false);barrier.createStatement().execute("select t11b9_lock()");
            try {
                for(int i=1;i<=2;i++) {
                    var args=List.of(Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name").startsWith("Windows")?"java.exe":"java").toString(),"-cp",System.getProperty("surefire.test.class.path",System.getProperty("java.class.path")),InstitutionStorageProcess.class.getName(),
                        f.setting("spring.datasource.url"),f.setting("spring.datasource.username"),f.setting("spring.datasource.password"),f.schema,""+published.id(),dir.resolve(i+".ready").toAbsolutePath().toString(),go.toAbsolutePath().toString(),dir.resolve(i+".result").toAbsolutePath().toString());
                    children.add(new ProcessBuilder(args).redirectErrorStream(true).redirectOutput(dir.resolve(i+".log").toFile()).start());
                }
                long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(25);
                while(!(Files.exists(dir.resolve("1.ready"))&&Files.exists(dir.resolve("2.ready")))&&System.nanoTime()<deadline)Thread.sleep(20);
                assertThat(Files.exists(dir.resolve("1.ready"))).isTrue();assertThat(Files.exists(dir.resolve("2.ready"))).isTrue();Files.writeString(go,"go");
                List<Integer> pids=List.of();deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);
                while(System.nanoTime()<deadline){pids=sql().queryForList("select pid from pg_stat_activity where datname=current_database() and wait_event_type='Lock' and query='select t11b9_lock()'",Integer.class);if(pids.size()==2)break;Thread.sleep(20);}
                assertThat(pids).hasSize(2);barrier.commit();
                for(Process p:children){assertThat(p.waitFor(45,TimeUnit.SECONDS)).isTrue();assertThat(p.exitValue()).isZero();}
                var results=List.of(Files.readString(dir.resolve("1.result")),Files.readString(dir.resolve("2.result")));
                assertThat(results).containsExactlyInAnyOrder("COMMITTED","REJECTED:P7501");assertThat(used(r.institution,"REVISION","rows")).isEqualTo(rows+1);
                assertThat(current()).isTrue();Files.writeString(dir.resolve("evidence.json"),f.json.writeValueAsString(Map.of("osPids",children.stream().map(Process::pid).toList(),"blockedPostgresPids",pids,"results",results,"scope","SQL integrity; mandatory service audit tested separately")));
            } finally {barrier.rollback();for(Process p:children)if(p.isAlive())p.destroyForcibly();}
        }
        nativeRestore();
    }
    void nativeRestore() throws Exception {
        Path dir=Files.createDirectories(Path.of("target","t11b9-native",f.schema));String db="chemicallab_t11b9_restore_"+UUID.randomUUID().toString().replace("-","");
        var before=state();String url=f.setting("spring.datasource.url"),port=url.substring(url.indexOf(':',18)+1,url.lastIndexOf('/'));
        var args=List.of("-h","127.0.0.1","-p",port,"-U",f.setting("spring.datasource.username"));boolean created=false;
        try {
            r.nativeTool("pg_dump",args,List.of("-Fc","-d","chemicallab_t01_test","-f",dir.resolve("fixture.dump").toAbsolutePath().toString()),dir);
            r.nativeTool("createdb",args,List.of(db),dir);created=true;r.nativeTool("pg_restore",args,List.of("--exit-on-error","--single-transaction","-d",db,dir.resolve("fixture.dump").toAbsolutePath().toString()),dir);
            try(Connection c=DriverManager.getConnection(url.substring(0,url.lastIndexOf('/')+1)+db,f.setting("spring.datasource.username"),f.setting("spring.datasource.password"));Statement q=c.createStatement()) {
                q.execute("set search_path to "+f.schema+",public");q.execute(expansion);Map<String,String> restored=new TreeMap<>();
                for(String table:before.keySet())try(var rows=q.executeQuery(r.signature(table))){rows.next();restored.put(table,rows.getString(1));}assertThat(restored).isEqualTo(before);
                assertThatThrownBy(()->q.execute("select t11b9_lock()")).isInstanceOf(SQLException.class).hasMessageContaining("fixtures");
                assertThatThrownBy(()->q.execute("truncate t11b9_items cascade")).isInstanceOf(SQLException.class).hasMessageContaining("append-only");
                Files.writeString(dir.resolve("signatures.json"),f.json.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of("source",before,"restored",restored,"certifier","B9 v1 frozen B8 chain")));
            }
        } finally {if(created)r.nativeTool("dropdb",args,List.of(db),dir);}
    }
}
