package com.morales.chemicallab.repository;

import com.morales.chemicallab.entity.AttemptEventType;
import com.morales.chemicallab.entity.EvaluationAttempt;
import com.morales.chemicallab.entity.EvaluationAttemptEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface EvaluationAttemptEventRepository extends JpaRepository<EvaluationAttemptEvent, Long> {

    // Total de eventos registrados en un intento.
    long countByAttempt(EvaluationAttempt attempt);

    // Cuenta de eventos de un tipo concreto (p. ej. solo "salidas": TAB_HIDDEN/WINDOW_BLUR).
    long countByAttemptAndEventTypeIn(EvaluationAttempt attempt, Collection<AttemptEventType> eventTypes);

    // Último evento del intento (control simple de duplicados/throttling).
    Optional<EvaluationAttemptEvent> findFirstByAttemptOrderByOccurredAtDesc(EvaluationAttempt attempt);

    // Línea de tiempo del intento, en orden cronológico, para la vista de trazabilidad docente.
    List<EvaluationAttemptEvent> findByAttemptOrderByOccurredAtAsc(EvaluationAttempt attempt);
}
