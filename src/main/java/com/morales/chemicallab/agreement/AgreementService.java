package com.morales.chemicallab.agreement;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Service
public class AgreementService {
    public enum Action { ACCEPT, ACKNOWLEDGE, GRANT, DECLINE, WITHDRAW }
    public enum PendingScope { ACCOUNT, INSTITUTION }

    public record Document(long id, String code, int version, String kind, String title, String sha256,
                           String changeSummary, OffsetDateTime publishedAt, Long institutionId,
                           String institutionName, boolean required) {}
    public record Text(Document document, String body) {}
    public record Item(Document document, Long subjectId, String decisionAuthority, Action currentAction) {}
    public record Overview(boolean enabled, boolean globalBlocked, List<Item> items) {}
    public record Decision(long id, long actorId, Long subjectId, Action action, OffsetDateTime recordedAt,
                           Document document) {}
    public record History(List<Decision> decisions, Long nextBefore) {}
    public record Submission(Long subjectId, Action action, UUID requestId) {}

    private static final String DOCUMENT = """
            SELECT v.id AS document_id, s.code, v.version, s.kind, v.title, v.body_sha256,
                   v.change_summary, v.published_at, s.institution_id, i.name AS institution_name, s.required
            """;
    private static final String SERIES = """
            JOIN agreement_runtime.series s ON s.id = v.series_id
            LEFT JOIN evaluation_runtime.institutions i ON i.id = s.institution_id
            """;
    private static final String APPLICABLE_SUBJECTS = """
                SELECT CASE WHEN s.authority = 'INSTITUTION' THEN NULL ELSE :actor END AS subject_id
                WHERE s.institution_id IS NULL OR EXISTS (
                    SELECT 1 FROM agreement_runtime.valid_memberships m
                    WHERE m.account_id = :actor AND m.institution_id = s.institution_id
                      AND (m.institution_active OR s.authority = 'INSTITUTION'))
                UNION ALL
                SELECT g.subject_id FROM agreement_runtime.current_authorities g
                WHERE g.series_id = s.id AND g.actor_id = :actor AND g.authority = 'GUARDIAN'
                  AND (s.institution_id IS NULL OR EXISTS (
                      SELECT 1 FROM agreement_runtime.active_memberships m
                      WHERE m.account_id = g.subject_id AND m.institution_id = s.institution_id))
            """;
    private static final String SUBJECTS = " CROSS JOIN LATERAL (" + APPLICABLE_SUBJECTS + """
            ) target
            LEFT JOIN agreement_runtime.current_authorities grant_row ON grant_row.series_id = s.id
                AND grant_row.actor_id = :actor AND grant_row.subject_id IS NOT DISTINCT FROM target.subject_id
            """;
    private static final String LATEST = """
            LEFT JOIN LATERAL (
                SELECT d.action FROM agreement_runtime.decisions d
                JOIN agreement_runtime.versions signed ON signed.id = d.version_id
                WHERE d.series_id = s.id AND d.subject_id IS NOT DISTINCT FROM target.subject_id
                  AND signed.renewal = v.renewal AND (s.kind <> 'NOTICE' OR signed.id = v.id)
                  AND (s.kind <> 'INSTITUTIONAL' OR grant_row.id IS NULL OR d.actor_id = :actor)
                ORDER BY d.id DESC LIMIT 1
            ) latest ON true
            """;
    private static final String DECISIONS = DOCUMENT + """
            , d.id AS decision_id, d.actor_id, d.subject_id, d.action, d.recorded_at
            FROM agreement_runtime.decisions d JOIN agreement_runtime.versions v ON v.id = d.version_id
            """ + SERIES;
    private static final String EVIDENCE_ACCESS = """
            (d.actor_id = :actor OR d.subject_id = :actor OR (d.subject_id IS NULL AND EXISTS (
                SELECT 1 FROM agreement_runtime.current_authorities g
                JOIN agreement_runtime.active_memberships m ON m.account_id = g.actor_id AND m.institution_id = s.institution_id
                WHERE g.series_id = d.series_id AND g.actor_id = :actor AND g.authority = 'INSTITUTION')))
            """;

    private final JdbcClient sql;
    private final boolean enabled;

    public AgreementService(JdbcClient sql, @Value("${app.agreements.enabled:false}") boolean enabled) {
        this.sql = sql;
        this.enabled = enabled;
    }

