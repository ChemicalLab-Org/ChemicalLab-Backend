package com.morales.chemicallab.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.morales.chemicallab.security.AccountSessionService;
import com.morales.chemicallab.security.SessionPrincipal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.AuthenticationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import java.util.function.Supplier;

@Service
public class EvaluationRuntime {
    static final String SCHEMA = "evaluation_runtime";
    static final String ROLE = """
        m.active and u.active and m.valid_from<=t.at and (m.valid_until is null or t.at<m.valid_until)
        and g.revoked_at is null and g.valid_from<=t.at and (g.valid_until is null or t.at<g.valid_until)
        """;
    static final String EPISODE = "e.ended_at is null and e.valid_from<=t.at and t.at<e.valid_until";
    static final String TEACHING = "a.ended_at is null and a.valid_from<=t.at and t.at<a.valid_until";
    static final String CLASS = "i.active and c.active and p.open and p.valid_from<=t.at and t.at<p.valid_until";
    static final String RIGHTS = """
        with t as (select statement_timestamp() at), rights as (
          select i.id institution_id, i.name institution_name, c.id class_id, c.name||' · '||c.code class_name, m.id member_id,
            exists(select 1 from evaluation_runtime.roles g join evaluation_runtime.enrollments e on e.membership_id=g.membership_id
              where g.membership_id=m.id and g.role='ESTUDIANTE' and e.class_id=c.id and %1$s and %2$s) student,
            exists(select 1 from evaluation_runtime.roles g join evaluation_runtime.teaching a on a.membership_id=g.membership_id
              where g.membership_id=m.id and g.role='DOCENTE' and a.class_id=c.id and %1$s and %3$s) teacher,
            exists(select 1 from evaluation_runtime.roles g
              where g.membership_id=m.id and g.role='DOCENTE' and %1$s) teachingRole,
            exists(select 1 from evaluation_runtime.roles g
              where g.membership_id=m.id and g.role='ADMIN_INSTITUCION' and %1$s) admin
          from evaluation_runtime.memberships m join user_accounts u on u.id=m.account_id
          join evaluation_runtime.institutions i on i.id=m.institution_id
          join evaluation_runtime.classes c on c.institution_id=i.id
          join evaluation_runtime.periods p on p.id=c.period_id
          cross join t where m.account_id=? and %4$s
        )
        """.formatted(ROLE, EPISODE, TEACHING, CLASS);
    record Rights(long membership, boolean student, boolean teacher, boolean teachingRole, boolean admin) {}
    private record Existing(long actor, long institution, long clazz, String action, String hash, String result) {}
    private static final Logger LOG = LoggerFactory.getLogger(EvaluationRuntime.class);
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final AccountSessionService sessions;
    private final TransactionTemplate transaction;
    private volatile Boolean installed;

