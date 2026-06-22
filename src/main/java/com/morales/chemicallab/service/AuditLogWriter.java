package com.morales.chemicallab.service;

import com.morales.chemicallab.entity.SystemLog;
import com.morales.chemicallab.repository.SystemLogRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Persiste un evento de trazabilidad en una transacción independiente.
 *
 * <p>Se usa {@code REQUIRES_NEW} a propósito: el guardado del log no debe compartir la
 * transacción de la operación principal (login, creación de usuario, publicación de
 * evaluación, etc.). Así, si el registro del log falla, no contamina ni revierte la
 * transacción que originó el evento. El manejo del error queda a cargo de quien invoca
 * (ver {@link AuditLogService}).</p>
 */
@Component
@RequiredArgsConstructor
public class AuditLogWriter {

    private final SystemLogRepository systemLogRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void persist(SystemLog log) {
        systemLogRepository.save(log);
    }
}