    public boolean enabled() { return enabled; }

    public boolean mayActivateInstitution(long institution) {
        if (!enabled) return true;
        return sql.sql("""
                WITH contracts AS (
                    SELECT s.id, v.renewal FROM agreement_runtime.current_versions v
                    JOIN agreement_runtime.series s ON s.id = v.series_id
                    WHERE s.kind = 'INSTITUTIONAL' AND s.required AND s.institution_id = :institution)
                SELECT EXISTS (SELECT 1 FROM contracts) AND NOT EXISTS (
                    SELECT 1 FROM contracts c WHERE NOT EXISTS (
                        SELECT 1 FROM agreement_runtime.decisions d
                        JOIN agreement_runtime.versions signed ON signed.id = d.version_id
                        JOIN agreement_runtime.current_authorities a ON a.series_id = c.id AND a.actor_id = d.actor_id
                        JOIN agreement_runtime.valid_memberships m ON m.account_id = a.actor_id AND m.institution_id = :institution
                        JOIN public.user_accounts u ON u.id = a.actor_id AND u.active
                        WHERE d.series_id = c.id AND d.action = 'ACCEPT' AND signed.renewal = c.renewal
                          AND a.authority = 'INSTITUTION' AND a.subject_id IS NULL))
                """).param("institution", institution).query(Boolean.class).single();
    }

    public boolean academicHold(long actor, Long institution) {
        if (!enabled || institution == null) return false;
        return sql.sql("""
                SELECT EXISTS (
                    SELECT 1 FROM agreement_runtime.current_family_cases c
                    JOIN agreement_runtime.active_memberships m ON m.id = c.membership_id AND m.institution_id = c.institution_id
                    WHERE m.account_id = :actor AND m.institution_id = :institution
                      AND c.purpose = 'ACADEMIC_PARTICIPATION' AND c.result <> 'NOT_REQUIRED'
                      AND (c.result <> 'VERIFIED' OR c.valid_until <= statement_timestamp()))
                """).param("actor", actor).param("institution", institution).query(Boolean.class).single();
    }

    public List<Document> publicDocuments() {
        if (!enabled) return List.of();
        return sql.sql(DOCUMENT + " FROM agreement_runtime.current_versions v " + SERIES + """
                WHERE s.public_read ORDER BY s.code
                """).query((rs, n) -> document(rs)).list();
    }

    public Text publicText(long id) {
        checkEnabled();
        return text(sql.sql(DOCUMENT + " , v.body FROM agreement_runtime.versions v " + SERIES + """
                WHERE v.id = :id AND s.public_read
                """).param("id", id));
    }

    public Text accountText(long actor, long id) {
        checkEnabled();
        return text(sql.sql(DOCUMENT + " , v.body FROM agreement_runtime.versions v " + SERIES + """
                WHERE v.id = :id AND (s.public_read OR s.institution_id IS NULL
                    OR EXISTS (""" + APPLICABLE_SUBJECTS + """
                        ) OR EXISTS (SELECT 1 FROM agreement_runtime.decisions d
                            WHERE d.version_id = v.id AND """ + EVIDENCE_ACCESS + "))")
                .param("actor", actor).param("id", id));
    }

    private Text text(JdbcClient.StatementSpec query) {
        return query.query((rs, n) -> new Text(document(rs), rs.getString("body"))).optional()
                .orElseThrow(() -> failure(HttpStatus.NOT_FOUND, "No se encontró esta versión."));
    }

    public Overview overview(long actor) {
        if (!enabled) return new Overview(false, false, List.of());
        var items = sql.sql(DOCUMENT + """
                , target.subject_id, CASE WHEN s.authority = 'SELF' THEN 'SELF' ELSE grant_row.authority END AS permitted,
                  latest.action
                FROM agreement_runtime.current_versions v
                """ + SERIES + SUBJECTS + LATEST + " ORDER BY s.institution_id NULLS FIRST, s.code, target.subject_id")
                .param("actor", actor).query((rs, n) -> new Item(document(rs),
                        rs.getObject("subject_id", Long.class), rs.getString("permitted"), action(rs))).list();
        boolean globalBlocked = items.stream().anyMatch(item -> item.document().institutionId() == null
                && item.document().required() && Long.valueOf(actor).equals(item.subjectId())
                && item.currentAction() != Action.ACCEPT);
        return new Overview(true, globalBlocked, items);
    }

