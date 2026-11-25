package com.morales.chemicallab.service;

import com.morales.chemicallab.entity.*;
import com.morales.chemicallab.repository.UserAccountRepository;
import com.morales.chemicallab.security.PasswordPolicy;
import com.morales.chemicallab.security.SessionPrincipal;
import com.morales.chemicallab.security.audit.AuditContext;
import com.morales.chemicallab.security.audit.AuditSafety;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.LongFunction;
import java.util.function.Supplier;

@Service
@RequiredArgsConstructor
public class InstitutionalAdministration {
    static final String ACADEMIC = "evaluation_runtime.";
    static final String ADMIN = "administration_runtime.";
    private static final String ADMINS = """
        from evaluation_runtime.memberships m
        join user_accounts u on u.id=m.account_id
        join evaluation_runtime.roles g on g.membership_id=m.id and g.institution_id=m.institution_id
        cross join (select statement_timestamp() at) t
        where g.role='ADMIN_INSTITUCION' and
        """ + EvaluationRuntime.ROLE;

    private final JdbcTemplate jdbc;
    private final EvaluationRuntime runtime;
    private final AdministrationRuntimeContract contract;
    private final WhiteboardRuntimeContract boards;
    private final UserAccountRepository users;
    private final PasswordEncoder passwords;
    private final AuditLogWriter audit;

    public enum MemberRole { ADMIN_INSTITUCION, DOCENTE, ESTUDIANTE }
    public enum MemberState { SUSPEND, RESUME, END }
    public enum Teaching { RESPONSABLE, CO_DOCENTE }
    public enum Faculty { EDIT_DRAFT, PUBLISH, ASSIGN }
    public enum Library {
        EVALUATIONS("evaluation_runtime."), CONTENT("content_runtime.");
        final String schema;
        Library(String schema) { this.schema = schema; }
    }

    public record Access(boolean adopted, boolean enabled, boolean platform) {}
    public record NewAccount(String username, String displayName) {}
    public record CreatedMember(long membershipId, String username, String temporaryPassword) {}
    public record Page(List<Map<String,Object>> items, Long nextAfter) {}
    public record NewClass(long periodId, String code, String name) {}
    public record NewPeriod(String name, Instant from, Instant until) {}
    public record NewEpisode(long classId, long membershipId, Instant until) {}
    public record NewTeaching(long classId, long membershipId, Instant until, Teaching faculty) {}
    public record NewFaculty(long resourceId, long membershipId, Instant until, Faculty faculty) {}

    public Access access(SessionPrincipal actor) {
        boolean adopted = boards.installed();
        boolean enabled = adopted && contract.installed();
        return new Access(adopted, enabled, enabled && runtime.unit(actor, null, null, false, null, () -> platform(actor)));
    }

    public Page institutions(SessionPrincipal actor, boolean asPlatform, long after, int limit) {
        return available(actor, () -> asPlatform
            ? platformOnly(actor, () -> page("""
                    select i.id,i.name,i.active,exists(select 1 from evaluation_runtime.roles g
                      where g.institution_id=i.id and g.role='ADMIN_INSTITUCION') has_administrator
                    from evaluation_runtime.institutions i where i.id>?
                    """,
                    after, limit, "id"))
            : page("""
                select i.id,i.name,i.active from evaluation_runtime.institutions i where i.active and exists(
                    select 1
                """ + ADMINS + """
                    and m.account_id=? and m.institution_id=i.id)
                and i.id>?
                """, after, limit, "id", actor.userId()));
    }

    public long createInstitution(SessionPrincipal actor, String name) {
        String label = label(name, 200);
        return available(actor, () -> platformOnly(actor, () -> {
            long institution = id("insert into evaluation_runtime.institutions(name) values(?) returning id", label);
            jdbc.update("insert into evaluation_runtime.quotas(institution_id,max_rows,max_bytes) values(?,10000,67108864)", institution);
            evidence(actor, institution, "INSTITUTION_CREATED", "Institution", institution, null);
            return institution;
        }));
    }

