package com.morales.chemicallab.service;

import com.morales.chemicallab.dto.*;
import com.morales.chemicallab.entity.*;
import com.morales.chemicallab.repository.*;
import com.morales.chemicallab.security.SessionPrincipal;
import com.morales.chemicallab.security.WhiteboardScope;
import com.morales.chemicallab.security.WhiteboardScope.Target;
import com.morales.chemicallab.validation.InputValidation;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.function.LongFunction;

import static com.morales.chemicallab.dto.WhiteboardControlEventType.*;
import static com.morales.chemicallab.entity.WhiteboardSessionStatus.*;
import static com.morales.chemicallab.service.EvaluationRuntime.*;

@Service
@RequiredArgsConstructor
public class InstitutionalWhiteboardService {
    public enum Action {
        CREATE(LogEventType.WHITEBOARD_SESSION_CREATED), JOIN(LogEventType.WHITEBOARD_SESSION_JOINED),
        DRAW(LogEventType.WHITEBOARD_DRAW_COMMITTED), PAUSE(LogEventType.WHITEBOARD_SESSION_PAUSED),
        RESUME(LogEventType.WHITEBOARD_SESSION_RESUMED), CLOSE(LogEventType.WHITEBOARD_SESSION_CLOSED),
        INTERACTION(LogEventType.WHITEBOARD_INTERACTION_UPDATED),
        PARTICIPANT(LogEventType.WHITEBOARD_PARTICIPANT_INTERACTION_UPDATED);
        private final LogEventType event;
        Action(LogEventType event) { this.event = event; }
    }

    private record Receipt(long actor, long institution, long clazz, long board, String action, String hash) {}
    public record LiveState(long sessionId, WhiteboardSessionStatus status, String stateJson,
                            LocalDateTime updatedAt, long revision, boolean interactionEnabled, Boolean canInteract) {}
    private final JdbcTemplate jdbc;
    private final EntityManager entityManager;
    private final EvaluationRuntime runtime;
    private final InstitutionalWhiteboardAccess access;
    private final TeacherProfileRepository teachers;
    private final StudentProfileRepository students;
    private final WhiteboardSessionRepository boards;
    private final WhiteboardParticipantRepository participants;
    private final WhiteboardQueryRepository queries;
    private final WhiteboardSessionService projection;
    private final WhiteboardDrawEventService drawing;
    private final WhiteboardBroadcastService broadcast;
    private final AuditLogWriter audit;

    public QueryPage<WhiteboardSessionResponse> teacherBoards(SessionPrincipal actor, WhiteboardScope scope, int page, int size) {
        require(scope.teacher());
        return unit(actor, scope, null, account -> queries.scopedTeacher(scope.institution(), scope.clazz(), page, size));
    }

    public QueryPage<WhiteboardStudentSessionResponse> liveBoards(SessionPrincipal actor, WhiteboardScope scope, int page, int size) {
        require(!scope.teacher());
        return unit(actor, scope, null, account -> queries.scopedActive(scope.institution(), scope.clazz(), actor.userId(), page, size));
    }

    public QueryPage<WhiteboardHistoryItemResponse> history(SessionPrincipal actor, WhiteboardScope scope, int page, int size) {
        require(!scope.teacher());
        return unit(actor, scope, null, account -> queries.scopedHistory(scope.institution(), scope.clazz(), actor.userId(), page, size));
    }

