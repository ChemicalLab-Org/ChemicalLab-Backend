package com.morales.chemicallab.service;

import com.morales.chemicallab.dto.WhiteboardDrawEventRequest;
import com.morales.chemicallab.dto.WhiteboardDrawEventResponse;
import com.morales.chemicallab.dto.WhiteboardDrawEventType;
import com.morales.chemicallab.dto.WhiteboardDrawTool;
import com.morales.chemicallab.dto.WhiteboardPoint;
import com.morales.chemicallab.dto.WhiteboardTextRun;
import com.morales.chemicallab.entity.Role;
import com.morales.chemicallab.entity.StudentProfile;
import com.morales.chemicallab.entity.TeacherProfile;
import com.morales.chemicallab.entity.UserAccount;
import com.morales.chemicallab.entity.WhiteboardParticipant;
import com.morales.chemicallab.entity.WhiteboardSession;
import com.morales.chemicallab.entity.WhiteboardSessionStatus;
import com.morales.chemicallab.repository.StudentProfileRepository;
import com.morales.chemicallab.repository.TeacherProfileRepository;
import com.morales.chemicallab.repository.UserAccountRepository;
import com.morales.chemicallab.repository.WhiteboardParticipantRepository;
import com.morales.chemicallab.repository.WhiteboardSessionRepository;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Valida y difunde los eventos de dibujo entrantes por WebSocket. El backend nunca confía en
 * el cliente: para cada evento valida el estado de la sesión y el permiso efectivo del actor
 * (resuelto del principal autenticado del STOMP), y acota el tamaño del payload.
 *
 * <p>Reglas:</p>
 * <ul>
 *   <li>La sesión debe existir y estar {@code ACTIVE} ({@code PAUSED}/{@code CLOSED} rechazan
 *       todo evento de dibujo).</li>
 *   <li>El docente debe ser el propietario de la sesión.</li>
 *   <li>El estudiante debe estar unido y tener permiso efectivo de interacción.</li>
 *   <li>{@code CLEAR} (limpiar toda la pizarra) queda reservado al docente.</li>
 * </ul>
 *
 * <p>No registra trazos ni payloads en los logs de auditoría: el dibujo es transporte en
 * vivo, no trazabilidad.</p>
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class WhiteboardDrawEventService {

    // Acotado para evitar payloads enormes por mensaje.
    private static final int MAX_POINTS = 1000;
    private static final double MAX_STROKE_WIDTH = 100.0;
    private static final double MAX_ERASER_SIZE = 200.0;
    private static final Pattern HEX_COLOR = Pattern.compile("^#([0-9a-fA-F]{3}|[0-9a-fA-F]{6})$");

    // Identidad y posición de trazos (deshacer/rehacer): acotadas para evitar abusos.
    private static final int MAX_STROKE_ID_LENGTH = 100;
    private static final int MAX_STROKE_INDEX = 100_000;

    // Texto: tope de fragmentos y de longitud total para acotar el payload.
    private static final int MAX_TEXT_RUNS = 200;
    private static final int MAX_TEXT_LENGTH = 5000;
    private static final double MIN_FONT_SIZE = 4.0;
    private static final double MAX_FONT_SIZE = 400.0;
    private static final Set<WhiteboardDrawTool> SHAPE_TOOLS = Set.of(
            WhiteboardDrawTool.RECTANGLE,
            WhiteboardDrawTool.CIRCLE,
            WhiteboardDrawTool.LINE,
            WhiteboardDrawTool.ARROW);
    // Evento reservado al docente: limpiar TODA la pizarra. El texto (TEXT/TEXT_DELETE) sí lo puede
    // originar un estudiante con permiso de interacción, igual que un trazo (lo valida la regla de
    // permiso efectivo de más abajo). Cada cliente solo puede borrar su propio texto vía TEXT_DELETE.
    private static final Set<WhiteboardDrawEventType> TEACHER_ONLY_EVENTS = Set.of(
            WhiteboardDrawEventType.CLEAR);

    private final WhiteboardSessionRepository sessionRepository;
    private final WhiteboardParticipantRepository participantRepository;
    private final UserAccountRepository userAccountRepository;
    private final TeacherProfileRepository teacherProfileRepository;
    private final StudentProfileRepository studentProfileRepository;
    private final WhiteboardBroadcastService broadcastService;

    /**
     * Valida un evento de dibujo y, si es válido, lo difunde a los suscriptores de la sesión.
     * Devuelve el evento difundido (útil para pruebas). El {@code username} proviene del
     * principal autenticado del canal STOMP, no del cuerpo del mensaje.
     */
    public WhiteboardDrawEventResponse processDrawEvent(String username, Long sessionId,
                                                        WhiteboardDrawEventRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("El evento de dibujo es obligatorio.");
        }

        WhiteboardSession session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new EntityNotFoundException("La sesión de pizarra no existe."));

        // Solo se dibuja en sesiones activas; pausadas y cerradas rechazan el evento.
        if (session.getStatus() != WhiteboardSessionStatus.ACTIVE) {
            throw new IllegalArgumentException("La sesión no está activa: no admite dibujo.");
        }

        UserAccount user = userAccountRepository.findByUsername(username)
                .orElseThrow(() -> new EntityNotFoundException("El usuario autenticado no existe."));

        WhiteboardDrawEventType eventType = requireEventType(request.eventType());
        WhiteboardDrawTool tool = requireTool(request.tool());

        String actorDisplayName;
        Role actorRole = user.getRole();

        switch (user.getRole()) {
            case DOCENTE -> {
                TeacherProfile teacher = teacherProfileRepository.findByUser(user)
                        .orElseThrow(() -> new EntityNotFoundException("El docente no existe."));
                if (!session.getTeacher().getId().equals(teacher.getId())) {
                    throw new IllegalArgumentException("No tienes permiso para dibujar en esta sesión.");
                }
                actorDisplayName = teacher.getNames() + " " + teacher.getLastNames();
            }
            case ESTUDIANTE -> {
                StudentProfile student = studentProfileRepository.findByStudentCode(user.getUsername())
                        .orElseThrow(() -> new EntityNotFoundException("El estudiante no existe."));
                WhiteboardParticipant participant = participantRepository
                        .findBySessionAndStudent(session, student)
                        .orElseThrow(() -> new IllegalArgumentException(
                                "Debes unirte a la sesión antes de dibujar."));
                boolean canInteract = WhiteboardInteractionPolicy.effective(
                        Boolean.TRUE.equals(session.getInteractionEnabled()),
                        participant.getInteractionOverride());
                if (!canInteract) {
                    throw new IllegalArgumentException("No tienes permiso de interacción en esta sesión.");
                }
                // Limpiar toda la pizarra y el manejo de texto quedan reservados al docente.
                if (TEACHER_ONLY_EVENTS.contains(eventType)) {
                    throw new IllegalArgumentException(
                            "Esta acción de la pizarra está reservada al docente.");
                }
                actorDisplayName = student.getNames() + " " + student.getLastNames();
            }
            default -> throw new IllegalArgumentException("El rol del usuario no permite dibujar.");
        }

        // Los eventos de texto (TEXT/TEXT_DELETE) viajan por el mismo canal con su propio payload;
        // el resto (DRAW/ERASE/CLEAR) conservan exactamente la validación anterior.
        WhiteboardDrawEventResponse response;
        if (eventType == WhiteboardDrawEventType.TEXT
                || eventType == WhiteboardDrawEventType.TEXT_DELETE) {
            response = buildTextResponse(session, eventType, tool, request, actorRole, actorDisplayName);
        } else if (eventType == WhiteboardDrawEventType.SHAPE
                || eventType == WhiteboardDrawEventType.SHAPE_DELETE) {
            response = buildShapeResponse(session, eventType, tool, request, actorRole, actorDisplayName);
        } else if (eventType == WhiteboardDrawEventType.STROKE_DELETE) {
            response = buildStrokeDeleteResponse(session, eventType, tool, request, actorRole, actorDisplayName);
        } else {
            response = buildDrawResponse(session, eventType, tool, request, actorRole, actorDisplayName);
        }

        broadcastService.broadcastDraw(response);
        return response;
    }

    private WhiteboardDrawEventResponse buildDrawResponse(WhiteboardSession session,
                                                          WhiteboardDrawEventType eventType,
                                                          WhiteboardDrawTool tool,
                                                          WhiteboardDrawEventRequest request,
                                                          Role actorRole, String actorDisplayName) {
        List<WhiteboardPoint> points = validatePayload(eventType, request);
        String color = validateColor(request.color());
        String strokeId = validateOptionalStrokeId(request.strokeId());
        Integer strokeIndex = validateStrokeIndex(request.strokeIndex());
        return new WhiteboardDrawEventResponse(
                session.getId(), eventType, tool, color,
                request.strokeWidth(), request.eraserSize(), points,
                actorRole, actorDisplayName, request.clientEventId(), LocalDateTime.now(),
                null, null, null, null, strokeId, strokeIndex);
    }

    /**
     * Construye un evento de eliminación de trazo por identificador estable. Lo origina el
     * deshacer/rehacer de un participante sobre sus propios trazos; el permiso efectivo es el
     * mismo que para dibujar (ya validado arriba).
     */
    private WhiteboardDrawEventResponse buildStrokeDeleteResponse(WhiteboardSession session,
                                                                  WhiteboardDrawEventType eventType,
                                                                  WhiteboardDrawTool tool,
                                                                  WhiteboardDrawEventRequest request,
                                                                  Role actorRole, String actorDisplayName) {
        String strokeId = request.strokeId();
        if (strokeId == null || strokeId.isBlank() || strokeId.length() > MAX_STROKE_ID_LENGTH) {
            throw new IllegalArgumentException("El identificador del trazo es obligatorio.");
        }
        return new WhiteboardDrawEventResponse(
                session.getId(), eventType, tool, null, null, null, null,
                actorRole, actorDisplayName, request.clientEventId(), LocalDateTime.now(),
                null, null, null, null, strokeId, null);
    }

    /**
     * Construye un evento de texto validado. TEXT exige posición (un punto), tamaño y fragmentos de
     * texto acotados; TEXT_DELETE solo exige el identificador del bloque a eliminar.
     */
    private WhiteboardDrawEventResponse buildTextResponse(WhiteboardSession session,
                                                          WhiteboardDrawEventType eventType,
                                                          WhiteboardDrawTool tool,
                                                          WhiteboardDrawEventRequest request,
                                                          Role actorRole, String actorDisplayName) {
        String textId = request.textId();
        if (textId == null || textId.isBlank() || textId.length() > 100) {
            throw new IllegalArgumentException("El identificador del texto es obligatorio.");
        }

        if (eventType == WhiteboardDrawEventType.TEXT_DELETE) {
            return new WhiteboardDrawEventResponse(
                    session.getId(), eventType, tool, null, null, null, null,
                    actorRole, actorDisplayName, request.clientEventId(), LocalDateTime.now(),
                    textId, null, null, null);
        }

        List<WhiteboardPoint> points = request.points();
        if (points == null || points.size() != 1 || points.get(0) == null
                || points.get(0).x() == null || points.get(0).y() == null) {
            throw new IllegalArgumentException("El texto debe incluir su posición (un punto).");
        }
        Double fontSize = request.fontSize();
        if (fontSize == null || fontSize < MIN_FONT_SIZE || fontSize > MAX_FONT_SIZE) {
            throw new IllegalArgumentException("El tamaño del texto no es válido.");
        }
        List<WhiteboardTextRun> runs = validateRuns(request.runs());
        String color = validateColor(request.color());
        return new WhiteboardDrawEventResponse(
                session.getId(), eventType, tool, color, null, null, List.copyOf(points),
                actorRole, actorDisplayName, request.clientEventId(), LocalDateTime.now(),
                textId, fontSize, runs, null);
    }

    private WhiteboardDrawEventResponse buildShapeResponse(WhiteboardSession session,
                                                           WhiteboardDrawEventType eventType,
                                                           WhiteboardDrawTool tool,
                                                           WhiteboardDrawEventRequest request,
                                                           Role actorRole, String actorDisplayName) {
        String shapeId = request.shapeId();
        if (shapeId == null || shapeId.isBlank() || shapeId.length() > 100) {
            throw new IllegalArgumentException("El identificador de la forma es obligatorio.");
        }
        if (!SHAPE_TOOLS.contains(tool)) {
            throw new IllegalArgumentException("La herramienta de forma no es valida.");
        }
        if (eventType == WhiteboardDrawEventType.SHAPE_DELETE) {
            return new WhiteboardDrawEventResponse(
                    session.getId(), eventType, tool, null, null, null, null,
                    actorRole, actorDisplayName, request.clientEventId(), LocalDateTime.now(),
                    null, null, null, shapeId);
        }
        List<WhiteboardPoint> points = request.points();
        if (points == null || points.size() != 2 || points.get(0) == null || points.get(1) == null
                || points.get(0).x() == null || points.get(0).y() == null
                || points.get(1).x() == null || points.get(1).y() == null) {
            throw new IllegalArgumentException("La forma debe incluir punto inicial y punto final.");
        }
        validateMeasure(request.strokeWidth(), MAX_STROKE_WIDTH, "El grosor de la forma");
        String color = validateColor(request.color());
        return new WhiteboardDrawEventResponse(
                session.getId(), eventType, tool, color, request.strokeWidth(), null, List.copyOf(points),
                actorRole, actorDisplayName, request.clientEventId(), LocalDateTime.now(),
                null, null, null, shapeId);
    }

    private List<WhiteboardTextRun> validateRuns(List<WhiteboardTextRun> runs) {
        if (runs == null || runs.isEmpty()) {
            throw new IllegalArgumentException("El texto no puede estar vacío.");
        }
        if (runs.size() > MAX_TEXT_RUNS) {
            throw new IllegalArgumentException("El texto tiene demasiados fragmentos de formato.");
        }
        int total = 0;
        for (WhiteboardTextRun run : runs) {
            if (run == null || run.text() == null) {
                throw new IllegalArgumentException("Cada fragmento de texto debe tener contenido.");
            }
            total += run.text().length();
        }
        if (total == 0) {
            throw new IllegalArgumentException("El texto no puede estar vacío.");
        }
        if (total > MAX_TEXT_LENGTH) {
            throw new IllegalArgumentException("El texto supera la longitud máxima permitida.");
        }
        return List.copyOf(runs);
    }

    // =========================================================================
    // VALIDACIONES
    // =========================================================================

    private WhiteboardDrawEventType requireEventType(WhiteboardDrawEventType eventType) {
        if (eventType == null) {
            throw new IllegalArgumentException("El tipo de evento de dibujo es obligatorio.");
        }
        return eventType;
    }

    private WhiteboardDrawTool requireTool(WhiteboardDrawTool tool) {
        if (tool == null) {
            throw new IllegalArgumentException("La herramienta del evento es obligatoria.");
        }
        return tool;
    }

    /**
     * Valida el payload según el tipo de evento. CLEAR no requiere puntos; DRAW/ERASE exigen
     * una lista de puntos no vacía y acotada, con coordenadas presentes. Valida además los
     * tamaños de trazo y borrador.
     */
    private List<WhiteboardPoint> validatePayload(WhiteboardDrawEventType eventType,
                                                  WhiteboardDrawEventRequest request) {
        if (eventType == WhiteboardDrawEventType.CLEAR) {
            return List.of();
        }

        List<WhiteboardPoint> points = request.points();
        if (points == null || points.isEmpty()) {
            throw new IllegalArgumentException("El evento de dibujo debe incluir al menos un punto.");
        }
        if (points.size() > MAX_POINTS) {
            throw new IllegalArgumentException("El evento de dibujo supera el máximo de "
                    + MAX_POINTS + " puntos.");
        }
        for (WhiteboardPoint point : points) {
            if (point == null || point.x() == null || point.y() == null) {
                throw new IllegalArgumentException("Cada punto debe tener coordenadas x e y.");
            }
        }

        validateMeasure(request.strokeWidth(), MAX_STROKE_WIDTH, "El grosor del trazo");
        validateMeasure(request.eraserSize(), MAX_ERASER_SIZE, "El tamaño del borrador");
        return List.copyOf(points);
    }

    private void validateMeasure(Double value, double max, String label) {
        if (value == null) {
            return;
        }
        if (value <= 0 || value > max) {
            throw new IllegalArgumentException(label + " no es válido.");
        }
    }

    /** El identificador de trazo es opcional en DRAW/ERASE, pero si viene debe ser razonable. */
    private String validateOptionalStrokeId(String strokeId) {
        if (strokeId == null || strokeId.isBlank()) {
            return null;
        }
        if (strokeId.length() > MAX_STROKE_ID_LENGTH) {
            throw new IllegalArgumentException("El identificador del trazo no es válido.");
        }
        return strokeId;
    }

    /** La posición de restauración de un trazo es opcional; si viene debe estar acotada. */
    private Integer validateStrokeIndex(Integer strokeIndex) {
        if (strokeIndex == null) {
            return null;
        }
        if (strokeIndex < 0 || strokeIndex > MAX_STROKE_INDEX) {
            throw new IllegalArgumentException("La posición del trazo no es válida.");
        }
        return strokeIndex;
    }

    private String validateColor(String color) {
        if (color == null || color.isBlank()) {
            return null;
        }
        String normalized = color.trim().toLowerCase(Locale.ROOT);
        if (!HEX_COLOR.matcher(normalized).matches()) {
            throw new IllegalArgumentException("El color debe ser un valor hexadecimal (p. ej. #1a2b3c).");
        }
        return normalized;
    }
}
