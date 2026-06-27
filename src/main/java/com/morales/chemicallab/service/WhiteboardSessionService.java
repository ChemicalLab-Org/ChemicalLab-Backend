package com.morales.chemicallab.service;

import com.morales.chemicallab.dto.*;
import com.morales.chemicallab.entity.*;
import com.morales.chemicallab.repository.StudentProfileRepository;
import com.morales.chemicallab.repository.TeacherProfileRepository;
import com.morales.chemicallab.repository.UserAccountRepository;
import com.morales.chemicallab.repository.WhiteboardParticipantRepository;
import com.morales.chemicallab.repository.WhiteboardSessionRepository;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Lógica de negocio de las sesiones de pizarra interactiva en vivo.
 *
 * <p>Garantías principales:</p>
 * <ul>
 *   <li><strong>Pertenencia:</strong> el docente solo controla sus propias sesiones; el
 *       estudiante solo ve/usa sesiones de su grado/sección. El actor se resuelve del usuario
 *       autenticado, nunca de identificadores enviados por el cliente.</li>
 *   <li><strong>Estados:</strong> {@code ACTIVE}/{@code PAUSED} admiten control; {@code CLOSED}
 *       es terminal: no se reabre, no se dibuja, no se cambian permisos ni la captura.</li>
 *   <li><strong>Captura final:</strong> al cerrar se exige una imagen PNG/JPG válida, que se
 *       guarda como bytes en PostgreSQL (no en el sistema de archivos local).</li>
 *   <li><strong>Permiso efectivo:</strong> {@link WhiteboardInteractionPolicy} combina el
 *       permiso global y el individual.</li>
 *   <li><strong>Trazabilidad:</strong> se registran logs seguros de las acciones importantes,
 *       sin volcar trazos ni la imagen.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Transactional
public class WhiteboardSessionService {

    private static final Logger log = LoggerFactory.getLogger(WhiteboardSessionService.class);

    private static final String TARGET = "WhiteboardSession";

    // Captura final: solo imágenes PNG/JPG, máximo 5 MB y nunca vacía.
    private static final long MAX_SNAPSHOT_BYTES = 5L * 1024 * 1024;
    private static final Set<String> ALLOWED_SNAPSHOT_TYPES = Set.of("image/png", "image/jpeg");

    // Estados visibles para el estudiante (sesiones disponibles para unirse/ver en vivo).
    private static final Set<WhiteboardSessionStatus> LIVE_STATUSES =
            Set.of(WhiteboardSessionStatus.ACTIVE, WhiteboardSessionStatus.PAUSED);

    private final WhiteboardSessionRepository sessionRepository;
    private final WhiteboardParticipantRepository participantRepository;
    private final UserAccountRepository userAccountRepository;
    private final TeacherProfileRepository teacherProfileRepository;
    private final StudentProfileRepository studentProfileRepository;
    private final AuditLogService auditLogService;
    private final UsageMetricService usageMetricService;
    private final WhiteboardBroadcastService broadcastService;

    // =========================================================================
    // DOCENTE
    // =========================================================================

    public WhiteboardSessionResponse createSession(String username, WhiteboardSessionCreateRequest request) {
        TeacherProfile teacher = requireTeacher(username);

        String name = request.name() == null ? "" : request.name().trim();
        if (name.isEmpty()) {
            throw new IllegalArgumentException("El nombre de la sesión es obligatorio.");
        }
        String grade = StudentValidation.normalizedGrade(request.grade());
        String section = StudentValidation.normalizedSection(request.section());

        WhiteboardSession session = WhiteboardSession.builder()
                .name(name)
                .description(cleanText(request.description()))
                .teacher(teacher)
                .grade(grade)
                .section(section)
                .status(WhiteboardSessionStatus.ACTIVE)
                .interactionEnabled(false)
                .build();
        sessionRepository.save(session);

        auditLogService.recordInfo(LogEventType.WHITEBOARD_SESSION_CREATED, TARGET, session.getId(),
                session.getName(), "Crear sesión",
                "Se creó la sesión de pizarra «" + session.getName() + "» para " + grade + "° " + section + ".",
                "grade=" + grade + ";section=" + section);

        return toTeacherResponse(session);
    }