    public WhiteboardSessionResponse create(SessionPrincipal actor, WhiteboardScope scope, UUID id,
            String title, String description) {
        require(scope.teacher());
        String name = InputValidation.requireWhiteboardTitle(title);
        String notes = description == null ? "" : description.trim();
        if (notes.length() > 2000) throw new IllegalArgumentException("La descripción supera el límite permitido.");
        return unit(actor, scope, id, membership -> {
            String hash = hash(scope, List.of(name, notes));
            Receipt existing = replay(actor, scope, id, null, Action.CREATE, hash);
            if (existing != null) return summary(board(new Target(scope, existing.board()), false));
            var teacher = teachers.findByUser_Id(actor.userId()).orElseThrow(EvaluationRuntime::denied);
            String code = jdbc.queryForObject("select code from evaluation_runtime.classes where id=? and institution_id=?",
                String.class, scope.clazz(), scope.institution());
            begin(actor, scope, membership, id, null, Action.CREATE, hash);
            var board = boards.save(WhiteboardSession.builder().teacher(teacher).name(name)
                .description(notes.isEmpty() ? null : notes).grade("").section(code).build());
            jdbc.update("insert into board_runtime.boards(board_id,institution_id,class_id,author_id,membership_id) values(?,?,?,?,?)",
                board.getId(), scope.institution(), scope.clazz(), actor.userId(), membership);
            seal(actor, scope, id, board, Action.CREATE);
            return summary(board);
        });
    }

    public WhiteboardSessionDetailResponse teacherDetail(SessionPrincipal actor, Target target) {
        var scope = target.scope();
        require(scope.teacher());
        return unit(actor, scope, null, account -> {
            var board = queries.scopedTeacherHeader(scope.institution(), scope.clazz(), target.board())
                .orElseThrow(EvaluationRuntime::denied);
            var rows = queries.currentParticipants(board.id(), board.grade(), board.section(), board.interactionEnabled());
            return new WhiteboardSessionDetailResponse(board.withParticipantCount(rows.size()), rows);
        });
    }

    public WhiteboardStudentSessionResponse studentDetail(SessionPrincipal actor, Target target) {
        require(!target.scope().teacher());
        return unit(actor, target.scope(), null, account -> {
            var board = board(target, false);
            var participant = participant(board, actor.userId());
            require(board.getStatus() != CLOSED || participant != null);
            return projection.toStudentResponse(board, participant);
        });
    }

    public LiveState state(SessionPrincipal actor, Target target) {
        return unit(actor, target.scope(), null, member -> {
            var board = board(target, false);
            Boolean canInteract = null;
            if (!target.scope().teacher()) {
                var participant = participant(board, actor.userId());
                require(board.getStatus() != CLOSED && participant != null);
                canInteract = board.getStatus() == ACTIVE && WhiteboardInteractionPolicy.effective(
                    board.getInteractionEnabled(), participant.getInteractionOverride());
            }
            var state = projection.toBoardState(board);
            return new LiveState(board.getId(), board.getStatus(), state.stateJson(), board.getStateUpdatedAt(),
                board.getStateRevision(), board.getInteractionEnabled(), canInteract);
        });
    }

    public WhiteboardSnapshotDownload snapshot(SessionPrincipal actor, Target target) {
        return unit(actor, target.scope(), null, member -> {
            var board = board(target, false);
            require(board.getStatus() == CLOSED);
            if (!target.scope().teacher()) require(participant(board, actor.userId()) != null);
            return projection.requireSnapshot(board);
        });
    }