    public History history(long actor, long before) {
        if (!enabled) return new History(List.of(), null);
        var page = sql.sql(DECISIONS + " WHERE " + EVIDENCE_ACCESS + """
                AND d.id < :before ORDER BY d.id DESC LIMIT 31
                """).param("actor", actor).param("before", before).query(this::decision).list();
        if (page.size() <= 30) return new History(page, null);
        return new History(page.subList(0, 30), page.get(29).id());
    }

    public boolean requiredOutstanding(long actor, Long institution) {
        return pendingScope(actor, institution) != null;
    }

    public PendingScope pendingScope(long actor, Long institution) {
        if (!enabled) return null;
        return sql.sql("""
                    SELECT CASE WHEN s.institution_id IS NULL THEN 'ACCOUNT' ELSE 'INSTITUTION' END
                    FROM agreement_runtime.current_versions v
                    JOIN agreement_runtime.series s ON s.id = v.series_id
                    WHERE s.required AND (s.institution_id IS NULL OR (s.institution_id = :institution
                        AND EXISTS (SELECT 1 FROM agreement_runtime.active_memberships m
                            WHERE m.institution_id = s.institution_id AND m.account_id = :actor)))
                    AND NOT EXISTS (
                        SELECT 1 FROM agreement_runtime.decisions d
                        JOIN agreement_runtime.versions signed ON signed.id = d.version_id
                        WHERE d.series_id = s.id AND signed.renewal = v.renewal AND d.action = 'ACCEPT'
                          AND d.subject_id IS NOT DISTINCT FROM CASE WHEN s.authority = 'INSTITUTION' THEN NULL ELSE :actor END)
                    ORDER BY s.institution_id NULLS FIRST LIMIT 1
                """).param("actor", actor).param("institution", institution, java.sql.Types.BIGINT)
                .query((rs, n) -> PendingScope.valueOf(rs.getString(1))).optional().orElse(null);
    }

    /** Only consumers of the approved purpose should consult this permission. */
    public boolean consentGranted(long account, Long institution, String purpose) {
        if (!enabled) return false;
        return sql.sql("""
                SELECT coalesce((
                    SELECT d.action = 'GRANT' FROM agreement_runtime.current_versions v
                    JOIN agreement_runtime.series s ON s.id = v.series_id
                    JOIN agreement_runtime.versions signed ON signed.series_id = s.id AND signed.renewal = v.renewal
                    JOIN agreement_runtime.decisions d ON d.version_id = signed.id
                    WHERE s.kind = 'CONSENT' AND s.code = :purpose AND d.subject_id = :account
                      AND s.institution_id IS NOT DISTINCT FROM :institution
                      AND (s.institution_id IS NULL OR EXISTS (SELECT 1 FROM agreement_runtime.active_memberships m
                          WHERE m.institution_id = s.institution_id AND m.account_id = :account))
                    ORDER BY d.id DESC LIMIT 1), false)
                """).param("account", account).param("institution", institution, java.sql.Types.BIGINT)
                .param("purpose", purpose).query(Boolean.class).single();
    }