    public void institutionState(SessionPrincipal actor, long institution, boolean active) {
        available(actor, () -> platformOnly(actor, () -> {
            lock(institution, true);
            changed(jdbc.update("""
                update evaluation_runtime.institutions set active=? where id=? and active<>?
                """, active, institution, active));
            evidence(actor, institution, active ? "INSTITUTION_RESUMED" : "INSTITUTION_SUSPENDED", "Institution", institution, null);
            return null;
        }));
    }

    public CreatedMember firstAdmin(SessionPrincipal actor, long institution, NewAccount request) {
        return available(actor, () -> platformOnly(actor, () -> {
            lock(institution, true);
            if (!exists("select exists(select 1 from evaluation_runtime.institutions where id=?)", institution)) throw denied();
            if (exists("""
                select exists(select 1 from evaluation_runtime.roles where institution_id=? and role='ADMIN_INSTITUCION')
                """, institution)) throw conflict("La institución ya tuvo un administrador.");
            CreatedMember member = createMember(actor, institution, request);
            grantRole(actor, institution, member.membershipId(), MemberRole.ADMIN_INSTITUCION);
            return member;
        }));
    }

    public CreatedMember member(SessionPrincipal actor, long institution, NewAccount request) {
        return scoped(actor, institution, true, admin -> createMember(actor, institution, request));
    }

    private CreatedMember createMember(SessionPrincipal actor, long institution, NewAccount request) {
        String username = request.username();
        if (username == null || !username.matches("[A-Za-z0-9][A-Za-z0-9._-]{2,63}")) {
            throw invalid("Usa un identificador nuevo de 3 a 64 caracteres sin espacios.");
        }
        String name = label(request.displayName(), 200);
        String temporary = PasswordPolicy.temporary();
        long account = users.saveAndFlush(UserAccount.builder().username(username).password(passwords.encode(temporary))
                .role(Role.INSTITUCIONAL).active(true).temporaryPassword(true).build()).getId();
        long membership = id("""
            insert into evaluation_runtime.memberships(institution_id,account_id,valid_from)
            values(?,?,statement_timestamp()) returning id
            """, institution, account);
        jdbc.update("insert into administration_runtime.member_names values(?,?,?)", membership, institution, name);
        evidence(actor, institution, "MEMBERSHIP_CREATED", "InstitutionMembership", membership, membership);
        return new CreatedMember(membership, username, temporary);
    }

    public void membershipState(SessionPrincipal actor, long institution, long membership, MemberState state) {
        scoped(actor, institution, true, admin -> {
            require(ACADEMIC + "memberships", institution, membership);
            if (state != MemberState.RESUME) retainAdministrator(institution, membership);
            String update = switch (state) {
                case SUSPEND -> "set active=false where active and (valid_until is null or valid_until>statement_timestamp())";
                case RESUME -> "set active=true where not active and (valid_until is null or valid_until>statement_timestamp())";
                case END -> """
                    set active=false, valid_until=greatest(statement_timestamp(), valid_from+interval '1 microsecond')
                    where valid_until is null or valid_until>statement_timestamp()
                    """;
            };
            changed(jdbc.update("update evaluation_runtime.memberships " + update + " and id=? and institution_id=?",
                    membership, institution));
            if (state == MemberState.END) {
                endMembershipRelations(institution, membership, "teaching");
                endMembershipRelations(institution, membership, "enrollments");
                for (String table : List.of(ACADEMIC + "roles", ACADEMIC + "faculties", "content_runtime.faculties")) {
                    jdbc.update("update " + table + " set revoked_at=statement_timestamp() "
                        + "where membership_id=? and institution_id=? and revoked_at is null", membership, institution);
                }
            }
            evidence(actor, institution, "MEMBERSHIP_" + state.name(), "InstitutionMembership", membership, membership);
            return null;
        });
    }

    public long role(SessionPrincipal actor, long institution, long membership, MemberRole role) {
        return scoped(actor, institution, true, admin -> grantRole(actor, institution, membership, role));
    }