    public WhiteboardStudentSessionResponse join(SessionPrincipal actor, Target target, UUID id) {
        var scope = target.scope();
        require(!scope.teacher());
        return unit(actor, scope, id, membership -> {
            var board = board(target, true);
            require(board.getStatus() != CLOSED);
            String hash = hash(scope, target.board());
            var participant = participant(board, actor.userId());
            if (replay(actor, scope, id, target.board(), Action.JOIN, hash) != null) {
                require(participant != null);
                return projection.toStudentResponse(board, participant);
            }
            // An ended episode retains its own participant row. A new episode cannot inherit it.
            if (participant == null) {
                require(jdbc.queryForList("select 1 from board_runtime.admissions where board_id=? and account_id=?",
                    Integer.class, board.getId(), actor.userId()).isEmpty());
                var episodes = jdbc.queryForList("""
                    select e.id from evaluation_runtime.enrollments e cross join (select statement_timestamp() at) t
                    where e.institution_id=? and e.class_id=? and e.membership_id=? and e.account_id=? and
                    """ + EPISODE, Long.class, scope.institution(), scope.clazz(), membership, actor.userId());
                require(episodes.size() == 1);
                if (actor.role() == Role.INSTITUCIONAL) {
                    // Satisfy the T03 participant FK on first use. Access always follows the scoped episode.
                    jdbc.update("""
                        insert into student_profiles(user_id,teacher_id,student_code,names,last_names,grade,section)
                        values(?,?,'I-'||?,'Participante','#'||?,'','')
                        on conflict(user_id) do nothing
                        """, actor.userId(), board.getTeacher().getId(), actor.userId(), actor.userId());
                }
                var student = students.findByUser_Id(actor.userId()).orElseThrow(EvaluationRuntime::denied);
                begin(actor, scope, membership, id, target.board(), Action.JOIN, hash);
                participant = participants.save(WhiteboardParticipant.builder().session(board).student(student).build());
                jdbc.update("""
                    insert into board_runtime.admissions(participant_id,board_id,institution_id,class_id,enrollment_id,account_id)
                    values(?,?,?,?,?,?)
                    """, participant.getId(), board.getId(), scope.institution(), scope.clazz(), episodes.get(0), actor.userId());
                seal(actor, scope, id, board, Action.JOIN);
                broadcast.send(target.topic(), projection.controlEvent(board, PARTICIPANT_JOINED, null), 0L);
            }
            return projection.toStudentResponse(board, participant);
        });
    }

    public WhiteboardSessionResponse change(SessionPrincipal actor, Target target, UUID id, Action action, Boolean enabled) {
        var scope = target.scope();
        require(scope.teacher());
        return unit(actor, scope, id, membership -> {
            var board = board(target, true);
            String hash = hash(scope, List.of(target.board(), Boolean.TRUE.equals(enabled)));
            if (replay(actor, scope, id, target.board(), action, hash) != null) return summary(board);
            require(board.getStatus() != CLOSED);
            begin(actor, scope, membership, id, target.board(), action, hash);
            WhiteboardControlEventType type = switch (action) {
                case PAUSE -> {
                    if (board.getStatus() != ACTIVE) throw conflict();
                    board.setStatus(PAUSED);
                    board.setPausedAt(LocalDateTime.now());
                    yield SESSION_PAUSED;
                }
                case RESUME -> {
                    if (board.getStatus() != PAUSED) throw conflict();
                    board.setStatus(ACTIVE);
                    board.setResumedAt(LocalDateTime.now());
                    yield SESSION_RESUMED;
                }
                case INTERACTION -> {
                    if (enabled == null) throw new IllegalArgumentException("Indica el permiso de interacción.");
                    board.setInteractionEnabled(enabled);
                    yield INTERACTION_UPDATED;
                }
                default -> throw new IllegalArgumentException("Acción de pizarra inválida.");
            };
            seal(actor, scope, id, board, action);
            control(target, board, type, null);
            return summary(board);
        });
    }

    public WhiteboardParticipantResponse permission(SessionPrincipal actor, Target target, UUID id, long student,
            WhiteboardInteractionOverride override) {
        var scope = target.scope();
        require(scope.teacher() && override != null);
        return unit(actor, scope, id, membership -> {
            var board = board(target, true);
            var rows = jdbc.queryForList("""
                select p.id from board_runtime.current_admissions a
                join whiteboard_participants p on p.id=a.participant_id
                where a.board_id=? and p.student_id=?
                """, Long.class, board.getId(), student);
            require(rows.size() == 1);
            var participant = participants.findById(rows.get(0)).orElseThrow(EvaluationRuntime::denied);
            String hash = hash(scope, List.of(target.board(), student, override));
            if (replay(actor, scope, id, target.board(), Action.PARTICIPANT, hash) == null) {
                require(board.getStatus() != CLOSED);
                begin(actor, scope, membership, id, target.board(), Action.PARTICIPANT, hash);
                participant.setInteractionOverride(override);
                seal(actor, scope, id, board, Action.PARTICIPANT);
                broadcast.send(target.topic(), projection.controlEvent(board, PARTICIPANT_PERMISSION_UPDATED, student),
                    participant.getStudent().getUser().getId());
            }
            return participantResponse(board, participant);
        });
    }

