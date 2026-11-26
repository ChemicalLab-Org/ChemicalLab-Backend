package com.morales.chemicallab.agreement;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Service
public class AgreementReviews {
    public enum DirectorResult { VERIFIED, DENIED, REVOKED }
    public enum FamilyResult { PENDING, VERIFIED, DENIED, WITHDRAWN, NOT_REQUIRED }
    public enum FamilyBasis { PARENT_OR_GUARDIAN, CAPABLE_ADOLESCENT }

    public record DirectorInput(long documentId, DirectorResult result, String evidenceReference,
                                OffsetDateTime validUntil, UUID requestId) {}
    public record FamilyInput(FamilyResult result, FamilyBasis basis, String evidenceReference,
                              OffsetDateTime validUntil, UUID requestId) {}
    public record Review(long id, long reviewerId, String result, String basis, String evidenceReference,
                         OffsetDateTime validUntil, OffsetDateTime recordedAt) {}
    public record School(long id, String name) {}
    public record Permissions(boolean platform, List<School> schools) {}
    public record FamilyStatus(long institutionId, String institutionName, String purpose, String status,
                               OffsetDateTime validUntil) {}

    private static final String SCHOOL_ADMINS = """
            SELECT m.institution_id FROM agreement_runtime.active_memberships m
            JOIN evaluation_runtime.roles r ON r.institution_id = m.institution_id AND r.membership_id = m.id
            WHERE m.account_id = :actor AND r.role = 'ADMIN_INSTITUCION' AND r.revoked_at IS NULL
              AND r.valid_from <= statement_timestamp() AND (r.valid_until IS NULL OR r.valid_until > statement_timestamp())
            """;
    private static final String DIRECTOR_HISTORY = """
            SELECT id, verified_by AS reviewer_id, result, authority AS basis, evidence_reference, valid_until, recorded_at
            FROM agreement_runtime.authorities WHERE series_id = :scope AND actor_id = :subject AND authority = 'INSTITUTION'
            """;
    private static final String FAMILY_HISTORY = """
            SELECT id, recorded_by AS reviewer_id, result, basis, evidence_reference, valid_until, recorded_at
            FROM agreement_runtime.family_cases WHERE institution_id = :scope AND membership_id = :subject
            """;

    private final JdbcClient sql;
    private final AgreementService agreements;

    public AgreementReviews(JdbcClient sql, AgreementService agreements) {
        this.sql = sql;
        this.agreements = agreements;
    }

    public Permissions permissions(long actor) {
        if (!agreements.enabled()) return new Permissions(false, List.of());
        var schools = sql.sql("SELECT id, name FROM evaluation_runtime.institutions WHERE id IN ("
                + SCHOOL_ADMINS + ") ORDER BY name, id").param("actor", actor)
                .query((rs, row) -> new School(rs.getLong("id"), rs.getString("name"))).list();
        return new Permissions(isPlatform(actor), schools);
    }

    public List<FamilyStatus> familyStatus(long actor) {
        if (!agreements.enabled()) return List.of();
        return sql.sql("""
                SELECT c.institution_id, i.name, c.purpose, c.valid_until,
                  CASE WHEN c.result = 'VERIFIED' AND c.valid_until <= statement_timestamp()
                       THEN 'EXPIRED' ELSE c.result END AS status
                FROM agreement_runtime.current_family_cases c
                JOIN evaluation_runtime.memberships m ON m.id = c.membership_id
                JOIN evaluation_runtime.institutions i ON i.id = c.institution_id
                WHERE m.account_id = :actor ORDER BY c.institution_id, c.purpose
                """).param("actor", actor).query((rs, row) -> new FamilyStatus(rs.getLong("institution_id"),
                rs.getString("name"), rs.getString("purpose"), rs.getString("status"),
                rs.getObject("valid_until", OffsetDateTime.class))).list();
    }