    @Transactional(readOnly = true)
    public List<WhiteboardSessionResponse> listTeacherSessions(String username) {
        TeacherProfile teacher = requireTeacher(username);
        return sessionRepository.findByTeacherOrderByCreatedAtDesc(teacher).stream()
                .map(this::toTeacherResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public WhiteboardSessionDetailResponse getTeacherSessionDetail(String username, Long sessionId) {
        TeacherProfile teacher = requireTeacher(username);
        WhiteboardSession session = requireOwnedSession(sessionId, teacher);
        return new WhiteboardSessionDetailResponse(toTeacherResponse(session), listParticipantResponses(session));
    }

    @Transactional(readOnly = true)
    public List<WhiteboardParticipantResponse> listParticipants(String username, Long sessionId) {
        TeacherProfile teacher = requireTeacher(username);
        WhiteboardSession session = requireOwnedSession(sessionId, teacher);
        return listParticipantResponses(session);
    }

    public WhiteboardSessionResponse pauseSession(String username, Long sessionId) {
        TeacherProfile teacher = requireTeacher(username);
        WhiteboardSession session = requireOwnedSession(sessionId, teacher);
        requireNotClosed(session);

        if (session.getStatus() != WhiteboardSessionStatus.ACTIVE) {
            throw new IllegalArgumentException("Solo se puede pausar una sesión activa.");
        }
        session.setStatus(WhiteboardSessionStatus.PAUSED);
        session.setPausedAt(LocalDateTime.now());
        sessionRepository.save(session);

        auditLogService.recordInfo(LogEventType.WHITEBOARD_SESSION_PAUSED, TARGET, session.getId(),
                session.getName(), "Pausar sesión",
                "Se pausó la sesión de pizarra «" + session.getName() + "».", null);

        broadcastService.broadcastControl(controlEvent(session, WhiteboardControlEventType.SESSION_PAUSED, null));
        return toTeacherResponse(session);
    }

    public WhiteboardSessionResponse resumeSession(String username, Long sessionId) {
        TeacherProfile teacher = requireTeacher(username);
        WhiteboardSession session = requireOwnedSession(sessionId, teacher);
        requireNotClosed(session);

        if (session.getStatus() != WhiteboardSessionStatus.PAUSED) {
            throw new IllegalArgumentException("Solo se puede reanudar una sesión pausada.");
        }
        session.setStatus(WhiteboardSessionStatus.ACTIVE);
        session.setResumedAt(LocalDateTime.now());
        sessionRepository.save(session);

        auditLogService.recordInfo(LogEventType.WHITEBOARD_SESSION_RESUMED, TARGET, session.getId(),
                session.getName(), "Reanudar sesión",
                "Se reanudó la sesión de pizarra «" + session.getName() + "».", null);

        broadcastService.broadcastControl(controlEvent(session, WhiteboardControlEventType.SESSION_RESUMED, null));
        return toTeacherResponse(session);
    }

    public WhiteboardSessionResponse closeSession(String username, Long sessionId, MultipartFile snapshot) {
        TeacherProfile teacher = requireTeacher(username);
        WhiteboardSession session = requireOwnedSession(sessionId, teacher);

        // CLOSED es terminal: no se reabre ni se vuelve a cerrar.
        if (session.getStatus() == WhiteboardSessionStatus.CLOSED) {
            throw new IllegalArgumentException("La sesión ya está finalizada y no puede reabrirse.");
        }

        SnapshotData data = validateSnapshot(snapshot);
        session.setFinalSnapshotData(data.bytes());
        session.setFinalSnapshotContentType(data.contentType());
        session.setFinalSnapshotFileName(data.fileName());
        session.setFinalSnapshotSize((long) data.bytes().length);
        session.setStatus(WhiteboardSessionStatus.CLOSED);
        session.setClosedAt(LocalDateTime.now());
        session.setClosedBy(username);
        sessionRepository.save(session);

        auditLogService.recordInfo(LogEventType.WHITEBOARD_SESSION_CLOSED, TARGET, session.getId(),
                session.getName(), "Finalizar sesión",
                "Se finalizó la sesión de pizarra «" + session.getName() + "» con captura final.",
                "contentType=" + data.contentType() + ";size=" + data.bytes().length);

        broadcastService.broadcastControl(controlEvent(session, WhiteboardControlEventType.SESSION_CLOSED, null));
        return toTeacherResponse(session);
    }

    public WhiteboardSessionResponse updateGlobalInteraction(String username, Long sessionId,
                                                             WhiteboardGlobalInteractionRequest request) {
        TeacherProfile teacher = requireTeacher(username);
        WhiteboardSession session = requireOwnedSession(sessionId, teacher);
        requireNotClosed(session);

        boolean enabled = Boolean.TRUE.equals(request.interactionEnabled());
        session.setInteractionEnabled(enabled);
        sessionRepository.save(session);

        auditLogService.recordInfo(LogEventType.WHITEBOARD_INTERACTION_UPDATED, TARGET, session.getId(),
                session.getName(), "Actualizar interacción global",
                "Se " + (enabled ? "activó" : "desactivó") + " la interacción global de la sesión «"
                        + session.getName() + "».",
                "interactionEnabled=" + enabled);

        broadcastService.broadcastControl(controlEvent(session, WhiteboardControlEventType.INTERACTION_UPDATED, null));
        return toTeacherResponse(session);
    }

    public WhiteboardParticipantResponse updateParticipantInteraction(String username, Long sessionId, Long studentId,
                                                                      WhiteboardParticipantInteractionRequest request) {
        TeacherProfile teacher = requireTeacher(username);
        WhiteboardSession session = requireOwnedSession(sessionId, teacher);
        requireNotClosed(session);

        WhiteboardParticipant participant = participantRepository
                .findBySession_IdAndStudent_Id(sessionId, studentId)
                .orElseThrow(() -> new EntityNotFoundException("El estudiante no participa en esta sesión."));

        // Defensa adicional: el alumno debe pertenecer al grado/sección de la sesión.
        StudentProfile student = participant.getStudent();
        if (!student.getGrade().equals(session.getGrade()) || !student.getSection().equals(session.getSection())) {
            throw new IllegalArgumentException("El estudiante no pertenece al grado/sección de la sesión.");
        }

        participant.setInteractionOverride(request.interactionOverride());
        participantRepository.save(participant);

        auditLogService.recordInfo(LogEventType.WHITEBOARD_PARTICIPANT_INTERACTION_UPDATED, TARGET, session.getId(),
                session.getName(), "Actualizar interacción individual",
                "Se actualizó el permiso de interacción de un estudiante en la sesión «"
                        + session.getName() + "».",
                "override=" + request.interactionOverride());

        broadcastService.broadcastControl(
                controlEvent(session, WhiteboardControlEventType.PARTICIPANT_PERMISSION_UPDATED, student.getId()));
        return toParticipantResponse(session, participant);
    }

    @Transactional(readOnly = true)
    public WhiteboardSnapshotDownload getTeacherSnapshot(String username, Long sessionId) {
        TeacherProfile teacher = requireTeacher(username);
        WhiteboardSession session = requireOwnedSession(sessionId, teacher);
        return requireSnapshot(session);
    }

    // =========================================================================
    // ESTUDIANTE
    // =========================================================================

    @Transactional(readOnly = true)
    public List<WhiteboardStudentSessionResponse> listActiveForStudent(String username) {
        StudentProfile student = requireStudent(username);
        return sessionRepository.findByGradeAndSectionAndStatusInOrderByCreatedAtDesc(
                        student.getGrade(), student.getSection(), LIVE_STATUSES).stream()
                .map(session -> toStudentResponse(session, student))
                .toList();
    }

    @Transactional(readOnly = true)
    public WhiteboardStudentSessionResponse getStudentSessionDetail(String username, Long sessionId) {
        StudentProfile student = requireStudent(username);
        WhiteboardSession session = requireVisibleToStudent(sessionId, student);
        return toStudentResponse(session, student);
    }

    public WhiteboardStudentSessionResponse joinSession(String username, Long sessionId) {
        StudentProfile student = requireStudent(username);
        WhiteboardSession session = requireVisibleToStudent(sessionId, student);

        if (session.getStatus() == WhiteboardSessionStatus.CLOSED) {
            throw new IllegalArgumentException("No puedes unirte a una sesión finalizada.");
        }

        // Join idempotente: si ya está unido, solo se actualiza la última conexión.
        WhiteboardParticipant participant = participantRepository
                .findBySessionAndStudent(session, student)
                .orElse(null);
        if (participant == null) {
            participant = WhiteboardParticipant.builder()
                    .session(session)
                    .student(student)
                    .interactionOverride(WhiteboardInteractionOverride.FOLLOW_GLOBAL)
                    .build();
        } else {
            participant.setLastSeenAt(LocalDateTime.now());
        }
        participantRepository.save(participant);

        recordJoinMetric(username, session);
        return toStudentResponse(session, student);
    }

    /**
     * Actualiza la marca de presencia (última conexión) del estudiante en una sesión en vivo.
     * Se invoca desde el canal de presencia WebSocket; es best-effort y no difunde eventos.
     */
    public void registerPresence(String username, Long sessionId) {
        StudentProfile student = requireStudent(username);
        WhiteboardSession session = sessionRepository.findById(sessionId).orElse(null);
        if (session == null || !belongsToStudentSection(session, student)
                || session.getStatus() == WhiteboardSessionStatus.CLOSED) {
            return;
        }
        participantRepository.findBySessionAndStudent(session, student).ifPresent(participant -> {
            participant.setLastSeenAt(LocalDateTime.now());
            participantRepository.save(participant);
        });
    }

    @Transactional(readOnly = true)
    public List<WhiteboardHistoryItemResponse> getStudentHistory(String username) {
        StudentProfile student = requireStudent(username);
        return sessionRepository.findByGradeAndSectionAndStatusOrderByClosedAtDesc(
                        student.getGrade(), student.getSection(), WhiteboardSessionStatus.CLOSED).stream()
                .map(this::toHistoryItem)
                .toList();
    }

    @Transactional(readOnly = true)
    public WhiteboardSnapshotDownload getStudentSnapshot(String username, Long sessionId) {
        StudentProfile student = requireStudent(username);
        WhiteboardSession session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new EntityNotFoundException("La sesión de pizarra no existe."));
        // El estudiante solo accede a la captura de sesiones cerradas de su grado/sección.
        if (!belongsToStudentSection(session, student)) {
            throw new EntityNotFoundException("La sesión no está disponible para tu grado o sección.");
        }
        if (session.getStatus() != WhiteboardSessionStatus.CLOSED) {
            throw new IllegalArgumentException("La captura final solo está disponible en sesiones finalizadas.");
        }
        return requireSnapshot(session);
    }

    // =========================================================================
    // ADMINISTRADOR
    // =========================================================================

    @Transactional(readOnly = true)
    public WhiteboardSummaryResponse getAdminSummary() {
        long total = sessionRepository.count();
        long active = sessionRepository.countByStatus(WhiteboardSessionStatus.ACTIVE);
        long paused = sessionRepository.countByStatus(WhiteboardSessionStatus.PAUSED);
        long closed = sessionRepository.countByStatus(WhiteboardSessionStatus.CLOSED);
        long participants = participantRepository.count();

        List<WhiteboardAdminSessionResponse> recent = sessionRepository.findAllByOrderByCreatedAtDesc().stream()
                .limit(10)
                .map(this::toAdminResponse)
                .toList();

        return new WhiteboardSummaryResponse(total, active, paused, closed, participants, recent);
    }

    @Transactional(readOnly = true)
    public List<WhiteboardAdminSessionResponse> listAdminSessions() {
        return sessionRepository.findAllByOrderByCreatedAtDesc().stream()
                .map(this::toAdminResponse)
                .toList();
    }

    // =========================================================================
    // RESOLUCIÓN DE USUARIOS Y SESIONES
    // =========================================================================

    private TeacherProfile requireTeacher(String username) {
        UserAccount user = userAccountRepository.findByUsername(username)
                .orElseThrow(() -> new EntityNotFoundException("El docente no existe."));
        if (user.getRole() != Role.DOCENTE) {
            throw new IllegalArgumentException("El usuario autenticado no es un docente.");
        }
        return teacherProfileRepository.findByUser(user)
                .orElseThrow(() -> new EntityNotFoundException("El docente no existe."));
    }

    private StudentProfile requireStudent(String username) {
        UserAccount user = userAccountRepository.findByUsername(username)
                .orElseThrow(() -> new EntityNotFoundException("El estudiante no existe."));
        if (user.getRole() != Role.ESTUDIANTE) {
            throw new IllegalArgumentException("El usuario autenticado no es un estudiante.");
        }
        return studentProfileRepository.findByStudentCode(user.getUsername())
                .orElseThrow(() -> new EntityNotFoundException("El estudiante no existe."));
    }

    /** Carga una sesión y valida que pertenezca al docente indicado. */
    private WhiteboardSession requireOwnedSession(Long sessionId, TeacherProfile teacher) {
        WhiteboardSession session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new EntityNotFoundException("La sesión de pizarra no existe."));
        if (!session.getTeacher().getId().equals(teacher.getId())) {
            throw new IllegalArgumentException("No tienes permiso para gestionar esta sesión.");
        }
        return session;
    }