    public WhiteboardSessionResponse close(SessionPrincipal actor, Target target, UUID id, long revision, MultipartFile file) {
        var scope = target.scope();
        require(scope.teacher());
        return unit(actor, scope, id, membership -> {
            var board = board(target, true);
            var snapshot = projection.validateSnapshot(file);
            String hash = hash(scope, List.of(target.board(), revision, snapshot.fileName(), sha256(snapshot.bytes())));
            if (replay(actor, scope, id, target.board(), Action.CLOSE, hash) != null) return summary(board);
            if (board.getStatus() == CLOSED || board.getStateRevision() != revision) throw conflict();
            begin(actor, scope, membership, id, target.board(), Action.CLOSE, hash);
            board.setFinalSnapshotData(snapshot.bytes());
            board.setFinalSnapshotContentType(snapshot.contentType());
            board.setFinalSnapshotFileName(snapshot.fileName());
            board.setFinalSnapshotSize((long) snapshot.bytes().length);
            board.setStatus(CLOSED);
            board.setClosedAt(LocalDateTime.now());
            board.setClosedBy(actor.username());
            seal(actor, scope, id, board, Action.CLOSE);
            control(target, board, SESSION_CLOSED, null);
            return summary(board);
        });
    }

    public void draw(SessionPrincipal actor, Target target, UUID id, WhiteboardDrawEventRequest request) {
        var scope = target.scope();
        unit(actor, scope, id, membership -> {
            var board = board(target, true);
            require(board.getStatus() == ACTIVE);
            UserAccount user;
            String displayName;
            if (scope.teacher()) {
                var teacher = teachers.findByUser_Id(actor.userId()).orElseThrow(EvaluationRuntime::denied);
                user = teacher.getUser();
                displayName = teacher.getNames() + " " + teacher.getLastNames();
            } else {
                var participant = participant(board, actor.userId());
                require(participant != null && WhiteboardInteractionPolicy.effective(
                    board.getInteractionEnabled(), participant.getInteractionOverride()));
                var student = participant.getStudent();
                user = student.getUser();
                displayName = student.getNames() + " " + student.getLastNames();
            }
            String hash = hash(scope, List.of(target.board(), request));
            if (replay(actor, scope, id, target.board(), Action.DRAW, hash) != null) return null;
            begin(actor, scope, membership, id, target.board(), Action.DRAW, hash);
            var event = drawing.applyInstitutional(board, user, request, scope.teacher(), displayName);
            seal(actor, scope, id, board, Action.DRAW);
            broadcast.send(target.topic(), event);
            return null;
        });
    }

    private <T> T unit(SessionPrincipal actor, WhiteboardScope scope, UUID id, LongFunction<T> work) {
        if (!access.installed()) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        return runtime.unit(actor, scope.institution(), null, id != null, id, () -> {
            T result = work.apply(access.membership(actor, scope));
            if (id != null) access.membership(actor, scope);
            return result;
        });
    }

    private WhiteboardSession board(Target target, boolean writing) {
        var scope = target.scope();
        var matching = jdbc.queryForList("""
            select b.id from board_runtime.boards s join whiteboard_sessions b on b.id=s.board_id
            where s.institution_id=? and s.class_id=? and s.board_id=?
            """ + (writing ? " for update of b" : " for share of b"), Long.class,
            scope.institution(), scope.clazz(), target.board());
        require(matching.size() == 1);
        return boards.findById(matching.get(0)).orElseThrow(EvaluationRuntime::denied);
    }