    private long grantRole(SessionPrincipal actor, long institution, long membership, MemberRole role) {
        if (!exists("""
            select exists(select 1 from evaluation_runtime.memberships m join user_accounts u on u.id=m.account_id
                where m.id=? and m.institution_id=? and m.active and u.active and m.valid_from<=statement_timestamp()
                and (m.valid_until is null or m.valid_until>statement_timestamp()))
            """, membership, institution)) throw denied();
        long grant = id("""
            insert into evaluation_runtime.roles(membership_id,institution_id,role,valid_from)
            values(?,?,?,statement_timestamp()) returning id
            """, membership, institution, role.name());
        if (role == MemberRole.DOCENTE) {
            // The existing evaluation/board engine requires a profile FK. Runtime grants supply authority.
            jdbc.update("""
                insert into teacher_profiles(user_id,names,last_names)
                select u.id,'Docente','#'||u.id from evaluation_runtime.memberships m
                join user_accounts u on u.id=m.account_id
                where m.id=? and m.institution_id=? and u.role='INSTITUCIONAL'
                on conflict(user_id) do nothing
                """, membership, institution);
        }
        evidence(actor, institution, "ROLE_GRANTED_" + role.name(), "MembershipRoleGrant", grant, membership);
        return grant;
    }

    public void revokeRole(SessionPrincipal actor, long institution, long membership, MemberRole role) {
        scoped(actor, institution, true, admin -> {
            require(ACADEMIC + "memberships", institution, membership);
            if (role == MemberRole.ADMIN_INSTITUCION) retainAdministrator(institution, membership);
            long grant = id("""
                update evaluation_runtime.roles set revoked_at=statement_timestamp()
                where membership_id=? and institution_id=? and role=? and revoked_at is null returning id
                """, membership, institution, role.name());
            if (role == MemberRole.DOCENTE) endMembershipRelations(institution, membership, "teaching");
            if (role == MemberRole.ESTUDIANTE) endMembershipRelations(institution, membership, "enrollments");
            evidence(actor, institution, "ROLE_REVOKED_" + role.name(), "MembershipRoleGrant", grant, membership);
            return null;
        });
    }

    private void retainAdministrator(long institution, long membership) {
        if (exists("select exists(select 1 " + ADMINS + " and m.id=? and m.institution_id=?)", membership, institution)
                && !exists("select exists(select 1 " + ADMINS + " and m.id<>? and m.institution_id=?)", membership, institution)) {
            throw conflict("La institución necesita al menos otro administrador vigente.");
        }
    }

    private void endMembershipRelations(long institution, long membership, String table) {
        jdbc.update("update " + ACADEMIC + table + " set ended_at=statement_timestamp() "
                + "where institution_id=? and membership_id=? and ended_at is null", institution, membership);
    }

    public long period(SessionPrincipal actor, long institution, NewPeriod request) {
        return scoped(actor, institution, true, admin -> {
            if (request.from() == null || request.until() == null || !request.until().isAfter(request.from())
                    || !request.until().isAfter(runtime.now())) throw invalid("Revisa las fechas del período.");
            long period = id("""
                insert into evaluation_runtime.periods(institution_id,name,valid_from,valid_until) values(?,?,?,?) returning id
                """, institution, label(request.name(), 200), Timestamp.from(request.from()), Timestamp.from(request.until()));
            evidence(actor, institution, "PERIOD_CREATED", "AcademicPeriod", period, null);
            return period;
        });
    }

    public long createClass(SessionPrincipal actor, long institution, NewClass request) {
        return scoped(actor, institution, true, admin -> {
            if (!exists("""
                select exists(select 1 from evaluation_runtime.periods where id=? and institution_id=?
                    and open and valid_until>statement_timestamp())
                """, request.periodId(), institution)) throw denied();
            long clazz = id("""
                insert into evaluation_runtime.classes(institution_id,period_id,code,name) values(?,?,?,?) returning id
                """, institution, request.periodId(), label(request.code(), 64), label(request.name(), 200));
            evidence(actor, institution, "CLASS_CREATED", "AcademicClass", clazz, null);
            return clazz;
        });
    }

    public void close(SessionPrincipal actor, long institution, long target, boolean period) {
        scoped(actor, institution, true, admin -> {
            String table = period ? "periods" : "classes";
            String flag = period ? "open" : "active";
            changed(jdbc.update("update " + ACADEMIC + table + " set " + flag + "=false where id=? and institution_id=? and " + flag,
                    target, institution));
            evidence(actor, institution, period ? "PERIOD_CLOSED" : "CLASS_CLOSED", period ? "AcademicPeriod" : "AcademicClass", target, null);
            return null;
        });
    }

