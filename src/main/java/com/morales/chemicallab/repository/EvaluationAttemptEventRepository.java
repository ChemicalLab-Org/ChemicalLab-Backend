package com.morales.chemicallab.repository;

import com.morales.chemicallab.entity.AttemptEventType;
import com.morales.chemicallab.entity.EvaluationAttempt;
import com.morales.chemicallab.entity.EvaluationAttemptEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.Optional;

@Repository
public interface EvaluationAttemptEventRepository extends JpaRepository<EvaluationAttemptEvent, Long> {

    // Total de incidencias registradas en un intento.
    long countByAttempt(EvaluationAttempt attempt);

    // Cuenta de incidencias de un tipo concreto (p. ej. solo "salidas": TAB_HIDDEN/WINDOW_BLUR).
    long countByAttemptAndEventTypeIn(EvaluationAttempt attempt, Collection<AttemptEventType> eventTypes);

    // Última incidencia del intento (control simple de duplicados/throttling).
    Optional<EvaluationAttemptEvent> findFirstByAttemptOrderByOccurredAtDesc(EvaluationAttempt attempt);
}