    private WhiteboardParticipant participant(WhiteboardSession board, long account) {
        var ids = jdbc.queryForList("select participant_id from board_runtime.current_admissions where board_id=? and account_id=?",
            Long.class, board.getId(), account);
        return ids.isEmpty() ? null : participants.findById(ids.get(0)).orElseThrow(EvaluationRuntime::denied);
    }

    private WhiteboardParticipantResponse participantResponse(WhiteboardSession board, WhiteboardParticipant p) {
        var student = p.getStudent();
        return new WhiteboardParticipantResponse(p.getId(), student.getId(), student.getNames() + " " + student.getLastNames(),
            "", board.getGrade(), board.getSection(), p.getInteractionOverride(), WhiteboardInteractionPolicy.effective(
            board.getInteractionEnabled(), p.getInteractionOverride()), p.getJoinedAt(), p.getLastSeenAt());
    }

    private WhiteboardSessionResponse summary(WhiteboardSession board) {
        int count = jdbc.queryForObject("select count(*) from board_runtime.current_admissions where board_id=?",
            Integer.class, board.getId());
        return projection.toTeacherResponse(board, count);
    }

    private void control(Target target, WhiteboardSession board, WhiteboardControlEventType type, Long student) {
        broadcast.send(target.topic(), projection.controlEvent(board, type, student));
    }

    private String hash(WhiteboardScope scope, Object request) {
        return runtime.digest(runtime.encode(List.of(scope.teacher(), request)));
    }

    private Receipt replay(SessionPrincipal actor, WhiteboardScope scope, UUID id, Long board, Action action, String hash) {
        var rows = jdbc.query("""
            select actor_id,institution_id,class_id,board_id,action,request_hash from board_runtime.operations where operation_id=?
            """, (r, n) -> new Receipt(r.getLong(1), r.getLong(2), r.getLong(3), r.getLong(4), r.getString(5), r.getString(6)), id);
        if (rows.isEmpty()) return null;
        var receipt = rows.get(0);
        if (receipt.actor() != actor.userId() || receipt.institution() != scope.institution() || receipt.clazz() != scope.clazz()
            || (board != null && receipt.board() != board) || !receipt.action().equals(action.name()) || !receipt.hash().equals(hash)) {
            throw conflict();
        }
        return receipt;
    }

    private void begin(SessionPrincipal actor, WhiteboardScope scope, long membership, UUID id, Long board, Action action, String hash) {
        jdbc.update("""
            insert into board_runtime.operations(operation_id,institution_id,class_id,actor_id,membership_id,board_id,action,request_hash)
            values(?,?,?,?,?,?,?,?)
            """, id, scope.institution(), scope.clazz(), actor.userId(), membership, board, action.name(), hash);
        jdbc.queryForObject("select set_config('board_runtime.operation',?,true)", String.class, id.toString());
    }

    private void seal(SessionPrincipal actor, WhiteboardScope scope, UUID id, WhiteboardSession board, Action action) {
        entityManager.flush();
        var evidence = SystemLog.builder().category(LogCategory.WHITEBOARD).eventType(action.event).severity(LogSeverity.INFO)
            .actorUserId(actor.userId()).actorRole(scope.teacher() ? Role.DOCENTE : Role.ESTUDIANTE)
            .targetType("WhiteboardSession").targetId(board.getId()).action(action.name()).description(action.name())
            .correlationId(id).outcome("SUCCESS").build();
        audit.persist(evidence);
        jdbc.execute("set local synchronous_commit=on");
        if (jdbc.update("""
            update board_runtime.operations set board_id=?,result=jsonb_build_object('revision',?::bigint),audit_id=?,
                committed_at=clock_timestamp() where operation_id=? and result is null
            """, board.getId(), board.getStateRevision(), evidence.getId(), id) != 1) {
            throw new IllegalStateException("Board receipt missing");
        }
    }

    private static String sha256(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