    public long episode(SessionPrincipal actor, long institution, NewEpisode request, Teaching faculty) {
        return scoped(actor, institution, true, admin -> {
            long membership = request.membershipId();
            long clazz = request.classId();
            String table = faculty == null ? "enrollments" : "teaching";
            String type = faculty == null ? "Enrollment" : "TeachingAssignment";
            String role = faculty == null ? "ESTUDIANTE" : "DOCENTE";
            if (request.until() == null || !request.until().isAfter(runtime.now())) throw invalid("Indica una fecha de fin futura.");
            // The scope lock serializes writers and prevents re-admitting an enrolled account.
            if (faculty == null && exists("""
                select exists(select 1 from evaluation_runtime.enrollments where institution_id=? and class_id=? and membership_id=?)
                """, institution, clazz, membership)) throw conflict("La matrícula ya tiene un episodio.");
            String column = faculty == null ? "account_id" : "faculty";
            String value = faculty == null ? "u.id" : "?";
            var args = new java.util.ArrayList<Object>();
            if (faculty != null) args.add(faculty.name());
            args.addAll(List.of(Timestamp.from(request.until()), clazz, institution, membership, role));
            long episode = id("""
                insert into evaluation_runtime.%s(institution_id,class_id,membership_id,%s,valid_from,valid_until)
                select m.institution_id,c.id,m.id,%s,t.at,least(?,p.valid_until,m.valid_until,g.valid_until)
                from evaluation_runtime.classes c join evaluation_runtime.periods p on p.id=c.period_id
                join evaluation_runtime.institutions i on i.id=c.institution_id
                join evaluation_runtime.memberships m on m.institution_id=i.id
                join user_accounts u on u.id=m.account_id
                join evaluation_runtime.roles g on g.membership_id=m.id
                cross join (select statement_timestamp() at) t
                where c.id=? and i.id=? and m.id=? and g.role=? and %s and %s returning id
                """.formatted(table, column, value, EvaluationRuntime.ROLE, EvaluationRuntime.CLASS), args.toArray());
            evidence(actor, institution, faculty == null ? "ENROLLMENT_CREATED" : "TEACHING_ASSIGNED", type, episode, membership);
            return episode;
        });
    }

    public void endEpisode(SessionPrincipal actor, long institution, long episode, boolean teaching) {
        scoped(actor, institution, true, admin -> {
            String table = teaching ? "teaching" : "enrollments";
            long member = id("update " + ACADEMIC + table + """
                set ended_at=statement_timestamp() where id=? and institution_id=? and ended_at is null returning membership_id
                """, episode, institution);
            evidence(actor, institution, teaching ? "TEACHING_ENDED" : "ENROLLMENT_ENDED",
                    teaching ? "TeachingAssignment" : "Enrollment", episode, member);
            return null;
        });
    }

    public long faculty(SessionPrincipal actor, long institution, Library library, NewFaculty request) {
        return scoped(actor, institution, true, admin -> {
            require(library.schema + "resources", institution, request.resourceId());
            if (request.until() == null || !request.until().isAfter(runtime.now())) throw invalid("Indica una fecha de fin futura.");
            long grant = id("insert into " + library.schema + """
                faculties(resource_id,institution_id,membership_id,faculty,granted_by_membership_id,valid_from,valid_until)
                select ?,m.institution_id,m.id,?,?,t.at,least(?,m.valid_until)
                from evaluation_runtime.memberships m join user_accounts u on u.id=m.account_id
                cross join (select statement_timestamp() at) t
                where m.id=? and m.institution_id=? and m.active and u.active and m.valid_from<=t.at
                    and (m.valid_until is null or m.valid_until>t.at) returning id
                """, request.resourceId(), request.faculty().name(), admin, Timestamp.from(request.until()), request.membershipId(), institution);
            evidence(actor, institution, library.name() + "_FACULTY_GRANTED", "ResourceManagerGrant", grant, request.membershipId());
            return grant;
        });
    }