    @Transactional
    public Review director(long actor, long institution, long membership, DirectorInput input) {
        requireReviewer(actor, institution, true);
        validate(input.result(), input.evidenceReference(), input.validUntil(), input.requestId());
        lockInstitution(institution);
        long subject = member(institution, membership);
        if (subject == actor) throw failure(HttpStatus.FORBIDDEN, "La revisión requiere otra cuenta.");
        long series = directorSeries(institution, input.documentId());
        // Serialize reviews before PostgreSQL allocates a new history ID.
        sql.sql("SELECT id FROM agreement_runtime.series WHERE id = :id FOR UPDATE")
                .param("id", series).query(Long.class).single();
        if (recorded("authorities", "verified_by", actor, input.requestId())) {
            return replay(DIRECTOR_HISTORY, series, subject, "verified_by", actor,
                    input.result().name(), "INSTITUTION", input.evidenceReference(), input.validUntil(), input.requestId());
        }
        checkExpiry(input.validUntil());
        var saved = sql.sql("""
                INSERT INTO agreement_runtime.authorities(series_id, actor_id, authority, result, valid_until,
                                                          verified_by, evidence_reference, request_id)
                VALUES (:scope, :subject, 'INSTITUTION', :result, :until, :actor, :reference, :request)
                ON CONFLICT (verified_by, request_id) DO NOTHING RETURNING id
                """).param("scope", series).param("subject", subject).param("result", input.result().name())
                .param("until", input.validUntil(), Types.TIMESTAMP_WITH_TIMEZONE).param("actor", actor)
                .param("reference", input.evidenceReference()).param("request", input.requestId()).query(Long.class).optional();
        if (saved.isPresent()) return entry(DIRECTOR_HISTORY, series, subject, saved.get());
        return replay(DIRECTOR_HISTORY, series, subject, "verified_by", actor,
                input.result().name(), "INSTITUTION", input.evidenceReference(), input.validUntil(), input.requestId());
    }

    @Transactional
    public Review family(long actor, long institution, long membership, FamilyInput input) {
        if (agreements.enabled()) lockInstitution(institution);
        requireReviewer(actor, institution, false);
        validate(input.result(), input.evidenceReference(), input.validUntil(), input.requestId());
        long subject = sql.sql("""
                SELECT account_id FROM evaluation_runtime.memberships WHERE id = :id AND institution_id = :institution FOR UPDATE
                """).param("id", membership).param("institution", institution).query(Long.class).optional()
                .orElseThrow(() -> failure(HttpStatus.NOT_FOUND, "No se encontró la pertenencia de este colegio."));
        if (subject == actor) throw failure(HttpStatus.FORBIDDEN, "La revisión requiere otra cuenta.");
        if ((input.result() == FamilyResult.VERIFIED) != (input.basis() != null)) {
            throw failure(HttpStatus.BAD_REQUEST, "Indica de quién procede la autorización verificada.");
        }
        if (input.result() == FamilyResult.NOT_REQUIRED && !isPlatform(actor)) {
            throw failure(HttpStatus.FORBIDDEN, "ChemicalLab debe revisar el cambio de fundamento informado por el colegio.");
        }
        var basis = input.basis() == null ? null : input.basis().name();
        if (recorded("family_cases", "recorded_by", actor, input.requestId())) {
            return replay(FAMILY_HISTORY, institution, membership, "recorded_by", actor,
                    input.result().name(), basis, input.evidenceReference(), input.validUntil(), input.requestId());
        }
        checkExpiry(input.validUntil());
        var saved = sql.sql("""
                INSERT INTO agreement_runtime.family_cases(institution_id, membership_id, purpose, result, basis,
                                                           valid_until, recorded_by, evidence_reference, request_id)
                VALUES (:scope, :subject, 'ACADEMIC_PARTICIPATION', :result, :basis, :until, :actor, :reference, :request)
                ON CONFLICT (recorded_by, request_id) DO NOTHING RETURNING id
                """).param("scope", institution).param("subject", membership).param("result", input.result().name())
                .param("basis", basis, Types.VARCHAR).param("until", input.validUntil(), Types.TIMESTAMP_WITH_TIMEZONE)
                .param("actor", actor).param("reference", input.evidenceReference()).param("request", input.requestId())
                .query(Long.class).optional();
        if (saved.isPresent()) return entry(FAMILY_HISTORY, institution, membership, saved.get());
        return replay(FAMILY_HISTORY, institution, membership, "recorded_by", actor,
                input.result().name(), basis, input.evidenceReference(), input.validUntil(), input.requestId());
    }

    public List<Review> directorHistory(long actor, long institution, long membership, long document, long before) {
        requireReviewer(actor, institution, true);
        return history(DIRECTOR_HISTORY, directorSeries(institution, document), member(institution, membership), before);
    }

    public List<Review> familyHistory(long actor, long institution, long membership, long before) {
        requireReviewer(actor, institution, false);
        member(institution, membership);
        return history(FAMILY_HISTORY, institution, membership, before);
    }

    private List<Review> history(String query, long scope, long subject, long before) {
        return sql.sql(query + " AND id < :before ORDER BY id DESC LIMIT 30").param("scope", scope)
                .param("subject", subject).param("before", before).query(AgreementReviews::read).list();
    }

    private Review entry(String query, long scope, long subject, long id) {
        return sql.sql(query + " AND id = :id").param("scope", scope).param("subject", subject).param("id", id)
                .query(AgreementReviews::read).single();
    }

