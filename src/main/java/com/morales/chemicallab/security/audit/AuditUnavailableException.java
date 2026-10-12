package com.morales.chemicallab.security.audit;

/** Deliberately excludes database messages, SQL and nested causes from public/log output. */
public final class AuditUnavailableException extends RuntimeException {
    public AuditUnavailableException(){super("No se pudo confirmar la evidencia obligatoria de la operación.");}
}