    public EvaluationRuntime(JdbcTemplate jdbc, ObjectMapper json, AccountSessionService sessions,
                             PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        this.json = json;
        this.sessions = sessions;
        transaction = new TransactionTemplate(manager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        transaction.setTimeout(30);
    }

    public boolean installed() {
        Boolean state=installed;
        if (state!=null) return state;
        state=Boolean.TRUE.equals(jdbc.queryForObject(
            "select to_regnamespace('evaluation_runtime') is not null", Boolean.class));
        if (state) jdbc.queryForObject("select version from evaluation_runtime.contract where version=1",Integer.class);
        // Install the contract with writers stopped, before restarting the application.
        installed=state;
        return state;
    }

    <T> T unit(SessionPrincipal actor, Long institution, Long resource, boolean writing, UUID operation, Supplier<T> work) {
        if (actor == null || actor.temporaryPassword()) throw unauthorized();
        try {
            return transaction.execute(tx -> {
                jdbc.execute("set local lock_timeout='5s'");
                // T02 writers acquire the account before the session. Compatible readers only block
                // revocation of this actor for the duration of the transaction.
                jdbc.queryForList("select id from user_accounts where id=? for share", Long.class, actor.userId());
                jdbc.queryForList("select id from account_sessions where id=? for share", UUID.class, actor.sessionId());
                sessions.revalidateLocked(actor);
                if (institution != null) {
                    jdbc.queryForObject("select pg_advisory_xact_lock_shared(evaluation_runtime.institution_lock(?))",
                        Object.class, institution);
                }
                if (operation != null) {
                    jdbc.queryForObject("select pg_advisory_xact_lock(hashtextextended('chemical-lab/operation/'||?,0))",
                        Object.class, operation.toString());
                }
                if (resource != null) {
                    var roots = jdbc.queryForList("select id from evaluation_runtime.resources where id=? and institution_id=? "
                        + (writing ? "for update" : "for share"), Long.class, resource, institution);
                    if (roots.isEmpty()) throw denied();
                }
                T result = work.get();
                sessions.revalidateLocked(actor);
                return result;
            });
        } catch (AuthenticationException failure) {
            throw unauthorized();
        } catch (DataAccessException | TransactionException failure) {
            if (com.morales.chemicallab.security.storage.StorageErrors.quota(failure)) throw failure;
            String state = sqlState(failure);
            if ("PCL01".equals(state)) throw failure;
            if ("23505".equals(state)) throw conflict();
            LOG.warn("Institutional evaluation transaction rejected sqlState={}", state);
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Operación no disponible.");
        }
    }

    Rights rights(SessionPrincipal actor, long institution, long clazz) {
        var rows = jdbc.query(RIGHTS + """
            select member_id,student,teacher,teachingRole,admin from rights where institution_id=? and class_id=?
            """, (rs, n) -> new Rights(rs.getLong(1),rs.getBoolean(2),rs.getBoolean(3),rs.getBoolean(4),rs.getBoolean(5)),
            actor.userId(), institution, clazz);
        if (rows.size()!=1) throw denied();
        return rows.get(0);
    }

    Instant now() {
        return jdbc.queryForObject("select clock_timestamp()", Timestamp.class).toInstant();
    }

    <T> T replay(SessionPrincipal actor, long institution, long clazz, UUID id, String action,
                 String request, Class<T> resultType) {
        var existing = jdbc.query("""
            select actor_id,institution_id,class_id,action,request_hash,result::text
            from evaluation_runtime.operations where operation_id=?
            """, (rs, n) -> new Existing(rs.getLong(1),rs.getLong(2),rs.getLong(3),rs.getString(4),rs.getString(5),rs.getString(6)), id);
        if (existing.isEmpty()) return null;
        var row = existing.get(0);
        if (row.actor()!=actor.userId() || row.institution()!=institution || row.clazz()!=clazz
                || !row.action().equals(action) || !row.hash().equals(digest(request))) throw conflict();
        if (row.result()==null) throw new IllegalStateException("Unsealed command");
        return decode(row.result(), resultType);
    }

    void begin(SessionPrincipal actor, long institution, long clazz, Long evaluation, UUID id, String action, String request) {
        jdbc.update("""
            insert into evaluation_runtime.operations(operation_id,institution_id,class_id,actor_id,evaluation_id,action,request_hash)
            values(?,?,?,?,?,?,?)
            """, id,institution,clazz,actor.userId(),evaluation,action,digest(request));
        jdbc.queryForObject("select set_config('evaluation_runtime.operation',?,true)", String.class, id.toString());
    }

    void finish(UUID id, long evaluation, Object result, long auditId) {
        if (jdbc.update("""
            update evaluation_runtime.operations
            set result=?::jsonb, evaluation_id=?, audit_id=?, committed_at=clock_timestamp()
            where operation_id=? and result is null
            """, encode(result), evaluation, auditId, id)!=1) throw new IllegalStateException("Command receipt missing");
    }

    String encode(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException(failure);
        }
    }

    <T> T decode(String value, Class<T> type) {
        try {
            return json.readValue(value, type);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException(failure);
        }
    }

    String digest(String value) {
        return jdbc.queryForObject("select encode(sha256(convert_to(?::jsonb::text,'UTF8')),'hex')", String.class, value);
    }

    private static String sqlState(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql) return sql.getSQLState();
        }
        return "unknown";
    }

    static void require(boolean condition) {
        if (!condition) throw denied();
    }
    static ResponseStatusException denied() {
        return new ResponseStatusException(HttpStatus.FORBIDDEN, "Operación no autorizada.");
    }
    static ResponseStatusException conflict() {
        return new ResponseStatusException(HttpStatus.CONFLICT, "Versión u operación incompatible.");
    }
    private static ResponseStatusException unauthorized() {
        return new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Sesión no vigente.");
    }
}