    private Review replay(String query, long scope, long subject, String actorColumn, long actor, String result,
                          String basis, String reference, OffsetDateTime until, UUID requestId) {
        return sql.sql(query + " AND " + actorColumn + " = :actor AND request_id = :request AND result = :result"
                        + " AND evidence_reference = :reference AND valid_until IS NOT DISTINCT FROM :until"
                        + (query.equals(FAMILY_HISTORY) ? " AND basis IS NOT DISTINCT FROM :basis" : " AND authority = :basis"))
                .param("scope", scope).param("subject", subject).param("actor", actor).param("request", requestId)
                .param("result", result).param("reference", reference).param("basis", basis, Types.VARCHAR)
                .param("until", until, Types.TIMESTAMP_WITH_TIMEZONE).query(AgreementReviews::read).optional()
                .orElseThrow(() -> failure(HttpStatus.CONFLICT, "La clave ya corresponde a otra revisión."));
    }

    private boolean recorded(String table, String column, long actor, UUID requestId) {
        return sql.sql("SELECT EXISTS (SELECT 1 FROM agreement_runtime." + table + " WHERE " + column
                        + " = :actor AND request_id = :request)")
                .param("actor", actor).param("request", requestId).query(Boolean.class).single();
    }

    private static void checkExpiry(OffsetDateTime until) {
        if (until != null && !until.isAfter(OffsetDateTime.now())) {
            throw failure(HttpStatus.BAD_REQUEST, "La fecha de vigencia debe estar en el futuro.");
        }
    }

    private void requireReviewer(long actor, long institution, boolean platformOnly) {
        if (!agreements.enabled()) throw failure(HttpStatus.NOT_FOUND, "Los acuerdos todavía no están habilitados.");
        if (isPlatform(actor)) return;
        if (!platformOnly && sql.sql("SELECT :institution IN (" + SCHOOL_ADMINS + ")").param("actor", actor)
                .param("institution", institution).query(Boolean.class).single()) return;
        throw failure(HttpStatus.FORBIDDEN, "La cuenta carece de facultades para esta revisión.");
    }

    private boolean isPlatform(long actor) {
        return sql.sql("""
                SELECT EXISTS (SELECT 1 FROM administration_runtime.platform_authority p
                JOIN public.user_accounts u ON u.id = p.account_id
                WHERE p.account_id = :actor AND u.active AND u.role = 'ADMINISTRADOR')
                """)
                .param("actor", actor).query(Boolean.class).single();
    }

    private void lockInstitution(long institution) {
        // Administration writes take the exclusive form of this same lock.
        sql.sql("SELECT true FROM pg_advisory_xact_lock_shared(evaluation_runtime.institution_lock(:institution))")
                .param("institution", institution).query(Boolean.class).single();
    }

    private long member(long institution, long membership) {
        return sql.sql("SELECT account_id FROM evaluation_runtime.memberships WHERE id = :id AND institution_id = :institution")
                .param("id", membership).param("institution", institution).query(Long.class).optional()
                .orElseThrow(() -> failure(HttpStatus.NOT_FOUND, "No se encontró la pertenencia de este colegio."));
    }

    private long directorSeries(long institution, long document) {
        return sql.sql("""
                SELECT s.id FROM agreement_runtime.versions v JOIN agreement_runtime.series s ON s.id = v.series_id
                WHERE v.id = :document AND s.institution_id = :institution AND s.kind = 'INSTITUTIONAL'
                """).param("document", document).param("institution", institution).query(Long.class).optional()
                .orElseThrow(() -> failure(HttpStatus.NOT_FOUND, "No se encontró un acuerdo para este colegio."));
    }

    private static void validate(Enum<?> result, String reference, OffsetDateTime until, UUID request) {
        if (result == null || request == null || reference == null || !reference.matches("[A-Z0-9][A-Z0-9_-]{5,63}")) {
            throw failure(HttpStatus.BAD_REQUEST, "Indica el resultado, la clave de solicitud y una referencia interna válida.");
        }
        if (result.name().equals("VERIFIED") != (until != null)) {
            throw failure(HttpStatus.BAD_REQUEST, "Indica una vigencia únicamente para la verificación favorable.");
        }
    }

    private static Review read(ResultSet rs, int row) throws SQLException {
        return new Review(rs.getLong("id"), rs.getLong("reviewer_id"), rs.getString("result"), rs.getString("basis"),
                rs.getString("evidence_reference"), rs.getObject("valid_until", OffsetDateTime.class),
                rs.getObject("recorded_at", OffsetDateTime.class));
    }

    private static ResponseStatusException failure(HttpStatus status, String message) {
        return new ResponseStatusException(status, message);
    }
}