    public void revokeFaculty(SessionPrincipal actor, long institution, Library library, long grant) {
        scoped(actor, institution, true, admin -> {
            long member = id("update " + library.schema + """
                faculties set revoked_at=statement_timestamp()
                where id=? and institution_id=? and revoked_at is null returning membership_id
                """, grant, institution);
            evidence(actor, institution, library.name() + "_FACULTY_REVOKED", "ResourceManagerGrant", grant, member);
            return null;
        });
    }

    <T> T scoped(SessionPrincipal actor, long institution, boolean writing, LongFunction<T> work) {
        return available(actor, () -> {
            lock(institution, writing);
            long member = id("select m.id " + ADMINS + """
                and m.account_id=? and m.institution_id=?
                and exists(select 1 from evaluation_runtime.institutions i where i.id=m.institution_id and i.active)
                """, actor.userId(), institution);
            return work.apply(member);
        });
    }

    private void lock(long institution, boolean writing) {
        String function = writing ? "pg_advisory_xact_lock" : "pg_advisory_xact_lock_shared";
        jdbc.queryForObject("select " + function + "(evaluation_runtime.institution_lock(?))", Object.class, institution);
    }

    private <T> T available(SessionPrincipal actor, Supplier<T> work) {
        if (!boards.installed() || !contract.installed()) throw denied();
        return runtime.unit(actor, null, null, false, null, work);
    }

    private boolean platform(SessionPrincipal actor) {
        return actor.role() == Role.ADMINISTRADOR && exists("""
            select exists(select 1 from administration_runtime.platform_authority where account_id=?)
            """, actor.userId());
    }

    private <T> T platformOnly(SessionPrincipal actor, Supplier<T> work) {
        if (!platform(actor)) throw denied();
        return work.get();
    }

    private void evidence(SessionPrincipal actor, long institution, String action, String targetType, long target, Long subject) {
        var log = SystemLog.builder().eventType(LogEventType.ADMIN_ACTION).category(LogCategory.ADMIN)
            .severity(LogSeverity.INFO).outcome("SUCCESS").actorUserId(actor.userId())
            .actorRole(actor.role()).actorUsername(AuditSafety.text(actor.username(), 100))
            .targetType(targetType).targetId(target).action(action).description(action)
            .metadata("institutionId=" + institution).correlationId(AuditContext.correlation()).build();
        audit.persist(log);
        jdbc.update("insert into administration_runtime.changes values(?,?,?,?,?,?)",
                log.getId(), institution, actor.userId(), subject, action, target);
    }

    void require(String table, long institution, long target) {
        if (!exists("select exists(select 1 from " + table + " where institution_id=? and id=?)", institution, target)) throw denied();
    }

    Page page(String query, long after, int limit, String order, Object... filters) {
        if (after < 0 || limit < 1 || limit > 40) throw invalid("Página inválida.");
        var args = new java.util.ArrayList<>(List.of(filters));
        args.add(after);
        args.add(limit + 1);
        var rows = jdbc.queryForList(query + " order by " + order + " limit ?", args.toArray());
        return page(rows, limit);
    }

    static Page page(List<Map<String,Object>> rows, int limit) {
        var items = List.copyOf(rows.subList(0, Math.min(rows.size(), limit)));
        return new Page(items, rows.size() > limit ? ((Number)items.get(items.size()-1).get("id")).longValue() : null);
    }

    private long id(String query, Object... args) {
        var rows = jdbc.queryForList(query, Long.class, args);
        if (rows.size() != 1) throw denied();
        return rows.get(0);
    }

    private boolean exists(String query, Object... args) {
        return Boolean.TRUE.equals(jdbc.queryForObject(query, Boolean.class, args));
    }

    private static void changed(int rows) {
        if (rows != 1) throw conflict("El estado ya cambió o la operación está fuera del ámbito.");
    }

    private static String label(String value, int max) {
        String label = value == null ? "" : value.strip();
        if (label.isEmpty() || !label.equals(AuditSafety.text(label, max))) throw invalid("Revisa el nombre o código.");
        return label;
    }

    static ResponseStatusException denied() {
        return new ResponseStatusException(HttpStatus.FORBIDDEN, "No tienes permiso para esta operación.");
    }

    static ResponseStatusException invalid(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    static ResponseStatusException conflict(String message) {
        return new ResponseStatusException(HttpStatus.CONFLICT, message);
    }
}