    /** Carga una sesión visible para el estudiante (de su grado/sección). */
    private WhiteboardSession requireVisibleToStudent(Long sessionId, StudentProfile student) {
        WhiteboardSession session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new EntityNotFoundException("La sesión de pizarra no existe."));
        if (!belongsToStudentSection(session, student)) {
            throw new EntityNotFoundException("La sesión no está disponible para tu grado o sección.");
        }
        return session;
    }

    private boolean belongsToStudentSection(WhiteboardSession session, StudentProfile student) {
        return session.getGrade().equals(student.getGrade())
                && session.getSection().equals(student.getSection());
    }

    private void requireNotClosed(WhiteboardSession session) {
        if (session.getStatus() == WhiteboardSessionStatus.CLOSED) {
            throw new IllegalArgumentException("La sesión está finalizada y no admite cambios.");
        }
    }

    // =========================================================================
    // CAPTURA FINAL
    // =========================================================================

    private SnapshotData validateSnapshot(MultipartFile snapshot) {
        if (snapshot == null || snapshot.isEmpty()) {
            throw new IllegalArgumentException("Debes adjuntar la captura final de la pizarra (PNG o JPG).");
        }
        if (snapshot.getSize() > MAX_SNAPSHOT_BYTES) {
            throw new IllegalArgumentException("La captura final supera el tamaño máximo permitido (5 MB).");
        }
        String contentType = snapshot.getContentType() == null
                ? "" : snapshot.getContentType().trim().toLowerCase(Locale.ROOT);
        if (!ALLOWED_SNAPSHOT_TYPES.contains(contentType)) {
            throw new IllegalArgumentException("La captura final debe ser una imagen PNG o JPG.");
        }
        byte[] bytes;
        try {
            bytes = snapshot.getBytes();
        } catch (Exception ex) {
            throw new IllegalArgumentException("No se pudo leer la captura final adjunta.");
        }
        if (bytes.length == 0) {
            throw new IllegalArgumentException("La captura final no puede estar vacía.");
        }
        String fileName = sanitizeFileName(snapshot.getOriginalFilename(),
                contentType.equals("image/png") ? "captura.png" : "captura.jpg");
        return new SnapshotData(bytes, contentType, fileName);
    }

    private WhiteboardSnapshotDownload requireSnapshot(WhiteboardSession session) {
        if (!session.hasSnapshot()) {
            throw new EntityNotFoundException("La sesión no tiene una captura final disponible.");
        }
        return new WhiteboardSnapshotDownload(
                session.getFinalSnapshotData(),
                session.getFinalSnapshotContentType(),
                session.getFinalSnapshotFileName());
    }

    private String sanitizeFileName(String rawName, String fallback) {
        if (rawName == null || rawName.isBlank()) {
            return fallback;
        }
        String name = rawName.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1);
        name = name.replace("..", "").replaceAll("[\\r\\n\"]", "").trim();
        if (name.isEmpty()) {
            return fallback;
        }
        return name.length() > 255 ? name.substring(name.length() - 255) : name;
    }

    private record SnapshotData(byte[] bytes, String contentType, String fileName) {
    }

    // =========================================================================
    // MÉTRICAS
    // =========================================================================

    /** Registra la unión del estudiante como métrica de uso, sin romper el join si falla. */
    private void recordJoinMetric(String username, WhiteboardSession session) {
        try {
            usageMetricService.recordEvent(username, new RecordUsageEventRequest(
                    UsageModule.WHITEBOARD,
                    UsageEventType.WHITEBOARD_SESSION_JOINED,
                    TARGET,
                    String.valueOf(session.getId()),
                    null,
                    null));
        } catch (Exception ex) {
            log.warn("No fue posible registrar la métrica de unión a la pizarra {}: {}",
                    session.getId(), ex.getMessage());
        }
    }

    // =========================================================================
    // MAPEO
    // =========================================================================

    private List<WhiteboardParticipantResponse> listParticipantResponses(WhiteboardSession session) {
        return participantRepository.findBySessionOrderByJoinedAtAsc(session).stream()
                .map(participant -> toParticipantResponse(session, participant))
                .toList();
    }

    private WhiteboardSessionResponse toTeacherResponse(WhiteboardSession session) {
        TeacherProfile teacher = session.getTeacher();
        return new WhiteboardSessionResponse(
                session.getId(),
                session.getName(),
                session.getDescription(),
                teacher.getId(),
                teacher.getNames() + " " + teacher.getLastNames(),
                session.getGrade(),
                session.getSection(),
                session.getStatus(),
                Boolean.TRUE.equals(session.getInteractionEnabled()),
                (int) participantRepository.countBySession(session),
                session.hasSnapshot(),
                session.getCreatedAt(),
                session.getStartedAt(),
                session.getPausedAt(),
                session.getResumedAt(),
                session.getClosedAt(),
                session.getClosedBy());
    }

    private WhiteboardParticipantResponse toParticipantResponse(WhiteboardSession session,
                                                                WhiteboardParticipant participant) {
        StudentProfile student = participant.getStudent();
        boolean effective = WhiteboardInteractionPolicy.effective(
                Boolean.TRUE.equals(session.getInteractionEnabled()), participant.getInteractionOverride());
        return new WhiteboardParticipantResponse(
                participant.getId(),
                student.getId(),
                student.getNames() + " " + student.getLastNames(),
                student.getStudentCode(),
                student.getGrade(),
                student.getSection(),
                participant.getInteractionOverride(),
                effective,
                participant.getJoinedAt(),
                participant.getLastSeenAt());
    }

    private WhiteboardStudentSessionResponse toStudentResponse(WhiteboardSession session, StudentProfile student) {
        TeacherProfile teacher = session.getTeacher();
        WhiteboardParticipant participant = participantRepository
                .findBySessionAndStudent(session, student)
                .orElse(null);
        boolean joined = participant != null;
        boolean canInteract = joined
                && session.getStatus() == WhiteboardSessionStatus.ACTIVE
                && WhiteboardInteractionPolicy.effective(
                        Boolean.TRUE.equals(session.getInteractionEnabled()), participant.getInteractionOverride());
        return new WhiteboardStudentSessionResponse(
                session.getId(),
                session.getName(),
                session.getDescription(),
                teacher.getNames() + " " + teacher.getLastNames(),
                session.getGrade(),
                session.getSection(),
                session.getStatus(),
                Boolean.TRUE.equals(session.getInteractionEnabled()),
                joined,
                canInteract,
                session.hasSnapshot(),
                session.getCreatedAt(),
                session.getStartedAt(),
                session.getClosedAt());
    }

    private WhiteboardHistoryItemResponse toHistoryItem(WhiteboardSession session) {
        TeacherProfile teacher = session.getTeacher();
        return new WhiteboardHistoryItemResponse(
                session.getId(),
                session.getName(),
                teacher.getNames() + " " + teacher.getLastNames(),
                session.getGrade(),
                session.getSection(),
                session.getStatus(),
                session.hasSnapshot(),
                session.getClosedAt());
    }

    private WhiteboardAdminSessionResponse toAdminResponse(WhiteboardSession session) {
        TeacherProfile teacher = session.getTeacher();
        return new WhiteboardAdminSessionResponse(
                session.getId(),
                session.getName(),
                teacher.getId(),
                teacher.getNames() + " " + teacher.getLastNames(),
                session.getGrade(),
                session.getSection(),
                session.getStatus(),
                Boolean.TRUE.equals(session.getInteractionEnabled()),
                (int) participantRepository.countBySession(session),
                session.hasSnapshot(),
                session.getCreatedAt(),
                session.getClosedAt());
    }

    private WhiteboardControlEventResponse controlEvent(WhiteboardSession session,
                                                        WhiteboardControlEventType type, Long targetStudentId) {
        return new WhiteboardControlEventResponse(
                session.getId(),
                type,
                session.getStatus(),
                Boolean.TRUE.equals(session.getInteractionEnabled()),
                targetStudentId,
                LocalDateTime.now());
    }

    private String cleanText(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
