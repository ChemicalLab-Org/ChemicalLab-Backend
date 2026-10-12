package com.morales.chemicallab.security;

import com.morales.chemicallab.dto.*;
import com.morales.chemicallab.entity.*;
import com.morales.chemicallab.repository.*;
import com.morales.chemicallab.service.*;
import com.morales.chemicallab.security.storage.*;
import org.junit.jupiter.api.*;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.hibernate.SessionFactory;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

/** Real PostgreSQL, HTTP and STOMP. Database/schema guards come from the T03 fixture. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class StorageQueriesDbTest {
    final WhiteboardSecurityTransportDbTest f=new WhiteboardSecurityTransportDbTest(){
        @Override ConfigurableApplicationContext startApp(String... extra) {
            var args=new ArrayList<>(List.of("--app.retention.delete-enabled=true","--app.retention.batch-size=2","--spring.jpa.properties.hibernate.generate_statistics=true"));
            args.addAll(List.of(extra));return super.startApp(args.toArray(String[]::new));
        }
    };
    @BeforeAll void start() throws Exception {f.start();}
    @BeforeEach void fixture(){f.fixture();}
    @AfterEach void peers(){f.closePeers();resetLimits();}
    @AfterAll void stop() throws Exception {f.cleanup();}
    void resetLimits(){f.sql().update("update storage_limits set maximum=case resource when 'MATERIAL_BYTES' then 268435456 when 'MATERIAL_COUNT' then 1000 when 'SNAPSHOT_BYTES' then 268435456 when 'BOARD_BYTES' then 67108864 when 'BOARD_COUNT' then 500 when 'USAGE_COUNT' then 100000 when 'ATTEMPT_TELEMETRY_COUNT' then 20000 when 'ATTEMPT_EVENT_COUNT' then 2000 else 10000 end");}
    long used(String scope,long owner,String resource){return Optional.ofNullable(f.sql().queryForObject("select coalesce((select used from storage_usage where scope=? and owner_id=? and resource=?),0)",Long.class,scope,owner,resource)).orElse(0L);}
    void cap(String resource,long max){f.sql().update("update storage_limits set maximum=? where resource=?",max,resource);}
    long teacher(){return f.app.getBean(TeacherProfileRepository.class).findByUser(f.teacher).orElseThrow().getId();}
    long concept(){return f.app.getBean(ConceptContentRepository.class).saveAndFlush(ConceptContent.builder().title("Synthetic material").category("Synthetic").createdByTeacher(f.app.getBean(TeacherProfileRepository.class).findByUser(f.teacher).orElseThrow()).build()).getId();}
    long material(long concept,int bytes){return f.sql().queryForObject("insert into concept_materials(concept_content_id,type,active,file_data,file_size) values(?,'FILE',true,?,999999) returning id",Long.class,concept,new byte[bytes]);}
    void event(long user,String date,String module){f.sql().update("insert into usage_events(user_id,username,user_role,module,event_type,occurred_at) values(?,'synthetic','ESTUDIANTE',?,'MODULE_ACCESS',?::timestamp)",user,module,date);}
    TransactionTemplate tx(){return new TransactionTemplate(f.app.getBean(PlatformTransactionManager.class));}

    @Test void pagesAreStableAcrossTiesAndSummaryUsesAllFilteredUsersWithZeroEntityLoads() {
        f.sql().update("insert into user_accounts(username,password,role,active,temporary_password,credentials_version,created_at) select 't07bpage'||g,'synthetic-hash','ESTUDIANTE',true,false,0,'2026-07-01' from generate_series(1,237) g");
        f.sql().update("insert into student_profiles(user_id,teacher_id,student_code,names,last_names,grade,section) select id,?,username,'Equal','Name','2','D' from user_accounts where username like 't07bpage%'",teacher());
        f.sql().update("insert into usage_events(user_id,username,user_role,module,event_type,occurred_at) select u.id,u.username,u.role,'PERIODIC_TABLE','MODULE_ACCESS',timestamp '2026-07-01 10:00'+g*interval '1 minute' from user_accounts u cross join generate_series(0,19) g where u.username like 't07bpage%'");
        f.sql().update("insert into system_logs(actor_user_id,event_type,category,severity,created_at) select id,'LOGIN_SUCCESS','AUTH','INFO','2026-07-01 10:00' from user_accounts where username like 't07bpage%'");
        var stats=f.app.getBean(jakarta.persistence.EntityManagerFactory.class).unwrap(SessionFactory.class).getStatistics();stats.clear();
        var service=f.app.getBean(StudentUsageRecordService.class);Set<Long> ids=new HashSet<>();
        for(int page=0;page<10;page++) {
            var response=service.getRecords("ESTUDIANTE","t07bpage","2","d","2026-07-01","2026-07-01",null,"true",page,25);
            assertThat(response.summary().totalUsers()).isEqualTo(237);
            assertThat(response.summary().studentsWithActivity()).isEqualTo(237);
            assertThat(response.summary().totalSessionsStarted()).isEqualTo(237);
            assertThat(response.summary().topModuleCount()).isEqualTo(4740);
            assertThat(response.summary().averageProgress()).isNull();
            assertThat(response.records()).hasSize(page==9?12:25);
            response.records().forEach(r->{assertThat(ids.add(r.userId())).isTrue();assertThat(r.totalUsageMinutes()).isEqualTo(19);assertThat(r.visitedModules()).containsExactly("PERIODIC_TABLE");});
        }
        assertThat(ids).hasSize(237);assertThat(stats.getEntityLoadCount()).isZero();
        var admin=f.app.getBean(AdminQueryRepository.class);
        var first=admin.users(0,100,"t07bpage","ESTUDIANTE",null);
        assertThat(first.totalElements()).isEqualTo(237);assertThat(first.content()).hasSize(100);
        assertThat(first.content().get(0).fullName()).isEqualTo("Equal Name");
        assertThat(admin.users(1,100,"t07bpage","ESTUDIANTE",null).content()).extracting(AdminUserResponse::userId).doesNotContainAnyElementsOf(first.content().stream().map(AdminUserResponse::userId).toList());
        assertThatThrownBy(()->admin.users(0,101,null,"all",null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->admin.users(-1,25,null,"all",null)).isInstanceOf(IllegalArgumentException.class);
        assertThat(admin.users(9999,25,"t07bpage","all",null).content()).isEmpty();
        assertThat(stats.getEntityLoadCount()).isZero();
        System.out.println("T07B SQL trial: 237 tied users, 4740 events, 10 pages, no duplicates; summaries independent of pages; entity loads=0");
    }

    @Test void academicAggregatesPreserveDatesStatusesNullsAndDetailsAreBounded() {
        var tr=f.app.getBean(TeacherProfileRepository.class).findByUser(f.teacher).orElseThrow();
        var student=f.app.getBean(StudentProfileRepository.class).findByUser_Id(f.student.getId()).orElseThrow();
        var e=f.app.getBean(EvaluationRepository.class).saveAndFlush(Evaluation.builder().title("Synthetic evaluation").createdByTeacher(tr).status(EvaluationStatus.PUBLISHED).build());
        f.app.getBean(EvaluationAssignmentRepository.class).saveAndFlush(EvaluationAssignment.builder().evaluation(e).teacher(tr).grade("3").section("A").active(true).build());
        var attempt=f.app.getBean(EvaluationAttemptRepository.class).saveAndFlush(EvaluationAttempt.builder().evaluation(e).student(student).status(AttemptStatus.PENDING_MANUAL_REVIEW).startedAt(LocalDateTime.parse("2026-07-01T10:00:00")).submittedAt(LocalDateTime.parse("2026-07-01T10:30:00")).overallFeedback("Synthetic feedback").build());
        var question=f.app.getBean(EvaluationQuestionRepository.class).saveAndFlush(EvaluationQuestion.builder().evaluation(e).questionText("Synthetic question").build());
        f.sql().update("insert into evaluation_answers(attempt_id,question_id,correct,teacher_feedback,reviewed,answered_at) values(?,?,true,'Synthetic',true,'2026-07-01 23:59:59.999999'),(?,?,false,null,true,'2026-07-02 00:00:00')",attempt.getId(),question.getId(),attempt.getId(),question.getId());
        event(f.student.getId(),"2026-07-01 10:10:00","COMPOUNDS");event(f.student.getId(),"2026-07-02 00:00:00","PERIODIC_TABLE");
        var service=f.app.getBean(StudentUsageRecordService.class);
        var r=service.getRecords(null,f.student.getUsername(),"3","a","2026-07-01","2026-07-01","COMPOUNDS",null).records().stream().filter(x->x.userId().equals(f.student.getId())).findFirst().orElseThrow();
        assertThat(r.assignedActivities()).isEqualTo(1);assertThat(r.completedActivities()).isEqualTo(1);assertThat(r.progressPercentage()).isEqualTo(100);
        assertThat(r.attemptsCount()).isEqualTo(1);assertThat(r.correctAnswers()).isEqualTo(1);assertThat(r.incorrectAnswers()).isZero();assertThat(r.accuracyRate()).isEqualTo(100);assertThat(r.feedbackReceived()).isEqualTo(2);assertThat(r.totalUsageMinutes()).isEqualTo(30);
        assertThat(service.getRecords("DOCENTE",f.teacher.getUsername(),null,null,null,null,null,null).records()).allSatisfy(x->{assertThat(x.assignedActivities()).isNull();assertThat(x.accuracyRate()).isNull();});
        for(int i=0;i<23;i++){var evaluation=f.app.getBean(EvaluationRepository.class).saveAndFlush(Evaluation.builder().title("Synthetic assigned "+i).createdByTeacher(tr).status(EvaluationStatus.PUBLISHED).build());f.app.getBean(EvaluationAssignmentRepository.class).saveAndFlush(EvaluationAssignment.builder().evaluation(evaluation).teacher(tr).grade("3").section("A").build());}
        var detail=service.getDetail(f.student.getId(),0,20);assertThat(detail.evaluations()).hasSize(20);assertThat(detail.evaluationsPage().totalElements()).isEqualTo(24);assertThat(service.getDetail(f.student.getId(),1,20).evaluations()).hasSize(4);
        assertThatThrownBy(()->service.getDetail(Long.MAX_VALUE)).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    }

    @Test void materialQuotaExactExcessReducedReplacementDeleteAndRollbackUseActualBytes() {
        long concept=concept();cap("MATERIAL_BYTES",10);long id=material(concept,10);
        assertThat(used("ACCOUNT",f.teacher.getId(),"MATERIAL_BYTES")).isEqualTo(10);
        assertThat(f.sql().queryForObject("select file_size from concept_materials where id=?",Long.class,id)).isEqualTo(10);
        assertThatThrownBy(()->material(concept,1)).satisfies(ex->assertThat(StorageErrors.quota(ex)).isTrue());
        // Lowered quota preserves historical records and accepts only non-growing replacement.
        cap("MATERIAL_BYTES",4);f.sql().update("update concept_materials set file_data=? where id=?",new byte[6],id);
        assertThat(used("ACCOUNT",f.teacher.getId(),"MATERIAL_BYTES")).isEqualTo(6);
        assertThatThrownBy(()->f.sql().update("update concept_materials set file_data=? where id=?",new byte[7],id)).satisfies(ex->assertThat(StorageErrors.quota(ex)).isTrue());
        tx().executeWithoutResult(status->{f.sql().update("update concept_materials set file_data=? where id=?",new byte[3],id);status.setRollbackOnly();});
        assertThat(used("ACCOUNT",f.teacher.getId(),"MATERIAL_BYTES")).isEqualTo(6);
        f.sql().update("delete from concept_materials where id=?",id);assertThat(used("ACCOUNT",f.teacher.getId(),"MATERIAL_BYTES")).isZero();
        assertThat(used("ACCOUNT",f.teacher.getId(),"MATERIAL_COUNT")).isZero();
    }

    @Test void concurrentConnectionsShareExactQuotaAndFailedWriteRollsBackReservation() throws Exception {
        long concept=concept();cap("MATERIAL_BYTES",10);var gate=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(8);
        try {
            var futures=new ArrayList<Future<Boolean>>();
            for(int i=0;i<8;i++)futures.add(pool.submit(()->{gate.await();try{material(concept,5);return true;}catch(RuntimeException ex){assertThat(StorageErrors.quota(ex)).isTrue();return false;}}));
            gate.countDown();int accepted=0;for(var future:futures)if(future.get(15,TimeUnit.SECONDS))accepted++;
            assertThat(accepted).isEqualTo(2);assertThat(used("ACCOUNT",f.teacher.getId(),"MATERIAL_BYTES")).isEqualTo(10);
            System.out.println("T07B concurrent PostgreSQL trial: 8 connections, 2 accepted, 6 quota rejections, exact 10 stored bytes");
        } finally {pool.shutdownNow();}
    }

    @Test void snapshotAndCanvasBudgetsAreIndependentAndListsLoadNoEntitiesOrBytes() {
        cap("SNAPSHOT_BYTES",7);cap("BOARD_BYTES",7);
        f.sql().update("update whiteboard_sessions set final_snapshot_data=?,current_state_json=? where id=?",new byte[7],"ñ12345",f.board);
        assertThat(used("ACCOUNT",f.teacher.getId(),"BOARD_BYTES")).isEqualTo(7);
        assertThatThrownBy(()->f.sql().update("update whiteboard_sessions set final_snapshot_data=? where id=?",new byte[8],f.board)).satisfies(ex->assertThat(StorageErrors.quota(ex)).isTrue());
        assertThatThrownBy(()->f.sql().update("update whiteboard_sessions set current_state_json=? where id=?","ñ123456",f.board)).satisfies(ex->assertThat(StorageErrors.quota(ex)).isTrue());
        long teacherId=teacher();
        var stats=f.app.getBean(jakarta.persistence.EntityManagerFactory.class).unwrap(SessionFactory.class).getStatistics();stats.clear();
        var rows=f.app.getBean(WhiteboardQueryRepository.class).teacher(teacherId,0,25);
        assertThat(f.app.getBean(WhiteboardQueryRepository.class).admin(0,25).content()).isNotEmpty();
        assertThat(rows.content()).hasSize(1);assertThat(rows.content().get(0).snapshotAvailable()).isTrue();assertThat(stats.getEntityLoadCount()).isZero();
        f.sql().update("update whiteboard_sessions set final_snapshot_data=?,current_state_json=? where id=?",new byte[3],"123",f.board);
        assertThat(used("ACCOUNT",f.teacher.getId(),"SNAPSHOT_BYTES")).isEqualTo(3);assertThat(used("ACCOUNT",f.teacher.getId(),"BOARD_BYTES")).isEqualTo(3);
    }

    @Test void ownershipLimitPreservesTombstonesRestorationAndPrivateQuotaError() throws Exception {
        cap("OWNERSHIP_COUNT",2);
        var a=f.connect(f.student);var b=f.connect(f.peerStudent);var teacher=f.connect(f.teacher);
        for(var p:List.of(a,b,teacher)){p.subscribe("errors","/user/queue/whiteboard-errors");p.subscribe("board","/topic/whiteboards/"+f.board);}
        a.send(f.drawDestination(),f.text("mine","create-a"));assertThat(a.await(x->x.contains("create-a"),5000)).isNotNull();
        teacher.send(f.drawDestination(),f.text("other","create-b"));assertThat(teacher.await(x->x.contains("create-b"),5000)).isNotNull();
        a.send(f.drawDestination(),f.deletion("TEXT","mine","delete-a"));assertThat(a.await(x->x.contains("delete-a"),5000)).isNotNull();
        String before=f.state();a.frame("SEND\ndestination:"+f.drawDestination()+"\ncontent-type:application/json\nclient-event-id:over-limit\n\n"+f.text("new","over-limit")+"\0");assertThat(a.await(x->x.contains("STORAGE_QUOTA_EXCEEDED"),5000)).contains("over-limit");assertThat(f.state()).isEqualTo(before);
        b.send(f.drawDestination(),f.text("mine","foreign-restore"));assertThat(b.await(x->x.contains("DRAW_REJECTED"),5000)).isNotNull();
        cap("OWNERSHIP_COUNT",1); // Historical excess must not revoke restoration of existing identities.
        a.send(f.drawDestination(),f.text("mine","own-restore"));assertThat(a.await(x->x.contains("own-restore"),5000)).isNotNull();assertThat(used("BOARD",f.board,"OWNERSHIP_COUNT")).isEqualTo(2);
        assertThat(f.sql().queryForObject("select owner_user_id from whiteboard_object_ownership where board_id=? and object_id='mine'",Long.class,f.board)).isEqualTo(f.student.getId());
    }

    @Test void telemetryQuotaDoesNotBlockAcademicLifecycleAndHttpSessionSurvives() throws Exception {
        long user=f.student.getId();cap("USAGE_COUNT",used("ACCOUNT",user,"USAGE_COUNT")+1);
        String token=f.login(f.student);String body="{\"module\":\"COMPOUNDS\",\"eventType\":\"MODULE_ACCESS\"}";
        assertThat(f.request("POST","/api/usage-metrics/events",token,body).statusCode()).isEqualTo(201);
        var rejected=f.request("POST","/api/usage-metrics/events",token,body);assertThat(rejected.statusCode()).isEqualTo(409);assertThat(rejected.body()).contains("STORAGE_QUOTA_EXCEEDED");assertThat(f.request("GET","/api/auth/me",token,null).statusCode()).isEqualTo(200);
        var tr=f.app.getBean(TeacherProfileRepository.class).findByUser(f.teacher).orElseThrow();var e=f.app.getBean(EvaluationRepository.class).saveAndFlush(Evaluation.builder().title("Synthetic").createdByTeacher(tr).build());
        var a=f.app.getBean(EvaluationAttemptRepository.class).saveAndFlush(EvaluationAttempt.builder().evaluation(e).student(f.app.getBean(StudentProfileRepository.class).findByUser_Id(user).orElseThrow()).build());
        cap("ATTEMPT_EVENT_COUNT",1);f.sql().update("insert into evaluation_attempt_events(attempt_id,event_type) values(?,'TAB_HIDDEN')",a.getId());
        assertThatThrownBy(()->f.sql().update("insert into evaluation_attempt_events(attempt_id,event_type) values(?,'TAB_VISIBLE')",a.getId())).satisfies(ex->assertThat(StorageErrors.quota(ex)).isTrue());
        f.sql().update("insert into evaluation_attempt_events(attempt_id,event_type) values(?,'ATTEMPT_SUBMITTED')",a.getId());
        assertThat(used("ATTEMPT",a.getId(),"ATTEMPT_EVENT_COUNT")).isEqualTo(1);
    }

    @Test void retentionSimulatesDeletesInRepeatableBatchesAndCoordinatesInstances() throws Exception {
        var retention=f.app.getBean(TechnicalRetentionService.class);LocalDateTime cutoff=LocalDateTime.parse("2025-01-01T00:00:00");
        for(int i=0;i<5;i++)event(f.student.getId(),"2024-01-01 00:00:00","COMPOUNDS");
        long before=used("ACCOUNT",f.student.getId(),"USAGE_COUNT");long audit=f.sql().queryForObject("select count(*) from system_logs",Long.class);
        var dry=retention.batch(cutoff,true);assertThat(dry.candidates()).isEqualTo(2);assertThat(dry.deleted()).isZero();assertThat(retention.batch(cutoff,true).ids()).isEqualTo(dry.ids());assertThat(used("ACCOUNT",f.student.getId(),"USAGE_COUNT")).isEqualTo(before);
        try(var second=f.startApp()) {
            var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var pool=Executors.newSingleThreadExecutor();
            try {
                var lock=pool.submit(()->tx().execute(s->{f.sql().queryForObject("select id from technical_retention_state where id=1 for update",Integer.class);entered.countDown();try{release.await(10,TimeUnit.SECONDS);}catch(InterruptedException ex){throw new RuntimeException(ex);}return true;}));
                assertThat(entered.await(10,TimeUnit.SECONDS)).isTrue();assertThat(second.getBean(TechnicalRetentionService.class).batch(cutoff,false).coordinated()).isFalse();release.countDown();lock.get(10,TimeUnit.SECONDS);
            } finally {release.countDown();pool.shutdownNow();}
        }
        int deleted=0;for(int i=0;i<4;i++){var result=retention.batch(cutoff,false);assertThat(result.deleted()).isLessThanOrEqualTo(2);deleted+=result.deleted();}
        assertThat(deleted).isEqualTo(5);assertThat(retention.batch(cutoff,false).deleted()).isZero();assertThat(used("ACCOUNT",f.student.getId(),"USAGE_COUNT")).isEqualTo(before-5);assertThat(f.sql().queryForObject("select count(*) from system_logs",Long.class)).isEqualTo(audit);
        assertThatThrownBy(()->retention.batch(LocalDateTime.now(),false)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void rosterAndSupervisionUseSqlPagesAndWholeDatasetCounts() {
        long teacher=teacher();var tr=f.app.getBean(TeacherProfileRepository.class).findByUser(f.teacher).orElseThrow();
        String prefix="scope"+f.teacher.getId();
        f.sql().update("insert into user_accounts(username,password,role,active,temporary_password,credentials_version,created_at) select ?||g,'synthetic','ESTUDIANTE',g%2=0,false,0,'2026-07-01' from generate_series(1,55) g",prefix);
        f.sql().update("insert into student_profiles(user_id,teacher_id,student_code,names,last_names,grade,section) select id,?,username,'Tie','Name','5','C' from user_accounts where username like ?",teacher,prefix+"%");
        var roster=f.app.getBean(RosterQueryRepository.class);var summary=roster.summary(teacher);
        assertThat(summary.total()).isEqualTo(57);assertThat(summary.groups()).contains(new RosterSummary.Group("5","C"));
        var p0=roster.students(teacher,0,25,prefix,null,null,"all");var p1=roster.students(teacher,1,25,prefix,null,null,"all");
        assertThat(p0.totalElements()).isEqualTo(55);assertThat(p1.content()).extracting(StudentResponse::id).doesNotContainAnyElementsOf(p0.content().stream().map(StudentResponse::id).toList());
        assertThat(roster.students(teacher,0,25,prefix,"5","C","active").totalElements()).isEqualTo(27);
        var q=f.app.getBean(SupervisionQueryRepository.class);var before=q.summary();
        for(int i=0;i<31;i++) {
            var c=f.app.getBean(ConceptContentRepository.class).saveAndFlush(ConceptContent.builder().title(prefix+" "+i).category("Synthetic").createdByTeacher(tr).status(ConceptStatus.PUBLISHED).build());
            f.sql().update("insert into concept_assignments(concept_content_id,teacher_id,grade,section,active,assigned_at) values(?,?,'5','C',true,'2026-07-01')",c.getId(),teacher);
            material(c.getId(),16);
        }
        var stats=f.app.getBean(jakarta.persistence.EntityManagerFactory.class).unwrap(SessionFactory.class).getStatistics();stats.clear();
        var c0=q.concepts(0,25,prefix,"PUBLISHED","Synthetic","5 C");var c1=q.concepts(1,25,prefix,"PUBLISHED","Synthetic","5 C");
        assertThat(c0.totalElements()).isEqualTo(31);assertThat(c1.content()).hasSize(6);
        assertThat(c1.content()).extracting(SupervisionConceptResponse::id).doesNotContainAnyElementsOf(c0.content().stream().map(SupervisionConceptResponse::id).toList());
        assertThat(c0.content()).allSatisfy(c->{assertThat(c.materialCount()).isEqualTo(1);assertThat(c.hasAttachment()).isTrue();assertThat(c.sections()).hasSize(1);});
        assertThat(q.summary().totalConcepts()).isEqualTo(before.totalConcepts()+31);
        assertThat(q.assignments(0,25,prefix,"CONTENIDO","5","C","true").totalElements()).isEqualTo(31);
        assertThat(q.options().groups()).contains(new RosterSummary.Group("5","C"));
        assertThat(stats.getEntityLoadCount()).isZero();
    }

    @Test void countQuotasAndParentOwnershipCannotBypassLedger() {
        long c=concept();cap("MATERIAL_COUNT",1);long m=material(c,0);
        assertThatThrownBy(()->material(c,0)).satisfies(ex->assertThat(StorageErrors.quota(ex)).isTrue());
        assertThatThrownBy(()->f.sql().update("update concept_contents set created_by_teacher_id=? where id=?",f.app.getBean(TeacherProfileRepository.class).findByUser(f.otherTeacher).orElseThrow().getId(),c)).isInstanceOf(org.springframework.dao.DataAccessException.class);
        f.sql().update("delete from concept_materials where id=?",m);assertThat(used("ACCOUNT",f.teacher.getId(),"MATERIAL_COUNT")).isZero();
        cap("BOARD_COUNT",1);
        assertThatThrownBy(()->f.app.getBean(WhiteboardSessionRepository.class).saveAndFlush(WhiteboardSession.builder().teacher(f.app.getBean(TeacherProfileRepository.class).findByUser(f.teacher).orElseThrow()).name("Synthetic").grade("3").section("A").build())).satisfies(ex->assertThat(StorageErrors.quota(ex)).isTrue());
    }

    @Test void retentionDisabledIsReadOnlyAndIndexesExist() throws Exception {
        try(var second=f.startApp("--app.retention.delete-enabled=false")) {
            var r=second.getBean(TechnicalRetentionService.class);var cutoff=LocalDateTime.parse("2025-01-01T00:00:00");
            assertThatThrownBy(()->r.batch(cutoff,false)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("desactivada");
            assertThat(r.batch(cutoff,true).deleted()).isZero();
        }
        assertThat(f.sql().queryForObject("select count(*) from pg_indexes where schemaname=current_schema() and indexname in ('idx_usage_retention','idx_assignment_group_active','idx_board_teacher_time')",Integer.class)).isEqualTo(3);
    }

    @Test void historicalInstallationSeedsActualConsumptionWithoutDeletingExcess() throws Exception {
        long concept=concept();String schemaSql=new org.springframework.core.io.ClassPathResource("security/t07b-storage.sql").getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        tx().executeWithoutResult(status->{
            // All DDL and synthetic historical inserts roll back inside this disposable schema.
            for(String table:List.of("concept_materials","whiteboard_sessions","whiteboard_object_ownership","usage_events","evaluation_attempt_events"))f.sql().execute("drop trigger storage_quota on "+table);
            for(String table:List.of("teacher_profiles","student_profiles","concept_contents","evaluation_attempts"))f.sql().execute("drop trigger storage_owner_guard on "+table);
            f.sql().execute("drop function storage_row_quota();drop function storage_owner_immutable();drop function storage_delta(varchar,bigint,varchar,bigint);drop table storage_usage,storage_limits,technical_retention_state,storage_schema_version");
            var indexes=java.util.regex.Pattern.compile("CREATE INDEX ([a-z_]+)").matcher(schemaSql);while(indexes.find())f.sql().execute("drop index "+indexes.group(1));
            f.sql().update("insert into concept_materials(concept_content_id,type,active,file_data,file_size) select ?,'FILE',false,decode('01','hex'),999999 from generate_series(1,1001)",concept);
            f.sql().execute(schemaSql);
            assertThat(used("ACCOUNT",f.teacher.getId(),"MATERIAL_COUNT")).isEqualTo(1001);
            assertThat(used("ACCOUNT",f.teacher.getId(),"MATERIAL_BYTES")).isEqualTo(1001);
            assertThat(f.sql().queryForObject("select max(file_size) from concept_materials where concept_content_id=?",Long.class,concept)).isEqualTo(1);
            // Do not deliberately abort this transaction with a quota error: preserve rollback evidence.
            f.sql().update("update concept_materials set file_data=decode('','hex') where id=(select min(id) from concept_materials where concept_content_id=?)",concept);
            assertThat(used("ACCOUNT",f.teacher.getId(),"MATERIAL_BYTES")).isEqualTo(1000);
            status.setRollbackOnly();
        });
        assertThat(f.sql().queryForObject("select count(*) from concept_materials where concept_content_id=?",Long.class,concept)).isZero();
        assertThat(f.sql().queryForObject("select count(*) from pg_trigger where tgname='storage_quota' and tgenabled='O' and tgrelid='concept_materials'::regclass",Integer.class)).isEqualTo(1);
    }

    @Test void attemptTimelineIsPagedWhileStatisticsCoverEveryEvent() {
        var tr=f.app.getBean(TeacherProfileRepository.class).findByUser(f.teacher).orElseThrow();
        var e=f.app.getBean(EvaluationRepository.class).saveAndFlush(Evaluation.builder().title("Synthetic trace").createdByTeacher(tr).build());
        var a=f.app.getBean(EvaluationAttemptRepository.class).saveAndFlush(EvaluationAttempt.builder().evaluation(e).student(f.app.getBean(StudentProfileRepository.class).findByUser_Id(f.student.getId()).orElseThrow()).build());
        f.sql().update("insert into evaluation_attempt_events(attempt_id,event_type,occurred_at) select ?,'TAB_HIDDEN','2026-07-01' from generate_series(1,55)",a.getId());
        f.sql().update("insert into evaluation_attempt_events(attempt_id,event_type,occurred_at,metadata) values(?,'TOOL_OPENED','2026-07-01','source=synthetic; tool=PERIODIC_TABLE')",a.getId());
        var service=f.app.getBean(EvaluationService.class);var p0=service.getAttemptTraceability(f.teacher.getUsername(),a.getId(),0,25);var p1=service.getAttemptTraceability(f.teacher.getUsername(),a.getId(),1,25);
        assertThat(p0.totalEvents()).isEqualTo(56);assertThat(p1.tabExitCount()).isEqualTo(55);assertThat(p0.toolsUsed()).containsExactly("PERIODIC_TABLE");
        assertThat(p1.events()).extracting(AttemptEventResponse::id).doesNotContainAnyElementsOf(p0.events().stream().map(AttemptEventResponse::id).toList());
        assertThat(service.getAttemptTraceability(f.teacher.getUsername(),a.getId(),2,25).events()).hasSize(6);
        assertThatThrownBy(()->service.getAttemptTraceability(f.otherTeacher.getUsername(),a.getId(),0,25)).hasMessageContaining("permiso");
    }

    @Test void materialMetadataCanReadAllHistoricalPagesWithoutLoadingFiles() throws Exception {
        long c=concept();
        f.sql().update("insert into concept_materials(concept_content_id,type,title,url,active) select ?,'LINK','Synthetic '||g,'https://example.com/'||g,true from generate_series(1,55) g",c);
        var stats=f.app.getBean(jakarta.persistence.EntityManagerFactory.class).unwrap(SessionFactory.class).getStatistics();stats.clear();
        var repo=f.app.getBean(ConceptMaterialRepository.class);
        var first=repo.findMetadataByConcept(c);var second=repo.findMetadataByConcept(c,org.springframework.data.domain.PageRequest.of(1,25));
        assertThat(first).hasSize(25);assertThat(second).hasSize(25);
        assertThat(second).extracting(ConceptMaterialView::id).doesNotContainAnyElementsOf(first.stream().map(ConceptMaterialView::id).toList());
        assertThat(stats.getEntityLoadCount()).isZero();
        var response=f.request("GET","/api/concepts/"+c+"/materials?page=2",f.login(f.teacher),null);
        assertThat(response.statusCode()).isEqualTo(200);var body=f.json.readTree(response.body());
        assertThat(body.path("totalElements").asInt()).isEqualTo(55);assertThat(body.path("content").size()).isEqualTo(5);
        assertThat(f.request("GET","/api/concepts/"+c+"/materials",f.login(f.otherTeacher),null).statusCode()).isEqualTo(400);
    }

    @Test void disabledQuotaTriggerPreventsApplicationStartup() {
        f.sql().execute("alter table usage_events disable trigger storage_quota");
        try {
            assertThatThrownBy(()->f.startApp()).hasRootCauseInstanceOf(IllegalStateException.class)
                .hasStackTraceContaining("quota triggers are missing or disabled");
        }finally{f.sql().execute("alter table usage_events enable trigger storage_quota");}
    }
}
