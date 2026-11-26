package com.morales.chemicallab.service;

import com.morales.chemicallab.dto.InstitutionalContent.*;
import com.morales.chemicallab.dto.MaterialDownload;
import com.morales.chemicallab.entity.LogCategory;
import com.morales.chemicallab.entity.LogEventType;
import com.morales.chemicallab.entity.LogSeverity;
import com.morales.chemicallab.entity.SystemLog;
import com.morales.chemicallab.security.SessionPrincipal;
import com.morales.chemicallab.security.files.FileValidationService;
import com.morales.chemicallab.security.files.ValidatedFile;
import com.morales.chemicallab.service.InstitutionalEvaluationCatalog.Page;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

import static com.morales.chemicallab.service.EvaluationRuntime.*;

@Service
@RequiredArgsConstructor
public class InstitutionalContentService {
    private static final int MAX_PAGE = 25;
    private static final int MAX_RECIPIENTS = 500;
    private static final String PUBLISHED = "PUBLISHED";
    private static final String DRAFT = "DRAFT";
    private static final Set<String> IMAGES = Set.of("image/png", "image/jpeg");
    private static final String ACTIVE_GRANT = """
        x.revoked_at is null and x.valid_from<=statement_timestamp() and statement_timestamp()<x.valid_until
        """;
    private static final String ACCESS = """
        select r.id content_id, v.id revision_id,v.revision_number,v.version,v.status,
          v.title,v.category,
          (? and r.author_membership_id=? and r.origin_class_id=?) or coalesce(g.edit,false) edit,
          (? and r.author_membership_id=? and r.origin_class_id=?) or coalesce(g.publish,false) publish,
          ? and (r.author_membership_id=? or coalesce(g.assign,false)) assign,
          exists(select 1 from content_runtime.deliveries d where d.revision_id=v.id and d.class_id=?) assigned
        from content_runtime.revisions v join content_runtime.resources r on r.id=v.resource_id
        left join lateral (
          select bool_or(x.faculty='EDIT_DRAFT') edit, bool_or(x.faculty='PUBLISH') publish,
            bool_or(x.faculty='ASSIGN') assign
          from content_runtime.faculties x where x.resource_id=r.id and x.membership_id=? and %s
        ) g on true
        where v.institution_id=?
        """.formatted(ACTIVE_GRANT);
    private static final String STUDENT_ACCESS = """
        from content_runtime.recipients a
        join evaluation_runtime.enrollments e on e.id=a.enrollment_id
        join content_runtime.deliveries d on d.id=a.delivery_id
        join content_runtime.revisions v on v.id=d.revision_id
        cross join (select statement_timestamp() at) t
        where a.account_id=? and a.class_id=? and a.institution_id=? and %s
        """.formatted(EPISODE);
    private static final String DETAILS = """
        select v.resource_id,v.id,v.revision_number,v.version,v.status,v.payload::text,
          f.id file_id,f.name,f.title file_title,f.mime,f.size
        from content_runtime.revisions v left join content_runtime.files f on f.id=v.file_id
        where v.id=? and v.resource_id=? and v.institution_id=?
        """;

    public enum Action {
        CREATE(null, LogEventType.CONCEPT_CREATED),
        EDIT("EDIT_DRAFT", LogEventType.CONCEPT_UPDATED),
        PUBLISH("PUBLISH", LogEventType.CONCEPT_PUBLISHED),
        SUCCESSOR("EDIT_DRAFT", LogEventType.CONCEPT_UPDATED),
        ASSIGN("ASSIGN", LogEventType.CONCEPT_ASSIGNED),
        FILE("EDIT_DRAFT", LogEventType.CONCEPT_MATERIAL_ADDED),
        REMOVE_FILE("EDIT_DRAFT", LogEventType.CONCEPT_MATERIAL_REMOVED);

        final String faculty;
        final LogEventType event;

        Action(String faculty, LogEventType event) {
            this.faculty = faculty;
            this.event = event;
        }

        String code() { return "CONTENT_" + name(); }
    }

    private record Existing(long actor, long institution, long clazz, String action, String hash, String result) {}
    private record Fingerprint(Revision revision, Long version, Text text, String fileDigest, String name,
                               String mime, String title) {}

    private final EvaluationRuntime runtime;
    private final JdbcTemplate jdbc;
    private final AuditLogWriter audit;
    private final FileValidationService validator;
    private volatile Boolean installed;

