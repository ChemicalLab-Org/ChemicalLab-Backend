package com.morales.chemicallab.service;

import com.morales.chemicallab.dto.StudentUsageRecordDetailResponse;
import com.morales.chemicallab.dto.StudentUsageRecordResponse;
import com.morales.chemicallab.dto.StudentUsageRecordsResponse;
import com.morales.chemicallab.entity.AttemptStatus;
import com.morales.chemicallab.entity.Evaluation;
import com.morales.chemicallab.entity.EvaluationAssignment;
import com.morales.chemicallab.entity.EvaluationAttempt;
import com.morales.chemicallab.entity.EvaluationStatus;
import com.morales.chemicallab.entity.LogEventType;
import com.morales.chemicallab.entity.LogSeverity;
import com.morales.chemicallab.entity.Role;
import com.morales.chemicallab.entity.StudentProfile;
import com.morales.chemicallab.entity.SystemLog;
import com.morales.chemicallab.entity.TeacherProfile;
import com.morales.chemicallab.entity.UsageEvent;
import com.morales.chemicallab.entity.UsageModule;
import com.morales.chemicallab.entity.UserAccount;
import com.morales.chemicallab.repository.EvaluationAnswerRepository;
import com.morales.chemicallab.repository.EvaluationAssignmentRepository;
import com.morales.chemicallab.repository.EvaluationAttemptRepository;
import com.morales.chemicallab.repository.StudentProfileRepository;
import com.morales.chemicallab.repository.SystemLogRepository;
import com.morales.chemicallab.repository.TeacherProfileRepository;
import com.morales.chemicallab.repository.UsageEventRepository;
import com.morales.chemicallab.repository.UserAccountRepository;
import jakarta.persistence.EntityNotFoundException;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Registro de uso por estudiante (instrumento «Ficha de registro automático de uso del
 * sistema ChemicalLab»). Consolida, por usuario, los indicadores de uso que la plataforma
 * ya persiste: sesiones iniciadas (logs de login), módulos visitados (eventos de uso),
 * actividades asignadas/completadas (asignaciones e intentos de evaluación), intentos,
 * aciertos/errores (respuestas corregidas), retroalimentaciones del docente e incidencias
 * técnicas (logs con severidad de advertencia o error).
 *
 * <p><strong>Sin datos inventados:</strong> cada indicador se calcula solo sobre registros
 * reales. El tiempo total de uso viaja siempre como {@code null} porque la plataforma no
 * registra cierre de sesión ni duración confiable; los promedios y tasas viajan como
 * {@code null} cuando no hay denominador. Los indicadores de evaluación no aplican a
 * docentes ni administradores y también viajan como {@code null} para esos roles.</p>
 *
 * <p>Solo lectura y pensado para el dataset de un colegio: las agrupaciones se resuelven
 * en memoria sobre consultas acotadas, siguiendo el estilo de {@link AcademicSupervisionService}.
 * Nunca expone contraseñas, tokens, respuestas de estudiantes ni payloads de pizarra.</p>
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class StudentUsageRecordService {

    /** Estados que cuentan como intento enviado (actividad completada). */
    private static final List<AttemptStatus> SUBMITTED_STATUSES = List.of(
            AttemptStatus.SUBMITTED, AttemptStatus.PENDING_MANUAL_REVIEW, AttemptStatus.GRADED);

    /** Severidades de log que cuentan como incidencia técnica. */
    private static final Set<LogSeverity> INCIDENT_SEVERITIES =
            Set.of(LogSeverity.WARNING, LogSeverity.ERROR);

    private static final Pattern GRADE_PATTERN = Pattern.compile("^[1-5]$");
    private static final Pattern SECTION_PATTERN = Pattern.compile("^[A-Za-z]$");

    private static final int MAX_DETAIL_ITEMS = 20;

    private final UserAccountRepository userAccountRepository;
    private final StudentProfileRepository studentProfileRepository;
    private final TeacherProfileRepository teacherProfileRepository;
    private final UsageEventRepository usageEventRepository;
    private final SystemLogRepository systemLogRepository;
    private final EvaluationAttemptRepository evaluationAttemptRepository;
    private final EvaluationAnswerRepository evaluationAnswerRepository;
    private final EvaluationAssignmentRepository evaluationAssignmentRepository;

    // =========================================================================
    // LISTADO CONSOLIDADO
    // =========================================================================

    public StudentUsageRecordsResponse getRecords(String roleParam, String search, String gradeParam,
                                                  String sectionParam, String fromParam, String toParam,
                                                  String moduleParam, String onlyWithActivityParam) {
        Filters filters = parseFilters(roleParam, search, gradeParam, sectionParam,
                fromParam, toParam, moduleParam, onlyWithActivityParam);

        Context context = loadContext(filters);
        List<StudentUsageRecordResponse> records = buildRecords(filters, context);

        return new StudentUsageRecordsResponse(buildSummary(records, context), records);
    }

    // =========================================================================
    // DETALLE POR USUARIO
    // =========================================================================

    public StudentUsageRecordDetailResponse getDetail(Long userId) {
        UserAccount user = userAccountRepository.findById(userId)
                .orElseThrow(() -> new EntityNotFoundException("El usuario indicado no existe."));

        // Indicadores consolidados sin filtros (histórico completo del usuario).
        Filters filters = Filters.none();
        Context context = loadContext(filters);
        StudentUsageRecordResponse summary = toRecord(user, filters, context);

        List<StudentUsageRecordDetailResponse.UsageEventItem> recentEvents =
                usageEventRepository.findAll(userEventsSpecification(userId),
                                PageRequest.of(0, MAX_DETAIL_ITEMS, Sort.by(Sort.Direction.DESC, "occurredAt")))
                        .map(e -> new StudentUsageRecordDetailResponse.UsageEventItem(
                                e.getModule(), e.getEventType(), e.getResourceType(),
                                e.getDescription(), e.getOccurredAt()))
                        .getContent();

        List<StudentUsageRecordDetailResponse.IncidentItem> incidents =
                context.incidentsByUser.getOrDefault(userId, List.of()).stream()
                        .sorted(Comparator.comparing(SystemLog::getCreatedAt,
                                Comparator.nullsLast(Comparator.reverseOrder())))
                        .limit(MAX_DETAIL_ITEMS)
                        .map(log -> new StudentUsageRecordDetailResponse.IncidentItem(
                                log.getSeverity(), log.getEventType(), log.getAction(),
                                log.getDescription(), log.getCreatedAt()))
                        .toList();

        return new StudentUsageRecordDetailResponse(
                summary, recentEvents, buildEvaluationItems(user, context), incidents);
    }

    private List<StudentUsageRecordDetailResponse.EvaluationUsageItem> buildEvaluationItems(
            UserAccount user, Context context) {
        StudentProfile profile = context.studentsByUserId.get(user.getId());
        if (profile == null) {
            return List.of();
        }

        Set<Long> assignedIds = context.assignedEvaluationIds(profile);
        List<EvaluationAttempt> attempts =
                context.attemptsByStudentId.getOrDefault(profile.getId(), List.of());

        // Une evaluaciones asignadas y evaluaciones con intentos (aunque su asignación ya
        // no esté activa) para no ocultar actividad real del estudiante.
        Map<Long, Evaluation> evaluations = new LinkedHashMap<>();
        Map<Long, List<EvaluationAttempt>> attemptsByEvaluation = attempts.stream()
                .collect(Collectors.groupingBy(a -> a.getEvaluation().getId()));
        attempts.forEach(a -> evaluations.putIfAbsent(a.getEvaluation().getId(), a.getEvaluation()));
        context.assignmentsByGradeSection
                .getOrDefault(gradeSectionKey(profile.getGrade(), profile.getSection()), List.of())
                .forEach(a -> evaluations.putIfAbsent(a.getEvaluation().getId(), a.getEvaluation()));

        return evaluations.values().stream()
                .map(evaluation -> {
                    List<EvaluationAttempt> evaluationAttempts =
                            attemptsByEvaluation.getOrDefault(evaluation.getId(), List.of());
                    boolean completed = evaluationAttempts.stream()
                            .anyMatch(a -> SUBMITTED_STATUSES.contains(a.getStatus()));
                    LocalDateTime lastAttemptAt = evaluationAttempts.stream()
                            .map(a -> a.getSubmittedAt() != null ? a.getSubmittedAt() : a.getStartedAt())
                            .filter(Objects::nonNull)
                            .max(Comparator.naturalOrder())
                            .orElse(null);
                    return new StudentUsageRecordDetailResponse.EvaluationUsageItem(
                            evaluation.getId(),
                            evaluation.getTitle(),
                            assignedIds.contains(evaluation.getId()),
                            evaluationAttempts.size(),
                            completed,
                            lastAttemptAt);
                })
                .sorted(Comparator.comparing(StudentUsageRecordDetailResponse.EvaluationUsageItem::lastAttemptAt,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();
    }

    // =========================================================================
    // FILTROS
    // =========================================================================

    /** Filtros ya validados y normalizados del listado. */
    private record Filters(Role role, String search, String grade, String section,
                           LocalDateTime from, LocalDateTime to, UsageModule module,
                           boolean onlyStudentsWithActivity) {

        static Filters none() {
            return new Filters(null, null, null, null, null, null, null, false);
        }
    }

    /**
     * Valida y normaliza los parámetros del listado. Todos son opcionales; cualquier valor
     * inválido lanza {@link IllegalArgumentException}, que el manejador global convierte en
     * una respuesta 400 (nunca en un 500).
     */
    private Filters parseFilters(String roleParam, String search, String gradeParam, String sectionParam,
                                 String fromParam, String toParam, String moduleParam,
                                 String onlyWithActivityParam) {
        Role role = parseEnum(roleParam, Role.class, "El filtro de rol no es válido.");
        UsageModule module = parseEnum(moduleParam, UsageModule.class, "El filtro de módulo no es válido.");

        String grade = blankToNull(gradeParam);
        if (grade != null && !GRADE_PATTERN.matcher(grade.trim()).matches()) {
            throw new IllegalArgumentException("El grado debe ser un número entero del 1 al 5.");
        }
        String section = blankToNull(sectionParam);
        if (section != null) {
            if (!SECTION_PATTERN.matcher(section.trim()).matches()) {
                throw new IllegalArgumentException("La sección debe ser una sola letra (A-Z).");
            }
            section = section.trim().toUpperCase(Locale.ROOT);
        }

        LocalDate from = parseDate(fromParam, "La fecha «desde» no es válida (formato AAAA-MM-DD).");
        LocalDate to = parseDate(toParam, "La fecha «hasta» no es válida (formato AAAA-MM-DD).");
        if (from != null && to != null && from.isAfter(to)) {
            throw new IllegalArgumentException("La fecha «desde» no puede ser posterior a la fecha «hasta».");
        }

        boolean onlyWithActivity = parseBoolean(onlyWithActivityParam);

        return new Filters(role, blankToNull(search), grade == null ? null : grade.trim(), section,
                from == null ? null : from.atStartOfDay(),
                to == null ? null : to.atTime(LocalTime.MAX),
                module, onlyWithActivity);
    }

    private <E extends Enum<E>> E parseEnum(String value, Class<E> type, String message) {
        String normalized = blankToNull(value);
        if (normalized == null || normalized.trim().equalsIgnoreCase("TODOS")) {
            return null;
        }
        try {
            return Enum.valueOf(type, normalized.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException(message);
        }
    }

    private LocalDate parseDate(String value, String message) {
        String normalized = blankToNull(value);
        if (normalized == null) {
            return null;
        }
        try {
            return LocalDate.parse(normalized.trim());
        } catch (DateTimeParseException ex) {
            throw new IllegalArgumentException(message);
        }
    }

    private boolean parseBoolean(String value) {
        String normalized = blankToNull(value);
        if (normalized == null) {
            return false;
        }
        String trimmed = normalized.trim();
        if (trimmed.equalsIgnoreCase("true")) {
            return true;
        }
        if (trimmed.equalsIgnoreCase("false")) {
            return false;
        }
        throw new IllegalArgumentException("El filtro de actividad debe ser true o false.");
    }

    private String blankToNull(String value) {
        return (value == null || value.isBlank()) ? null : value;
    }

    // =========================================================================
    // CARGA Y AGRUPACIÓN DE DATOS REALES
    // =========================================================================

    /** Datos ya agrupados por usuario/estudiante sobre los que se calculan los indicadores. */
    private static final class Context {
        Map<Long, StudentProfile> studentsByUserId = Map.of();
        Map<Long, TeacherProfile> teachersByUserId = Map.of();
        Map<Long, List<UsageEvent>> eventsByUser = Map.of();
        Map<Long, List<SystemLog>> loginsByUser = Map.of();
        Map<Long, List<SystemLog>> incidentsByUser = Map.of();
        Map<Long, List<EvaluationAttempt>> attemptsByStudentId = Map.of();
        Map<Long, long[]> answerStatsByStudentId = Map.of();    // [aciertos, errores]
        Map<Long, Long> answerFeedbackByStudentId = Map.of();
        Map<String, List<EvaluationAssignment>> assignmentsByGradeSection = Map.of();

        Set<Long> assignedEvaluationIds(StudentProfile profile) {
            return assignmentsByGradeSection
                    .getOrDefault(gradeSectionKey(profile.getGrade(), profile.getSection()), List.of())
                    .stream()
                    .map(a -> a.getEvaluation().getId())
                    .collect(Collectors.toSet());
        }
    }

    private Context loadContext(Filters filters) {
        Context context = new Context();

        context.studentsByUserId = studentProfileRepository.findAll().stream()
                .collect(Collectors.toMap(p -> p.getUser().getId(), Function.identity(), (a, b) -> a));
        context.teachersByUserId = teacherProfileRepository.findAll().stream()
                .collect(Collectors.toMap(p -> p.getUser().getId(), Function.identity(), (a, b) -> a));

        context.eventsByUser = usageEventRepository.findAll(eventSpecification(filters)).stream()
                .collect(Collectors.groupingBy(UsageEvent::getUserId));

        // Una sola consulta de logs: logins exitosos (sesiones iniciadas) e incidencias
        // (advertencias/errores). Se separan en memoria; los logs sin actor no son atribuibles.
        Map<Boolean, List<SystemLog>> logs = systemLogRepository.findAll(logSpecification(filters)).stream()
                .filter(log -> log.getActorUserId() != null)
                .collect(Collectors.partitioningBy(log -> log.getEventType() == LogEventType.LOGIN_SUCCESS));
        context.loginsByUser = logs.get(true).stream()
                .collect(Collectors.groupingBy(SystemLog::getActorUserId));
        context.incidentsByUser = logs.get(false).stream()
                .filter(log -> INCIDENT_SEVERITIES.contains(log.getSeverity()))
                .collect(Collectors.groupingBy(SystemLog::getActorUserId));

        context.attemptsByStudentId = evaluationAttemptRepository.findAll().stream()
                .filter(a -> inRange(a.getStartedAt(), filters))
                .collect(Collectors.groupingBy(a -> a.getStudent().getId()));

        Map<Long, long[]> answerStats = new HashMap<>();
        evaluationAnswerRepository.findCorrectnessStats().stream()
                .filter(v -> inRange(v.getAnsweredAt(), filters))
                .forEach(v -> {
                    long[] stats = answerStats.computeIfAbsent(v.getStudentId(), k -> new long[2]);
                    stats[Boolean.TRUE.equals(v.getCorrect()) ? 0 : 1]++;
                });
        context.answerStatsByStudentId = answerStats;

        context.answerFeedbackByStudentId = evaluationAnswerRepository.findTeacherFeedbackStats().stream()
                .filter(v -> v.getReviewedAt() == null || inRange(v.getReviewedAt(), filters))
                .collect(Collectors.groupingBy(
                        EvaluationAnswerRepository.AnswerFeedbackView::getStudentId, Collectors.counting()));

        // Las actividades asignadas representan lo vigente para el grado/sección del
        // estudiante (asignación activa de evaluación publicada y activa); no se acotan por
        // el rango de fechas del listado.
        context.assignmentsByGradeSection = evaluationAssignmentRepository.findAll().stream()
                .filter(a -> Boolean.TRUE.equals(a.getActive()))
                .filter(a -> a.getEvaluation().getStatus() == EvaluationStatus.PUBLISHED
                        && Boolean.TRUE.equals(a.getEvaluation().getActive()))
                .collect(Collectors.groupingBy(a -> gradeSectionKey(a.getGrade(), a.getSection())));

        return context;
    }

    private Specification<UsageEvent> eventSpecification(Filters filters) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (filters.module() != null) {
                predicates.add(cb.equal(root.get("module"), filters.module()));
            }
            if (filters.from() != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("occurredAt"), filters.from()));
            }
            if (filters.to() != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("occurredAt"), filters.to()));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }

    private Specification<UsageEvent> userEventsSpecification(Long userId) {
        return (root, query, cb) -> cb.equal(root.get("userId"), userId);
    }

    private Specification<SystemLog> logSpecification(Filters filters) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.or(
                    cb.equal(root.get("eventType"), LogEventType.LOGIN_SUCCESS),
                    root.get("severity").in(INCIDENT_SEVERITIES)));
            if (filters.from() != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("createdAt"), filters.from()));
            }
            if (filters.to() != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("createdAt"), filters.to()));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }

    private boolean inRange(LocalDateTime moment, Filters filters) {
        if (moment == null) {
            return filters.from() == null && filters.to() == null;
        }
        if (filters.from() != null && moment.isBefore(filters.from())) {
            return false;
        }
        return filters.to() == null || !moment.isAfter(filters.to());
    }

    // =========================================================================
    // CONSTRUCCIÓN DE REGISTROS
    // =========================================================================

    private List<StudentUsageRecordResponse> buildRecords(Filters filters, Context context) {
        return userAccountRepository.findAll().stream()
                .filter(user -> filters.role() == null || user.getRole() == filters.role())
                .filter(user -> matchesGradeSection(user, filters, context))
                .filter(user -> matchesSearch(user, filters, context))
                .map(user -> toRecord(user, filters, context))
                .filter(record -> !filters.onlyStudentsWithActivity() || hasActivity(record))
                .sorted(Comparator
                        .comparing((StudentUsageRecordResponse r) -> r.role() != Role.ESTUDIANTE)
                        .thenComparing(r -> r.fullName() != null ? r.fullName() : r.username(),
                                String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    private boolean matchesGradeSection(UserAccount user, Filters filters, Context context) {
        if (filters.grade() == null && filters.section() == null) {
            return true;
        }
        StudentProfile profile = context.studentsByUserId.get(user.getId());
        if (profile == null) {
            return false;
        }
        if (filters.grade() != null && !filters.grade().equals(profile.getGrade())) {
            return false;
        }
        return filters.section() == null || filters.section().equalsIgnoreCase(profile.getSection());
    }

    private boolean matchesSearch(UserAccount user, Filters filters, Context context) {
        if (filters.search() == null) {
            return true;
        }
        String needle = filters.search().trim().toLowerCase(Locale.ROOT);
        StudentProfile student = context.studentsByUserId.get(user.getId());
        TeacherProfile teacher = context.teachersByUserId.get(user.getId());

        return containsIgnoreCase(user.getUsername(), needle)
                || containsIgnoreCase(user.getEmail(), needle)
                || (student != null && (containsIgnoreCase(student.getStudentCode(), needle)
                || containsIgnoreCase(student.getNames() + " " + student.getLastNames(), needle)))
                || (teacher != null && containsIgnoreCase(teacher.getNames() + " " + teacher.getLastNames(), needle));
    }

    private boolean containsIgnoreCase(String haystack, String needle) {
        return haystack != null && haystack.toLowerCase(Locale.ROOT).contains(needle);
    }

    private boolean hasActivity(StudentUsageRecordResponse record) {
        return (record.sessionsStarted() != null && record.sessionsStarted() > 0)
                || (record.visitedModulesCount() != null && record.visitedModulesCount() > 0)
                || (record.attemptsCount() != null && record.attemptsCount() > 0);
    }

    private StudentUsageRecordResponse toRecord(UserAccount user, Filters filters, Context context) {
        Long userId = user.getId();
        StudentProfile student = context.studentsByUserId.get(userId);
        TeacherProfile teacher = context.teachersByUserId.get(userId);

        List<UsageEvent> events = context.eventsByUser.getOrDefault(userId, List.of());
        List<SystemLog> logins = context.loginsByUser.getOrDefault(userId, List.of());
        List<SystemLog> incidents = context.incidentsByUser.getOrDefault(userId, List.of());

        Set<String> visitedModules = events.stream()
                .map(e -> e.getModule().name())
                .collect(Collectors.toCollection(TreeSet::new));

        LocalDateTime lastActivityAt = lastActivity(events, logins,
                student == null ? List.of() : context.attemptsByStudentId.getOrDefault(student.getId(), List.of()));

        // Indicadores de evaluación: solo aplican a estudiantes; para docentes y
        // administradores viajan como null (no como 0) para no simular datos.
        Long assigned = null;
        Long completed = null;
        Double progress = null;
        Long attemptsCount = null;
        Long correct = null;
        Long incorrect = null;
        Double accuracy = null;
        Long feedback = null;

        if (student != null && user.getRole() == Role.ESTUDIANTE) {
            List<EvaluationAttempt> attempts =
                    context.attemptsByStudentId.getOrDefault(student.getId(), List.of());
            Set<Long> assignedIds = context.assignedEvaluationIds(student);
            Set<Long> completedIds = attempts.stream()
                    .filter(a -> SUBMITTED_STATUSES.contains(a.getStatus()))
                    .filter(a -> inRange(a.getSubmittedAt(), filters))
                    .map(a -> a.getEvaluation().getId())
                    .filter(assignedIds::contains)
                    .collect(Collectors.toSet());

            assigned = (long) assignedIds.size();
            completed = (long) completedIds.size();
            progress = assigned == 0 ? null : round1(completed * 100.0 / assigned);

            attemptsCount = (long) attempts.size();
            long[] stats = context.answerStatsByStudentId.getOrDefault(student.getId(), new long[2]);
            correct = stats[0];
            incorrect = stats[1];
            long graded = stats[0] + stats[1];
            accuracy = graded == 0 ? null : round1(stats[0] * 100.0 / graded);

            long attemptFeedback = attempts.stream()
                    .filter(a -> a.getOverallFeedback() != null && !a.getOverallFeedback().isBlank())
                    .count();
            feedback = attemptFeedback
                    + context.answerFeedbackByStudentId.getOrDefault(student.getId(), 0L);
        }

        return new StudentUsageRecordResponse(
                userId,
                student == null ? null : student.getId(),
                student == null ? null : student.getStudentCode(),
                user.getUsername(),
                fullName(student, teacher),
                user.getRole(),
                student == null ? null : student.getGrade(),
                student == null ? null : student.getSection(),
                null, // tiempo total de uso: sin registro confiable de duración de sesión
                (long) logins.size(),
                visitedModules.size(),
                List.copyOf(visitedModules),
                assigned,
                completed,
                progress,
                attemptsCount,
                correct,
                incorrect,
                accuracy,
                feedback,
                (long) incidents.size(),
                incidentsSummary(incidents),
                lastActivityAt);
    }

    private LocalDateTime lastActivity(List<UsageEvent> events, List<SystemLog> logins,
                                       List<EvaluationAttempt> attempts) {
        List<LocalDateTime> moments = new ArrayList<>();
        events.forEach(e -> moments.add(e.getOccurredAt()));
        logins.forEach(l -> moments.add(l.getCreatedAt()));
        attempts.forEach(a -> {
            moments.add(a.getStartedAt());
            moments.add(a.getSubmittedAt());
        });
        return moments.stream()
                .filter(Objects::nonNull)
                .max(Comparator.naturalOrder())
                .orElse(null);
    }

    private String fullName(StudentProfile student, TeacherProfile teacher) {
        if (student != null) {
            return student.getNames() + " " + student.getLastNames();
        }
        if (teacher != null) {
            return teacher.getNames() + " " + teacher.getLastNames();
        }
        return null;
    }

    private String incidentsSummary(List<SystemLog> incidents) {
        if (incidents.isEmpty()) {
            return null;
        }
        long errors = incidents.stream().filter(l -> l.getSeverity() == LogSeverity.ERROR).count();
        long warnings = incidents.size() - errors;
        List<String> parts = new ArrayList<>();
        if (errors > 0) {
            parts.add(errors + (errors == 1 ? " error" : " errores"));
        }
        if (warnings > 0) {
            parts.add(warnings + (warnings == 1 ? " advertencia" : " advertencias"));
        }
        return String.join(" · ", parts);
    }

    private double round1(double value) {
        return Math.round(value * 10.0) / 10.0;
    }

    // =========================================================================
    // RESUMEN PARA LAS TARJETAS
    // =========================================================================

    private StudentUsageRecordsResponse.Summary buildSummary(List<StudentUsageRecordResponse> records,
                                                             Context context) {
        long studentsWithActivity = records.stream()
                .filter(r -> r.role() == Role.ESTUDIANTE)
                .filter(this::hasActivity)
                .count();

        Double averageProgress = average(records.stream()
                .map(StudentUsageRecordResponse::progressPercentage));
        Double averageAccuracy = average(records.stream()
                .map(StudentUsageRecordResponse::accuracyRate));

        long totalSessions = records.stream()
                .mapToLong(r -> r.sessionsStarted() == null ? 0 : r.sessionsStarted())
                .sum();

        // Módulo más visitado entre los usuarios incluidos en el listado filtrado.
        Set<Long> includedUsers = records.stream()
                .map(StudentUsageRecordResponse::userId)
                .collect(Collectors.toSet());
        Map<UsageModule, Long> moduleCounts = context.eventsByUser.entrySet().stream()
                .filter(e -> includedUsers.contains(e.getKey()))
                .flatMap(e -> e.getValue().stream())
                .collect(Collectors.groupingBy(UsageEvent::getModule, Collectors.counting()));
        Map.Entry<UsageModule, Long> topModule = moduleCounts.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .orElse(null);

        return new StudentUsageRecordsResponse.Summary(
                records.size(),
                studentsWithActivity,
                averageProgress,
                averageAccuracy,
                totalSessions,
                topModule == null ? null : topModule.getKey().name(),
                topModule == null ? null : topModule.getValue());
    }

    private Double average(java.util.stream.Stream<Double> values) {
        List<Double> present = values.filter(Objects::nonNull).toList();
        if (present.isEmpty()) {
            return null;
        }
        return round1(present.stream().mapToDouble(Double::doubleValue).average().orElse(0));
    }

    private static String gradeSectionKey(String grade, String section) {
        return (grade == null ? "" : grade.trim()) + "|"
                + (section == null ? "" : section.trim().toUpperCase(Locale.ROOT));
    }
}
