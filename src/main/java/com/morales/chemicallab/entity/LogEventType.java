package com.morales.chemicallab.entity;

/**
 * Tipo de evento de trazabilidad registrado por la plataforma. Cada tipo conoce la
 * categoría a la que pertenece por defecto, de modo que al registrar un evento no sea
 * necesario indicar ambos valores por separado.
 *
 * <p>No todos los tipos se utilizan todavía; se deja la estructura preparada para
 * crecer sin tener que modificar el modelo de datos.</p>
 */
public enum LogEventType {

    // Autenticación
    LOGIN_SUCCESS(LogCategory.AUTH),
    LOGIN_FAILED(LogCategory.AUTH),
    LOGOUT(LogCategory.AUTH),

    // Gestión de usuarios
    USER_CREATED(LogCategory.USER_MANAGEMENT),
    USER_UPDATED(LogCategory.USER_MANAGEMENT),
    USER_DEACTIVATED(LogCategory.USER_MANAGEMENT),
    USER_REACTIVATED(LogCategory.USER_MANAGEMENT),
    PASSWORD_RESET(LogCategory.USER_MANAGEMENT),

    // Contenidos conceptuales
    CONCEPT_CREATED(LogCategory.CONCEPT_CONTENT),
    CONCEPT_UPDATED(LogCategory.CONCEPT_CONTENT),
    CONCEPT_PUBLISHED(LogCategory.CONCEPT_CONTENT),
    CONCEPT_ARCHIVED(LogCategory.CONCEPT_CONTENT),
    CONCEPT_ASSIGNED(LogCategory.CONCEPT_CONTENT),
    CONCEPT_MATERIAL_ADDED(LogCategory.CONCEPT_CONTENT),
    CONCEPT_MATERIAL_REPLACED(LogCategory.CONCEPT_CONTENT),
    CONCEPT_MATERIAL_REMOVED(LogCategory.CONCEPT_CONTENT),
    CONCEPT_LINK_ADDED(LogCategory.CONCEPT_CONTENT),
    CONCEPT_LINK_REMOVED(LogCategory.CONCEPT_CONTENT),

    // Evaluaciones
    EVALUATION_CREATED(LogCategory.EVALUATION),
    EVALUATION_UPDATED(LogCategory.EVALUATION),
    EVALUATION_CONFIG_UPDATED(LogCategory.EVALUATION),
    EVALUATION_PUBLISHED(LogCategory.EVALUATION),
    EVALUATION_ARCHIVED(LogCategory.EVALUATION),
    EVALUATION_ASSIGNED(LogCategory.EVALUATION),
    // El docente creó o editó una pregunta abierta (revisión manual).
    EVALUATION_OPEN_QUESTION_SAVED(LogCategory.EVALUATION),
    EVALUATION_ATTEMPT_STARTED(LogCategory.EVALUATION),
    EVALUATION_ATTEMPT_SUBMITTED(LogCategory.EVALUATION),
    // El intento quedó pendiente de revisión manual por contener preguntas abiertas.
    EVALUATION_ATTEMPT_PENDING_REVIEW(LogCategory.EVALUATION),
    // El docente revisó/calificó manualmente una respuesta abierta.
    EVALUATION_ANSWER_REVIEWED(LogCategory.EVALUATION),
    // Se completó la revisión manual de un intento y se recalculó la nota final.
    EVALUATION_REVIEW_COMPLETED(LogCategory.EVALUATION),

    // Resultados
    RESULT_VIEWED(LogCategory.RESULTS),

    // Sistema / administración
    SYSTEM_HEALTH_CHECK(LogCategory.SYSTEM),
    ADMIN_ACTION(LogCategory.ADMIN);

    private final LogCategory defaultCategory;

    LogEventType(LogCategory defaultCategory) {
        this.defaultCategory = defaultCategory;
    }

    public LogCategory getDefaultCategory() {
        return defaultCategory;
    }
}