    public boolean installed() {
        Boolean state = installed;
        if (state != null) return state;
        state = Boolean.TRUE.equals(jdbc.queryForObject(
            "select to_regnamespace('content_runtime') is not null", Boolean.class));
        if (state) {
            jdbc.queryForObject("select version from content_runtime.contract where version=1 and evaluation_version=1", Integer.class);
            if (!runtime.installed()) throw new IllegalStateException("Content requires the institutional context");
        }
        installed = state;
        return state;
    }

    public Page<Choice> choices(SessionPrincipal actor, long after, int limit) {
        validatePage(after, limit);
        return unit(actor, null, null, false, null, () -> {
            var rows = jdbc.query(RIGHTS + """
                select institution_id,institution_name,class_id,class_name,student,teacher,
                  teachingRole and exists(select 1 from content_runtime.faculties x
                    join content_runtime.resources r on r.id=x.resource_id
                    where x.membership_id=rights.member_id and r.origin_class_id=rights.class_id and %s
                      and x.faculty in ('EDIT_DRAFT','PUBLISH')) delegate
                from rights where class_id>?
                and (student or teacher or (teachingRole and exists(select 1 from content_runtime.faculties x
                    join content_runtime.resources r on r.id=x.resource_id
                    where x.membership_id=rights.member_id and r.origin_class_id=rights.class_id and %s
                      and x.faculty in ('EDIT_DRAFT','PUBLISH'))))
                order by class_id limit ?
                """.formatted(ACTIVE_GRANT, ACTIVE_GRANT),
                (rs, n) -> new Choice(rs.getLong(1), rs.getString(2), rs.getLong(3), rs.getString(4), rs.getBoolean(5),
                    rs.getBoolean(6) || rs.getBoolean(7), rs.getBoolean(6)), actor.userId(), after, limit + 1);
            return InstitutionalEvaluationCatalog.page(rows, limit, Choice::classId);
        });
    }

    public Page<Row> teacherList(SessionPrincipal actor, Scope scope, long after, int limit) {
        validatePage(after, limit);
        return unit(actor, scope, null, false, null, () -> {
            Rights rights = runtime.rights(actor, scope.institution(), scope.clazz());
            require(rights.teachingRole());
            var rows = jdbc.query("select * from (" + ACCESS + """
                and v.id>? ) accessible
                where (status='DRAFT' and (edit or publish)) or (status='PUBLISHED' and (edit or publish or assign))
                order by revision_id limit ?
                """, (rs, n) -> row(rs), rights.teacher(), rights.membership(), scope.clazz(),
                rights.teacher(), rights.membership(), scope.clazz(), rights.teacher(), rights.membership(),
                scope.clazz(), rights.membership(), scope.institution(), after, limit + 1);
            return InstitutionalEvaluationCatalog.page(rows, limit, Row::revisionId);
        });
    }

    public Page<Delivery> studentList(SessionPrincipal actor, Scope scope, long after, int limit) {
        validatePage(after, limit);
        return unit(actor, scope, null, false, null, () -> {
            require(runtime.rights(actor, scope.institution(), scope.clazz()).student());
            var rows = jdbc.query("""
                select d.id,v.resource_id,v.id,v.revision_number,v.title,v.category
                """ + STUDENT_ACCESS + " and a.delivery_id>? order by a.delivery_id limit ?",
                (rs, n) -> new Delivery(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getInt(4),
                    rs.getString(5), rs.getString(6)), actor.userId(), scope.clazz(), scope.institution(), after, limit + 1);
            return InstitutionalEvaluationCatalog.page(rows, limit, Delivery::deliveryId);
        });
    }

    public Detail detail(SessionPrincipal actor, Scope scope, Revision revision, Long delivery) {
        return unit(actor, scope, revision, false, null, () -> {
            readAccess(actor, scope, revision, delivery);
            return detail(scope, revision);
        });
    }

    public MaterialDownload download(SessionPrincipal actor, Scope scope, Revision revision, Long delivery,
                                     long fileId, boolean preview) {
        return unit(actor, scope, revision, false, null, () -> {
            readAccess(actor, scope, revision, delivery);
            var result = jdbc.query("""
                select f.data,f.mime,f.name
                from content_runtime.files f join content_runtime.revisions v on v.file_id=f.id
                where v.id=? and v.resource_id=? and v.institution_id=? and f.id=?
                  and (not ? or f.mime in ('image/png','image/jpeg'))
                """, (rs, n) -> new MaterialDownload(rs.getBytes(1), rs.getString(2), rs.getString(3), IMAGES.contains(rs.getString(2))),
                revision.revisionId(), revision.contentId(), scope.institution(), fileId, preview);
            return one(result);
        });
    }

