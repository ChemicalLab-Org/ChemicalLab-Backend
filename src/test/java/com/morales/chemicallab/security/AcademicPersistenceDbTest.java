package com.morales.chemicallab.security;

import com.morales.chemicallab.entity.institution.*;
import com.morales.chemicallab.service.*;
import org.junit.jupiter.api.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

/** Application transactions/T08 and real disposable PostgreSQL, never a school database. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AcademicPersistenceDbTest {
    final WhiteboardSecurityTransportDbTest f=new WhiteboardSecurityTransportDbTest();
    final Instant t=Instant.parse("2026-02-01T00:00:00Z"), end=Instant.parse("2026-07-15T00:00:00Z");
    AcademicMaintenanceService service;
    InstitutionMaintenanceService institutions;
    String expansion,b1;
    long a,b,p,q,c,d,student,teacher,replacement;
    int sequence;
    JdbcTemplate sql(){return f.sql();}
    TransactionTemplate tx(){return new TransactionTemplate(f.app.getBean(PlatformTransactionManager.class));}
    Instant at(int seconds){return t.plusSeconds(seconds);}
    @BeforeAll void start() throws Exception {
        f.start();service=f.app.getBean(AcademicMaintenanceService.class);institutions=f.app.getBean(InstitutionMaintenanceService.class);
        b1=new ClassPathResource("db/expansion/t11b1-v1.sql").getContentAsString(StandardCharsets.UTF_8);
        expansion=new ClassPathResource("db/expansion/t11b2-v1.sql").getContentAsString(StandardCharsets.UTF_8);
        sql().execute(b1);sql().execute(expansion);
    }
    @BeforeEach void fixture() {
        f.fixture();sequence++;
        a=institutions.createInstitution("B2-A-"+sequence,"Fictitious A","America/Lima").id();
        b=institutions.createInstitution("B2-B-"+sequence,"Fictitious B","America/Lima").id();
        institutions.changeInstitutionStatus(a,Institution.Status.ACTIVE);institutions.changeInstitutionStatus(b,Institution.Status.ACTIVE);
        p=period(a);q=period(b);c=academicClass(a,p,"QUIM-3A");d=academicClass(a,p,"FIS-3A");
        student=membership(a,f.student.getId(),MembershipRoleGrant.InstitutionalRole.ESTUDIANTE);
        teacher=membership(a,f.teacher.getId(),MembershipRoleGrant.InstitutionalRole.DOCENTE);
        replacement=membership(a,f.otherTeacher.getId(),MembershipRoleGrant.InstitutionalRole.DOCENTE);
    }
    @AfterEach void close(){f.closePeers();}
    @AfterAll void stop() throws Exception {f.cleanup();}
    long period(long institution) {
        var x=service.createPeriod(institution,"SEM-1","Semester",t,end,t);
        service.transitionPeriod(x.id(),AcademicPeriod.Status.OPEN,at(1));return x.id();
    }
    long academicClass(long institution,long period,String code) {
        var x=service.createClass(institution,period,code,"Chemistry 3A","Chemistry","3","A",t);
        service.transitionClass(x.id(),AcademicClass.Status.ACTIVE,at(2));return x.id();
    }
    long membership(long institution,long account,MembershipRoleGrant.InstitutionalRole role) {
        var m=institutions.createMembership(institution,account,t);
        institutions.transitionMembership(m.id(),InstitutionMembership.Status.ACTIVE,at(1));
        if(role!=null)institutions.grantRole(m.id(),role,at(2),null);
        return m.id();
    }
    Enrollment pending(long classId,long member) {return service.createEnrollment(a,classId,member,at(10),end,at(3));}
    Enrollment enrolled(long classId,long member) {
        return service.transitionEnrollment(pending(classId,member).id(),Enrollment.Status.ACTIVE,at(10));
    }
    TeachingAssignment responsible(long member) {
        var x=service.planAssignment(a,c,member,at(10),end,at(3));
        return service.transitionAssignment(x.id(),TeachingAssignment.Status.ACTIVE,at(10));
    }
    @Test void localCodesHaveInstitutionAndPeriodScopes() {
        long foreign=academicClass(b,q,"QUIM-3A");
        assertThat(foreign).isNotEqualTo(c);
        assertThatThrownBy(()->period(a)).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(()->academicClass(a,p,"QUIM-3A")).isInstanceOf(DataAccessException.class);
        var later=service.createPeriod(a,"SEM-2","Next semester",end,end.plusSeconds(100000),t);
        service.transitionPeriod(later.id(),AcademicPeriod.Status.OPEN,end);
        var next=service.createClass(a,later.id(),"QUIM-3A","Same group later","Chemistry","3","A",t);
        assertThat(next.id()).isNotEqualTo(c);
    }
    @Test void directSqlRejectsCrossInstitutionReferences() {
        assertThatThrownBy(()->sql().update("""
            insert into academic_classes(institution_id,period_id,local_code,name,course,grade,section,state_since,change_reason)
            values(?,?,'X','X','X','3','A',?,'CREATED')
            """,b,p,Timestamp.from(t))).isInstanceOf(DataAccessException.class);
        long other=membership(b,f.student.getId(),MembershipRoleGrant.InstitutionalRole.ESTUDIANTE);
        assertThatThrownBy(()->pending(c,other)).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(()->sql().update("""
            insert into enrollments(institution_id,class_id,membership_id,episode_number,valid_from,valid_until,state_since,change_reason)
            values(?,?,?,1,?,?,?,'CREATED')
            """,b,c,other,Timestamp.from(at(10)),Timestamp.from(end),Timestamp.from(t))).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(()->service.planAssignment(b,c,teacher,at(10),end,t)).isInstanceOf(DataAccessException.class);
    }
    @Test void oneAccountInTwoClassesAndInstitutionsHasIndependentEnrollments() {
        var first=enrolled(c,student);var second=enrolled(d,student);
        long other=membership(b,f.student.getId(),MembershipRoleGrant.InstitutionalRole.ESTUDIANTE),foreign=academicClass(b,q,"QUIM-3A");
        var third=service.createEnrollment(b,foreign,other,at(10),end,at(3));
        service.transitionEnrollment(third.id(),Enrollment.Status.ACTIVE,at(10));
        service.transitionEnrollment(first.id(),Enrollment.Status.WITHDRAWN,at(20));
        assertThat(sql().queryForList("select status from enrollments where id in (?,?) order by id",String.class,second.id(),third.id()))
            .containsExactly("ACTIVE","ACTIVE");
        assertThat(sql().queryForObject("select count(*) from institution_memberships where account_id=?",Long.class,f.student.getId())).isEqualTo(2);
    }
    @Test void legacyTeacherRoleDoesNotReplaceInstitutionalGrant() {
        long absent=membership(a,f.peerStudent.getId(),null);
        var e=pending(c,absent);
        assertThatThrownBy(()->service.transitionEnrollment(e.id(),Enrollment.Status.ACTIVE,at(10))).isInstanceOf(DataAccessException.class);
        long globalTeacher=membership(b,f.teacher.getId(),null),foreign=academicClass(b,q,"QUIM-3A");
        var x=service.planAssignment(b,foreign,globalTeacher,at(10),end,at(3));
        assertThatThrownBy(()->service.transitionAssignment(x.id(),TeachingAssignment.Status.ACTIVE,at(10))).isInstanceOf(DataAccessException.class);
    }
    @Test void pendingSuspendedEndedAndMissingMembershipCannotActivate() {
        long pending=institutions.createMembership(a,f.peerStudent.getId(),t).id();
        var x=pending(c,pending);
        assertThatThrownBy(()->service.transitionEnrollment(x.id(),Enrollment.Status.ACTIVE,at(10))).isInstanceOf(DataAccessException.class);
        var e=pending(c,student);
        institutions.transitionMembership(student,InstitutionMembership.Status.SUSPENDED,at(5));
        assertThatThrownBy(()->service.transitionEnrollment(e.id(),Enrollment.Status.ACTIVE,at(10))).isInstanceOf(DataAccessException.class);
        institutions.transitionMembership(student,InstitutionMembership.Status.ENDED,at(6));
        assertThatThrownBy(()->service.transitionEnrollment(e.id(),Enrollment.Status.ACTIVE,at(10))).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(()->pending(c,Long.MAX_VALUE)).isInstanceOf(DataAccessException.class);
    }
    @Test void expiredFutureRevokedAndWrongRoleGrantsCannotActivate() {
        long m=membership(a,f.peerStudent.getId(),null);
        var e=pending(c,m);
        var g=institutions.grantRole(m,MembershipRoleGrant.InstitutionalRole.ESTUDIANTE,at(2),at(10));
        assertThatThrownBy(()->service.transitionEnrollment(e.id(),Enrollment.Status.ACTIVE,at(10))).isInstanceOf(DataAccessException.class);
        institutions.grantRole(m,MembershipRoleGrant.InstitutionalRole.ESTUDIANTE,at(20),null);
        assertThatThrownBy(()->service.transitionEnrollment(e.id(),Enrollment.Status.ACTIVE,at(19))).isInstanceOf(DataAccessException.class);
        institutions.revokeRole(g.id(),at(9));
        institutions.grantRole(m,MembershipRoleGrant.InstitutionalRole.DOCENTE,at(2),null);
        assertThatThrownBy(()->service.transitionEnrollment(e.id(),Enrollment.Status.ACTIVE,at(10))).isInstanceOf(DataAccessException.class);
    }
    @Test void membershipStateTimeAndReadmissionDoNotRestoreOldGrants() {
        var e=pending(c,student);
        institutions.transitionMembership(student,InstitutionMembership.Status.SUSPENDED,at(20));
        institutions.transitionMembership(student,InstitutionMembership.Status.ACTIVE,at(30));
        assertThatThrownBy(()->service.transitionEnrollment(e.id(),Enrollment.Status.ACTIVE,at(10))).isInstanceOf(DataAccessException.class);
        institutions.transitionMembership(student,InstitutionMembership.Status.ENDED,at(40));
        institutions.transitionMembership(student,InstitutionMembership.Status.ACTIVE,at(50));
        assertThatThrownBy(()->service.transitionEnrollment(e.id(),Enrollment.Status.ACTIVE,at(60))).isInstanceOf(DataAccessException.class);
    }
    @Test void periodsAndRelationsRejectInvalidInfiniteAndOutOfPeriodDates() {
        for(String from:List.of("infinity","2026-07-15T00:00:00Z")) {
            assertThatThrownBy(()->sql().update("""
                insert into academic_periods(institution_id,local_code,name,valid_from,valid_until,state_since,change_reason)
                values(?,'BAD','Bad',?::timestamptz,?,?, 'CREATED')
                """,a,from,Timestamp.from(end),Timestamp.from(t))).isInstanceOf(DataAccessException.class);
        }
        assertThatThrownBy(()->service.createEnrollment(a,c,student,end,at(10),t)).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(()->service.planAssignment(a,c,teacher,t.minusSeconds(1),end,t)).isInstanceOf(DataAccessException.class);
        var e=pending(c,student);
        for(Instant at:List.of(at(9),end))assertThatThrownBy(()->service.transitionEnrollment(e.id(),Enrollment.Status.ACTIVE,at)).isInstanceOf(DataAccessException.class);
    }
    @Test void inactiveInstitutionPeriodOrClassPreventsActivation() {
        var e=pending(c,student);
        institutions.changeInstitutionStatus(a,Institution.Status.SUSPENDED);
        assertThatThrownBy(()->service.transitionEnrollment(e.id(),Enrollment.Status.ACTIVE,at(10))).isInstanceOf(DataAccessException.class);
        institutions.changeInstitutionStatus(a,Institution.Status.ACTIVE);
        service.transitionClass(c,AcademicClass.Status.CLOSED,at(5));
        assertThatThrownBy(()->service.transitionEnrollment(e.id(),Enrollment.Status.ACTIVE,at(10))).isInstanceOf(DataAccessException.class);
        var x=pending(d,student);
        service.transitionPeriod(p,AcademicPeriod.Status.CLOSED,at(6));
        assertThatThrownBy(()->service.transitionEnrollment(x.id(),Enrollment.Status.ACTIVE,at(10))).isInstanceOf(DataAccessException.class);
    }
    @Test void academicTransitionsAreTerminalAndConserveIdentityAndHistory() {
        service.transitionClass(c,AcademicClass.Status.CLOSED,at(20));
        service.transitionClass(c,AcademicClass.Status.ARCHIVED,at(30));
        service.transitionPeriod(p,AcademicPeriod.Status.CLOSED,at(40));
        service.transitionPeriod(p,AcademicPeriod.Status.ARCHIVED,at(50));
        assertThatThrownBy(()->service.transitionClass(c,AcademicClass.Status.ACTIVE,at(60))).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(()->service.transitionPeriod(p,AcademicPeriod.Status.OPEN,at(60))).isInstanceOf(DataAccessException.class);
        assertThat(sql().queryForObject("select count(*) from academic_class_events where entity_id=?",Long.class,c)).isEqualTo(4);
        assertThat(sql().queryForObject("select count(*) from academic_period_events where entity_id=?",Long.class,p)).isEqualTo(4);
        assertThat(sql().queryForObject("select period_id from academic_classes where id=?",Long.class,c)).isEqualTo(p);
    }
    @Test void enrollmentSuspensionAndReadmissionKeepAllEpisodes() {
        var first=enrolled(c,student);
        service.transitionEnrollment(first.id(),Enrollment.Status.SUSPENDED,at(20));
        service.transitionEnrollment(first.id(),Enrollment.Status.ACTIVE,at(30));
        service.transitionEnrollment(first.id(),Enrollment.Status.WITHDRAWN,at(40));
        var next=service.createEnrollment(a,c,student,at(40),end,at(40));
        service.transitionEnrollment(next.id(),Enrollment.Status.ACTIVE,at(40));
        assertThat(next.episodeNumber()).isEqualTo(2);assertThat(next.id()).isNotEqualTo(first.id());
        assertThat(sql().queryForList("select version from enrollment_events where entity_id=? order by version",Long.class,first.id()))
            .containsExactly(0L,1L,2L,3L,4L);
        assertThat(sql().queryForObject("select activated_at from enrollments where id=?",Timestamp.class,first.id())).isEqualTo(Timestamp.from(at(10)));
        assertThatThrownBy(()->service.transitionEnrollment(first.id(),Enrollment.Status.ACTIVE,at(50))).isInstanceOf(DataAccessException.class);
    }
    @Test void suspendedAndTerminatedHistoryStillBlocksOverlappingEpisodes() {
        var first=enrolled(c,student);service.transitionEnrollment(first.id(),Enrollment.Status.SUSPENDED,at(20));
        var second=pending(c,student);
        assertThatThrownBy(()->service.transitionEnrollment(second.id(),Enrollment.Status.ACTIVE,at(25))).hasStackTraceContaining("exclusion");
        service.transitionEnrollment(first.id(),Enrollment.Status.COMPLETED,at(30));
        assertThatThrownBy(()->service.transitionEnrollment(second.id(),Enrollment.Status.ACTIVE,at(25))).hasStackTraceContaining("exclusion");
        service.transitionEnrollment(second.id(),Enrollment.Status.ACTIVE,at(30));
    }
    @Test void cancellationsNeverReserveEffectiveIntervals() {
        var first=pending(c,student);service.transitionEnrollment(first.id(),Enrollment.Status.CANCELLED,at(5));
        assertThat(enrolled(c,student).episodeNumber()).isEqualTo(2);
        var planned=service.planAssignment(a,c,teacher,at(10),end,at(3));
        service.transitionAssignment(planned.id(),TeachingAssignment.Status.CANCELLED,at(5));
        assertThat(responsible(replacement).status()).isEqualTo(TeachingAssignment.Status.ACTIVE);
    }
    @Test void naturalExpiryAllowsAdjacentEpisodeWithoutRewritingPreviousStatus() {
        var first=service.createEnrollment(a,c,student,at(10),at(20),at(3));
        service.transitionEnrollment(first.id(),Enrollment.Status.ACTIVE,at(10));
        var next=service.createEnrollment(a,c,student,at(20),end,at(3));
        service.transitionEnrollment(next.id(),Enrollment.Status.ACTIVE,at(20));
        assertThat(sql().queryForObject("select status from enrollments where id=?",String.class,first.id())).isEqualTo("ACTIVE");
    }
    @Test void replacingResponsibleConservesClassAccountsAndEnrollments() {
        var enrolled=enrolled(c,student);var old=responsible(teacher);
        var accounts=sql().queryForList("select * from user_accounts order by id");
        var classes=sql().queryForList("select * from academic_classes order by id");
        var enrollments=sql().queryForList("select * from enrollments order by id");
        var next=service.replaceResponsible(old.id(),replacement,at(20),end);
        assertThat(next.classId()).isEqualTo(c);assertThat(next.activatedAt()).isEqualTo(at(20));
        assertThat(sql().queryForList("select * from user_accounts order by id")).isEqualTo(accounts);
        assertThat(sql().queryForList("select * from academic_classes order by id")).isEqualTo(classes);
        assertThat(sql().queryForList("select * from enrollments order by id")).isEqualTo(enrollments);
        assertThat(sql().queryForObject("select ended_at from teaching_assignments where id=?",Timestamp.class,old.id())).isEqualTo(Timestamp.from(at(20)));
        assertThat(enrolled.classId()).isEqualTo(c);
    }
    @Test void noCoTeachingAndNoSecondResponsibleDuringEffectiveHistory() {
        var old=responsible(teacher);
        var next=service.planAssignment(a,c,replacement,at(10),end,at(3));
        assertThatThrownBy(()->service.transitionAssignment(next.id(),TeachingAssignment.Status.ACTIVE,at(15))).hasStackTraceContaining("exclusion");
        service.transitionAssignment(old.id(),TeachingAssignment.Status.ENDED,at(20));
        assertThatThrownBy(()->service.transitionAssignment(next.id(),TeachingAssignment.Status.ACTIVE,at(15))).hasStackTraceContaining("exclusion");
        service.transitionAssignment(next.id(),TeachingAssignment.Status.ACTIVE,at(20));
        assertThatThrownBy(()->sql().update("update teaching_assignments set function='CO_DOCENTE' where id=?",next.id())).isInstanceOf(DataAccessException.class);
    }
    @Test void historyIdentitiesAndWindowsCannotBeEditedDeletedOrTruncated() {
        var e=enrolled(c,student);
        for(String table:List.of("academic_periods","academic_classes","teaching_assignments","enrollments",
            "academic_period_events","academic_class_events","teaching_assignment_events","enrollment_events")) {
            assertThatThrownBy(()->sql().execute("truncate "+table+" cascade")).isInstanceOf(DataAccessException.class);
        }
        assertThatThrownBy(()->sql().update("delete from enrollments where id=?",e.id())).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(()->sql().update("update enrollment_events set reason='X' where entity_id=?",e.id())).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(()->sql().update("update enrollments set valid_until=? where id=?",Timestamp.from(at(50)),e.id())).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(()->sql().update("update academic_classes set period_id=? where id=?",q,c)).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(()->sql().update("update enrollments set episode_number=7 where id=?",e.id())).isInstanceOf(DataAccessException.class);
    }
    @Test void invalidAndNonMonotonicRelationTransitionsDoNotWriteVersions() {
        var e=pending(c,student);
        assertThatThrownBy(()->service.transitionEnrollment(e.id(),Enrollment.Status.COMPLETED,at(10))).isInstanceOf(DataAccessException.class);
        service.transitionEnrollment(e.id(),Enrollment.Status.ACTIVE,at(10));
        assertThatThrownBy(()->service.transitionEnrollment(e.id(),Enrollment.Status.ACTIVE,at(11))).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(()->service.transitionEnrollment(e.id(),Enrollment.Status.SUSPENDED,at(10))).isInstanceOf(DataAccessException.class);
        assertThat(sql().queryForObject("select version from enrollments where id=?",Long.class,e.id())).isEqualTo(1);
    }
    @Test void rollbackRemovesBusinessHistoryVersionAndSuccess() {
        var e=pending(c,student);long logs=sql().queryForObject("select count(*) from system_logs",Long.class);
        tx().executeWithoutResult(s->{service.transitionEnrollment(e.id(),Enrollment.Status.ACTIVE,at(10));s.setRollbackOnly();});
        assertThat(sql().queryForObject("select version from enrollments where id=?",Long.class,e.id())).isZero();
        assertThat(sql().queryForObject("select count(*) from enrollment_events where entity_id=?",Long.class,e.id())).isEqualTo(1);
        assertThat(sql().queryForObject("select count(*) from system_logs",Long.class)).isEqualTo(logs);
    }
    @Test void failedReplacementRestoresPredecessorAndNoOrphanSuccess() {
        var old=responsible(teacher);long logs=sql().queryForObject("select count(*) from system_logs",Long.class);
        assertThatThrownBy(()->service.replaceResponsible(old.id(),student,at(20),end)).isInstanceOf(DataAccessException.class);
        assertThat(sql().queryForObject("select status from teaching_assignments where id=?",String.class,old.id())).isEqualTo("ACTIVE");
        assertThat(sql().queryForObject("select count(*) from teaching_assignments where class_id=?",Long.class,c)).isEqualTo(1);
        assertThat(sql().queryForObject("select count(*) from system_logs",Long.class)).isEqualTo(logs);
    }
    @Test void evidenceFailureAfterEndingAlsoRollsBackWholeReplacement() {
        var old=responsible(teacher);long logs=sql().queryForObject("select count(*) from system_logs",Long.class);
        sql().execute("""
            create function fictitious_b2_audit_failure() returns trigger language plpgsql as $$
            begin if NEW.event_type='TEACHING_ASSIGNMENT_CREATED' then raise exception 'fictitious evidence outage'; end if; return NEW; end $$;
            create trigger fictitious_b2_audit_failure before insert on system_logs for each row execute function fictitious_b2_audit_failure()
            """);
        try {
            assertThatThrownBy(()->service.replaceResponsible(old.id(),replacement,at(20),end))
                .isInstanceOf(com.morales.chemicallab.security.audit.AuditUnavailableException.class);
            assertThat(sql().queryForObject("select version from teaching_assignments where id=?",Long.class,old.id())).isEqualTo(1);
            assertThat(sql().queryForObject("select count(*) from teaching_assignment_events where entity_id=?",Long.class,old.id())).isEqualTo(2);
            assertThat(sql().queryForObject("select count(*) from teaching_assignments where class_id=?",Long.class,c)).isEqualTo(1);
            assertThat(sql().queryForObject("select count(*) from system_logs",Long.class)).isEqualTo(logs);
        } finally {sql().execute("drop trigger fictitious_b2_audit_failure on system_logs");sql().execute("drop function fictitious_b2_audit_failure()");}
    }
    @Test void expansionRepeatAndDivergenceValidateB1AndB2WithoutRepair() {
        sql().execute(b1);sql().execute(expansion);sql().execute(expansion);
        sql().execute("alter table enrollments disable trigger t11b2_guard");
        try {assertThatThrownBy(()->sql().execute(expansion)).hasStackTraceContaining("schema divergence");}
        finally {sql().execute("alter table enrollments enable trigger t11b2_guard");}
        sql().execute("alter table institutions disable trigger t11b1_institution_guard");
        try {assertThatThrownBy(()->sql().execute(expansion)).hasStackTraceContaining("prerequisite T11-B1 divergence");}
        finally {sql().execute("alter table institutions enable trigger t11b1_institution_guard");}
        sql().execute(expansion);
    }
    @Test void expansionOnPopulatedB1AndLegacyPreservesDataAndRecoveryRoundTrip() {
        String schema="b2_old_"+UUID.randomUUID().toString().replace("-","");
        var accounts=sql().queryForList("select * from user_accounts order by id");
        try {
            tx().executeWithoutResult(s->{
                sql().execute("create schema "+schema);
                sql().execute("create table "+schema+".user_accounts(like user_accounts including all)");
                sql().execute("insert into "+schema+".user_accounts select * from user_accounts");
                sql().execute("create table "+schema+".account_sessions as select * from account_sessions");
                sql().execute("set local search_path to "+schema+","+f.schema);
                sql().execute(b1);
                sql().update("insert into institutions(platform_code,name) values('FIXTURE','Fictitious')");
                var before=sql().queryForList("select * from institutions");
                sql().execute(expansion);sql().execute(expansion);sql().execute(b1);
                assertThat(sql().queryForList("select * from user_accounts order by id")).isEqualTo(accounts);
                assertThat(sql().queryForList("select * from institutions")).isEqualTo(before);
                assertThat(sql().queryForObject("select count(*) from enrollments",Long.class)).isZero();
                var constraints=sql().queryForList("""
                    select r.relname,c.conname,pg_get_constraintdef(c.oid) as definition from pg_constraint c
                    join pg_class r on r.oid=c.conrelid where r.relnamespace=current_schema()::regnamespace and c.contype='c'
                    and r.relname in ('academic_periods','academic_classes','teaching_assignments','enrollments')
                    """);
                for(var constraint:constraints) {
                    sql().execute("alter table "+constraint.get("relname")+" drop constraint "+constraint.get("conname"));
                    sql().execute("alter table "+constraint.get("relname")+" add constraint "+constraint.get("conname")+" "+constraint.get("definition"));
                }
                sql().execute(expansion);
            });
        } finally {sql().execute("drop schema if exists "+schema+" cascade");}
    }
    @Test void explicitExpansionExtendsLegacyAuditEnumWithoutHibernateOrChangingEvidence() {
        String schema="b2_audit_"+UUID.randomUUID().toString().replace("-","");
        try {
            tx().executeWithoutResult(s->{
                sql().execute("create schema "+schema);
                sql().execute("create table "+schema+".user_accounts(id bigint primary key)");
                sql().execute("create table "+schema+".system_logs(id bigint primary key,event_type varchar(255) check(event_type in ('LOGIN_SUCCESS','ADMIN_ACTION')))");
                sql().execute("insert into "+schema+".system_logs values(1,'LOGIN_SUCCESS')");
                sql().execute("set local search_path to "+schema+","+f.schema);sql().execute(b1);sql().execute(expansion);sql().execute(expansion);
            });
            sql().execute("insert into "+schema+".system_logs values(2,'ENROLLMENT_CREATED')");
            assertThatThrownBy(()->sql().execute("insert into "+schema+".system_logs values(3,'UNKNOWN')")).isInstanceOf(DataAccessException.class);
            assertThat(sql().queryForObject("select event_type from "+schema+".system_logs where id=1",String.class)).isEqualTo("LOGIN_SUCCESS");
            tx().executeWithoutResult(s->{
                sql().execute("set local search_path to "+schema+","+f.schema);
                var constraints=sql().queryForList("select conname,pg_get_constraintdef(oid) as definition from pg_constraint where conrelid='system_logs'::regclass and contype='c'");
                for(var constraint:constraints) {
                    sql().execute("alter table system_logs drop constraint "+constraint.get("conname"));
                    sql().execute("alter table system_logs add constraint "+constraint.get("conname")+" "+constraint.get("definition"));
                }
                sql().execute(b1);sql().execute(expansion);
            });
        } finally {sql().execute("drop schema if exists "+schema+" cascade");}
    }
    @Test void expansionRejectsUnversionedObjectsAtomically() {
        String schema="b2_bad_"+UUID.randomUUID().toString().replace("-","");
        try {
            tx().executeWithoutResult(s->{
                sql().execute("create schema "+schema);sql().execute("create table "+schema+".user_accounts(id bigint primary key)");
                sql().execute("insert into "+schema+".user_accounts values(42)");
                sql().execute("set local search_path to "+schema+","+f.schema);sql().execute(b1);
                sql().execute("create table enrollments(id bigint)");
            });
            tx().executeWithoutResult(s->{
                sql().execute("set local search_path to "+schema+","+f.schema);
                assertThatThrownBy(()->sql().execute(expansion)).hasStackTraceContaining("unversioned objects");s.setRollbackOnly();
            });
            assertThat(sql().queryForObject("select id from "+schema+".user_accounts",Long.class)).isEqualTo(42);
            assertThat(sql().queryForObject("select count(*) from pg_class where relnamespace=?::regnamespace and relname='t11b2_schema_version'",Long.class,schema)).isZero();
        } finally {sql().execute("drop schema if exists "+schema+" cascade");}
    }
    @Test void authenticatedActorAndSafeEvidenceAreRecorded() throws Exception {
        String token=f.login(f.teacher);
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(f.app.getBean(AccountSessionService.class).authenticate(token));
        try {
            var e=enrolled(c,student);
            assertThat(sql().queryForObject("select changed_by from enrollments where id=?",Long.class,e.id())).isEqualTo(f.teacher.getId());
            var logs=sql().queryForList("select * from system_logs where target_type='Enrollment' and target_id=?",e.id());
            assertThat(logs).hasSize(2).allSatisfy(r->{
                assertThat(r.get("actor_user_id")).isEqualTo(f.teacher.getId());
                assertThat(r.get("outcome")).isEqualTo("SUCCESS");assertThat(r.get("correlation_id")).isNotNull();
                assertThat(r.get("metadata")).isEqualTo("institutionId="+a);
                assertThat(r.toString()).doesNotContain(token,"Chemistry","QUIM-3A");
            });
        } finally {org.springframework.security.core.context.SecurityContextHolder.clearContext();}
    }
    @Test void newAcademicRelationsLeaveSessionsCredentialsRestAndStompAuthoritiesIntact() throws Exception {
        String token=f.login(f.student);
        var accounts=sql().queryForList("select * from user_accounts order by id");
        var sessions=sql().queryForList("select * from account_sessions order by id");
        institutions.grantRole(student,MembershipRoleGrant.InstitutionalRole.DOCENTE,at(2),null);
        institutions.grantRole(student,MembershipRoleGrant.InstitutionalRole.ADMIN_INSTITUCION,at(2),null);
        enrolled(c,student);responsible(student);
        assertThat(sql().queryForList("select * from user_accounts order by id")).isEqualTo(accounts);
        assertThat(sql().queryForList("select * from account_sessions order by id")).isEqualTo(sessions);
        assertThat(f.request("GET","/api/auth/me",token,null).body()).contains("ESTUDIANTE").doesNotContain("ADMIN_INSTITUCION");
        assertThat(f.request("GET","/api/admin/logs",token,null).statusCode()).isEqualTo(403);
        assertThat(f.request("GET","/api/whiteboards/teacher",token,null).statusCode()).isEqualTo(403);
        var peer=f.connect(f.student);peer.subscribe("foreign","/topic/whiteboards/"+f.otherBoard);
        assertThat(peer.closed.await(6,TimeUnit.SECONDS)).isTrue();
    }
    @Test void concurrentActivationCannotCreateTwoEffectiveResponsibles() throws Exception {
        var first=service.planAssignment(a,c,teacher,at(10),end,at(3));
        var second=service.planAssignment(a,c,replacement,at(10),end,at(3));
        race(update("teaching_assignments",first.id()),update("teaching_assignments",second.id()));
        assertThat(sql().queryForObject("select count(*) from teaching_assignments where class_id=? and status='ACTIVE'",Long.class,c)).isEqualTo(1);
    }
    @Test void concurrentActivationCannotCreateIncompatibleEnrollmentEpisodes() throws Exception {
        var first=pending(c,student);var second=pending(c,student);
        race(update("enrollments",first.id()),update("enrollments",second.id()));
        assertThat(sql().queryForObject("select count(*) from enrollments where class_id=? and status='ACTIVE'",Long.class,c)).isEqualTo(1);
    }
    String update(String table,long id) {
        return "update "+table+" set status='ACTIVE',state_since='"+at(10)+"',activated_at='"+at(10)+"',change_reason='ACTIVE' where id="+id;
    }
    void race(String first,String second) throws Exception {
        var pool=Executors.newFixedThreadPool(2);var gate=new CountDownLatch(2);
        try {
            var tasks=new ArrayList<Callable<String>>();
            for(String statement:List.of(first,second))tasks.add(()->{
                try(var connection=DriverManager.getConnection(f.setting("spring.datasource.url")+"?currentSchema="+f.schema,
                    f.setting("spring.datasource.username"),f.setting("spring.datasource.password"))) {
                    connection.setAutoCommit(false);
                    try(var s=connection.createStatement()) {
                        s.execute("set local lock_timeout='5s'");s.execute("set local statement_timeout='8s'");
                        gate.countDown();if(!gate.await(5,TimeUnit.SECONDS))throw new IllegalStateException("Barrier");
                        try {s.execute(statement);connection.commit();return "committed";}
                        catch(SQLException ex){connection.rollback();return ex.getSQLState();}
                    }
                }
            });
            var results=new ArrayList<String>();for(var future:pool.invokeAll(tasks,15,TimeUnit.SECONDS))results.add(future.get());
            assertThat(results).containsExactlyInAnyOrder("committed","23P01");
        } finally {pool.shutdownNow();}
    }
}