    @Transactional
    public Decision submit(long actor, long documentId, Submission input) {
        checkEnabled();
        if (input.action() == null || input.requestId() == null || (input.subjectId() != null && input.subjectId() < 1)) {
            throw failure(HttpStatus.BAD_REQUEST, "La decisión, el destinatario y la clave de solicitud deben ser válidos.");
        }
        // Serialize replays even when the same key is sent concurrently to different series.
        sql.sql("SELECT true FROM pg_advisory_xact_lock(hashtextextended(:key, 0))")
                .param("key", "agreement:" + actor + ":" + input.requestId()).query(Boolean.class).single();
        var replay = sql.sql(DECISIONS + " WHERE d.actor_id = :actor AND d.request_id = :key")
                .param("actor", actor).param("key", input.requestId()).query(this::decision).optional();
        if (replay.isPresent()) {
            var decision = replay.get();
            if (decision.document().id() != documentId || decision.action() != input.action()
                    || !java.util.Objects.equals(decision.subjectId(), input.subjectId())) {
                throw failure(HttpStatus.CONFLICT, "La clave de solicitud ya corresponde a otra decisión.");
            }
            return decision;
        }
        var series = sql.sql("""
                SELECT s.id FROM agreement_runtime.series s JOIN agreement_runtime.versions v ON v.series_id = s.id
                WHERE v.id = :id FOR UPDATE OF s
                """).param("id", documentId).query(Long.class).optional()
                .orElseThrow(() -> failure(HttpStatus.NOT_FOUND, "No se encontró esta versión."));
        var selection = sql.sql(DOCUMENT + """
                , s.authority, grant_row.id AS authority_id, latest.action
                FROM agreement_runtime.current_versions v
                """ + SERIES + SUBJECTS + LATEST + """
                WHERE s.id = :series AND target.subject_id IS NOT DISTINCT FROM :subject
                """).param("actor", actor).param("series", series).param("subject", input.subjectId(), java.sql.Types.BIGINT)
                .query((rs, n) -> new Selection(document(rs), rs.getString("authority"),
                        rs.getObject("authority_id", Long.class), action(rs))).optional()
                .orElseThrow(() -> failure(HttpStatus.NOT_FOUND, "No se encontró un documento aplicable."));
        if (selection.document().id() != documentId) {
            throw failure(HttpStatus.CONFLICT, "Hay una versión más reciente. Revísala antes de registrar tu decisión.");
        }
        if (!selection.authority().equals("SELF") && selection.authorityId() == null) {
            throw failure(HttpStatus.FORBIDDEN, "Esta decisión requiere una autorización de representación o capacidad verificada.");
        }
        validateAction(selection, input.action());
        var id = sql.sql("""
                INSERT INTO agreement_runtime.decisions(series_id, version_id, actor_id, subject_id, authority_id, action, request_id)
                VALUES (:series, :version, :actor, :subject, :authority, :action, :request) RETURNING id
                """).param("series", series).param("version", documentId).param("actor", actor)
                .param("subject", input.subjectId(), java.sql.Types.BIGINT)
                .param("authority", selection.authorityId(), java.sql.Types.BIGINT)
                .param("action", input.action().name()).param("request", input.requestId()).query(Long.class).single();
        return sql.sql(DECISIONS + " WHERE d.id = :id").param("id", id).query(this::decision).single();
    }

    private record Selection(Document document, String authority, Long authorityId, Action latest) {}

    private static void validateAction(Selection selection, Action action) {
        Action latest = selection.latest();
        boolean allowed = switch (selection.document().kind()) {
            case "TERMS", "INSTITUTIONAL" -> action == Action.ACCEPT && latest != Action.ACCEPT;
            case "NOTICE" -> action == Action.ACKNOWLEDGE && latest != Action.ACKNOWLEDGE;
            case "CONSENT" -> switch (action) {
                case GRANT -> latest != Action.GRANT;
                case DECLINE -> latest == null;
                case WITHDRAW -> latest == Action.GRANT;
                default -> false;
            };
            default -> false;
        };
        if (!allowed) throw failure(HttpStatus.CONFLICT, "La decisión no se aplica al estado o tipo de este documento.");
    }

    private Decision decision(ResultSet rs, int row) throws SQLException {
        return new Decision(rs.getLong("decision_id"), rs.getLong("actor_id"), rs.getObject("subject_id", Long.class),
                action(rs), rs.getObject("recorded_at", OffsetDateTime.class), document(rs));
    }

    private static Document document(ResultSet rs) throws SQLException {
        return new Document(rs.getLong("document_id"), rs.getString("code"), rs.getInt("version"), rs.getString("kind"),
                rs.getString("title"), rs.getString("body_sha256"), rs.getString("change_summary"),
                rs.getObject("published_at", OffsetDateTime.class), rs.getObject("institution_id", Long.class),
                rs.getString("institution_name"), rs.getBoolean("required"));
    }

    private static Action action(ResultSet rs) throws SQLException {
        String value = rs.getString("action");
        return value == null ? null : Action.valueOf(value);
    }

    private void checkEnabled() {
        if (!enabled) throw failure(HttpStatus.NOT_FOUND, "Los acuerdos todavía no están habilitados.");
    }

    private static ResponseStatusException failure(HttpStatus status, String message) {
        return new ResponseStatusException(status, message);
    }
}