    public Receipt upload(SessionPrincipal actor, Scope scope, Revision revision, UUID operation, long version,
                          MultipartFile file, String title) {
        Command command = new Command(operation, version, null);
        unit(actor, scope, revision, false, null, () -> {
            authorize(actor, scope, revision, Action.FILE);
            return null;
        });
        var validated = validator.material(file);
        return write(actor, scope, revision, Action.FILE, command, validated, InstitutionalContentInput.clean(title, 150, false));
    }

    public Receipt write(SessionPrincipal actor, Scope scope, Revision revision, Action action, Command command) {
        return write(actor, scope, revision, action, command, null, null);
    }

    private Receipt write(SessionPrincipal actor, Scope scope, Revision revision, Action action,
                          Command command, ValidatedFile file, String title) {
        if (command.operationId() == null || (revision != null && (command.version() == null || command.version() < 1))) {
            throw InstitutionalContentInput.invalid();
        }
        var key = command.operationId();
        return unit(actor, scope, revision, true, key, () -> {
            Row access = authorize(actor, scope, revision, action);
            var fingerprint = new Fingerprint(revision, command.version(), command.text(), file == null ? null : sha(file.data()),
                file == null ? null : file.fileName(), file == null ? null : file.contentType(), title);
            String hash = runtime.digest(runtime.encode(fingerprint));
            Receipt replay = replay(actor, scope, key, action, hash);
            if (replay != null) return replay;
            if (access != null) validateChange(access, action, command.version());
            Rights rights = runtime.rights(actor, scope.institution(), scope.clazz());
            begin(actor, scope, revision, action, key, hash, rights.membership());

            boolean replacement = action == Action.FILE && currentFile(revision) != null;
            Receipt receipt;
            if (action == Action.CREATE) {
                Revision created = create(scope, rights, actor, command.text());
                receipt = result(key, detail(scope, created), null, null);
            } else if (action == Action.ASSIGN) {
                receipt = assign(scope, revision, access, key);
            } else {
                Revision updated = change(scope, revision, action, command, file, title);
                receipt = result(key, detail(scope, updated), null, null);
            }
            authorize(actor, scope, action == Action.CREATE ? null : revision, action);
            seal(actor, action, key, receipt, replacement);
            return receipt;
        });
    }

    private void readAccess(SessionPrincipal actor, Scope scope, Revision revision, Long delivery) {
        if (delivery != null) {
            require(runtime.rights(actor, scope.institution(), scope.clazz()).student());
            require(Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 " + STUDENT_ACCESS
                + " and a.delivery_id=? and v.resource_id=? and v.id=?)", Boolean.class,
                actor.userId(), scope.clazz(), scope.institution(), delivery, revision.contentId(), revision.revisionId())));
            return;
        }
        Row row = access(actor, scope, revision);
        require(row.canEdit() || (row.status().equals(PUBLISHED) && (row.canPublish() || row.canAssign())));
    }

    private Row authorize(SessionPrincipal actor, Scope scope, Revision revision, Action action) {
        if (action == Action.CREATE) {
            require(runtime.rights(actor, scope.institution(), scope.clazz()).teacher());
            return null;
        }
        Row row = access(actor, scope, revision);
        require(switch (action.faculty) {
            case "EDIT_DRAFT" -> row.canEdit();
            case "PUBLISH" -> row.canPublish();
            case "ASSIGN" -> row.canAssign();
            default -> false;
        });
        return row;
    }

    private Row access(SessionPrincipal actor, Scope scope, Revision revision) {
        Rights rights = runtime.rights(actor, scope.institution(), scope.clazz());
        require(rights.teachingRole());
        return one(jdbc.query(ACCESS + " and r.id=? and v.id=?", (rs, n) -> row(rs),
            rights.teacher(), rights.membership(), scope.clazz(), rights.teacher(), rights.membership(),
            scope.clazz(), rights.teacher(), rights.membership(), scope.clazz(), rights.membership(),
            scope.institution(), revision.contentId(), revision.revisionId()));
    }

    private static Row row(ResultSet rs) throws SQLException {
        return new Row(rs.getLong("content_id"), rs.getLong("revision_id"), rs.getInt("revision_number"),
            rs.getLong("version"), rs.getString("status"), rs.getString("title"), rs.getString("category"),
            rs.getBoolean("edit"), rs.getBoolean("publish"), rs.getBoolean("assign"), rs.getBoolean("assigned"));
    }

    private Detail detail(Scope scope, Revision revision) {
        return one(jdbc.query(DETAILS, (rs, n) -> {
            Text text = runtime.decode(rs.getString(6), Text.class);
            File file = rs.getObject("file_id") == null ? null : readFile(rs);
            return new Detail(rs.getLong(1), rs.getLong(2), rs.getInt(3), rs.getLong(4), rs.getString(5),
                text, file, text.links().size() + (file == null ? 0 : 1));
        }, revision.revisionId(), revision.contentId(), scope.institution()));
    }

    private static File readFile(ResultSet rs) throws SQLException {
        String mime = rs.getString("mime");
        return new File(rs.getLong("file_id"), rs.getString("name"), rs.getString("file_title"), mime,
            rs.getInt("size"), IMAGES.contains(mime));
    }

    private Revision create(Scope scope, Rights rights, SessionPrincipal actor, Text text) {
        long content = jdbc.queryForObject("""
            insert into content_runtime.resources(institution_id,origin_class_id,author_account_id,author_membership_id)
            values(?,?,?,?) returning id
            """, Long.class, scope.institution(), scope.clazz(), actor.userId(), rights.membership());
        jdbc.update("update content_runtime.operations set resource_id=? where operation_id="
            + "current_setting('content_runtime.operation')::uuid", content);
        return insertRevision(scope, content, null, 1, runtime.encode(text), null);
    }

    private Revision insertRevision(Scope scope, long content, Long parent, int number, String payload, Long file) {
        long revision = jdbc.queryForObject("""
            insert into content_runtime.revisions(resource_id,institution_id,parent_id,revision_number,payload,file_id)
            values(?,?,?,?,?::jsonb,?) returning id
            """, Long.class, content, scope.institution(), parent, number, payload, file);
        return new Revision(content, revision);
    }

    private static void validateChange(Row row, Action action, long version) {
        if (row.version() != version) throw conflict();
        String required = action == Action.SUCCESSOR || action == Action.ASSIGN ? PUBLISHED : DRAFT;
        if (!row.status().equals(required)) throw conflict();
    }

    private Revision change(Scope scope, Revision revision, Action action, Command command, ValidatedFile file, String title) {
        long id = revision.revisionId();
        switch (action) {
            case EDIT -> jdbc.update("update content_runtime.revisions set payload=?::jsonb,version=version+1 where id=?",
                runtime.encode(command.text()), id);
            case PUBLISH -> {
                Detail draft = detail(scope, revision);
                if (draft.file() == null && !draft.text().hasBody()) throw InstitutionalContentInput.invalid();
                jdbc.update("""
                    update content_runtime.revisions set status='PUBLISHED',published_at=clock_timestamp(),version=version+1
                    where id=?
                    """, id);
            }
            case SUCCESSOR -> {
                Detail parent = detail(scope, revision);
                Long fileId = parent.file() == null ? null : parent.file().id();
                return insertRevision(scope, revision.contentId(), id, parent.number() + 1, runtime.encode(parent.text()), fileId);
            }
            case FILE, REMOVE_FILE -> changeFile(scope, revision, file, title);
            default -> throw new IllegalStateException("Unexpected content action");
        }
        return revision;
    }

    private void changeFile(Scope scope, Revision revision, ValidatedFile file, String title) {
        long id = revision.revisionId();
        Long previous = currentFile(revision);
        if (previous != null) jdbc.update("""
            delete from content_runtime.files f where f.id=?
            and not exists(select 1 from content_runtime.revisions v where v.file_id=f.id and v.id<>?)
            """, previous, id);
        Long next = null;
        if (file != null) {
            next = jdbc.queryForObject("""
                insert into content_runtime.files(resource_id,institution_id,name,title,mime,data)
                values(?,?,?,?,?,?) returning id
                """, Long.class, revision.contentId(), scope.institution(), file.fileName(), title, file.contentType(), file.data());
        }
        jdbc.update("update content_runtime.revisions set file_id=?,version=version+1 where id=?", next, id);
    }

    private Long currentFile(Revision revision) {
        return jdbc.queryForObject("select file_id from content_runtime.revisions where id=?", Long.class, revision.revisionId());
    }

    private Receipt assign(Scope scope, Revision revision, Row row, UUID key) {
        long delivery = jdbc.queryForObject("""
            insert into content_runtime.deliveries(resource_id,revision_id,institution_id,class_id)
            values(?,?,?,?) returning id
            """, Long.class, revision.contentId(), revision.revisionId(), scope.institution(), scope.clazz());
        int count = jdbc.update("""
            insert into content_runtime.recipients(delivery_id,resource_id,institution_id,class_id,enrollment_id,account_id)
            select ?,?,e.institution_id,e.class_id,e.id,e.account_id
            from evaluation_runtime.enrollments e join evaluation_runtime.memberships m on m.id=e.membership_id
            join user_accounts u on u.id=m.account_id
            join evaluation_runtime.roles g on g.membership_id=m.id and g.role='ESTUDIANTE'
            cross join (select statement_timestamp() at) t
            where e.institution_id=? and e.class_id=? and %s and %s
            order by e.id limit ?
            """.formatted(ROLE, EPISODE), delivery, revision.contentId(), scope.institution(), scope.clazz(), MAX_RECIPIENTS + 1);
        if (count == 0 || count > MAX_RECIPIENTS) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
            "La clase debe tener entre 1 y 500 matrículas vigentes.");
        return new Receipt(key, revision.contentId(), revision.revisionId(), row.number(), row.version(),
            row.status(), delivery, count, null);
    }

    private Receipt replay(SessionPrincipal actor, Scope scope, UUID key, Action action, String hash) {
        var rows = jdbc.query("""
            select actor_id,institution_id,class_id,action,request_hash,result::text
            from content_runtime.operations where operation_id=?
            """, (rs, n) -> new Existing(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getString(4),
                rs.getString(5), rs.getString(6)), key);
        if (rows.isEmpty()) return null;
        Existing row = rows.get(0);
        if (row.actor() != actor.userId() || row.institution() != scope.institution() || row.clazz() != scope.clazz()
            || !row.action().equals(action.code()) || !row.hash().equals(hash)) throw conflict();
        return runtime.decode(row.result(), Receipt.class);
    }

    private void begin(SessionPrincipal actor, Scope scope, Revision revision, Action action, UUID key,
                       String hash, long membership) {
        jdbc.update("""
            insert into content_runtime.operations(operation_id,institution_id,class_id,actor_id,membership_id,
                resource_id,action,request_hash) values(?,?,?,?,?,?,?,?)
            """, key, scope.institution(), scope.clazz(), actor.userId(), membership,
            revision == null ? null : revision.contentId(), action.code(), hash);
        jdbc.queryForObject("select set_config('content_runtime.operation',?,true)", String.class, key.toString());
    }

    private void seal(SessionPrincipal actor, Action action, UUID key, Receipt receipt, boolean replacement) {
        var event = SystemLog.builder().category(LogCategory.CONCEPT_CONTENT).eventType(replacement ? LogEventType.CONCEPT_MATERIAL_REPLACED : action.event)
            .severity(LogSeverity.INFO).actorUserId(actor.userId()).actorRole(actor.role()).targetType("ConceptContent")
            .targetId(receipt.contentId()).action(action.code()).description(action.code()).outcome("SUCCESS")
            .correlationId(key).build();
        audit.persist(event);
        jdbc.execute("set local synchronous_commit=on");
        if (jdbc.update("""
            update content_runtime.operations set result=?::jsonb,audit_id=?,committed_at=clock_timestamp(),resource_id=?
            where operation_id=? and result is null
            """, runtime.encode(receipt), event.getId(), receipt.contentId(), key) != 1) {
            throw new IllegalStateException("Content receipt missing");
        }
    }

    private <T> T unit(SessionPrincipal actor, Scope scope, Revision revision, boolean writing, UUID operation, Supplier<T> work) {
        if (!installed()) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        return runtime.unit(actor, scope == null ? null : scope.institution(), null, writing, operation, () -> {
            if (revision != null) require(!jdbc.queryForList(
                "select id from content_runtime.resources where id=? and institution_id=? " + (writing ? "for update" : "for share"),
                Long.class, revision.contentId(), scope.institution()).isEmpty());
            return work.get();
        });
    }

    private static Receipt result(UUID key, Detail detail, Long delivery, Integer recipients) {
        return new Receipt(key, detail.contentId(), detail.revisionId(), detail.number(), detail.version(),
            detail.status(), delivery, recipients, detail.file());
    }

    private static <T> T one(List<T> rows) {
        if (rows.size() != 1) throw denied();
        return rows.get(0);
    }

    private static void validatePage(long after, int limit) {
        if (after < 0 || limit < 1 || limit > MAX_PAGE) throw InstitutionalContentInput.invalid();
    }

    private static String sha(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException(failure);
        }
    }
}
