package com.morales.chemicallab.exception;

public final class AuthenticationLimitException extends RuntimeException {
    private final int status;
    private final long retryAfter;
    public AuthenticationLimitException(int status, long retryAfter) {
        super(status == 429 ? "Demasiados intentos. Espera antes de volver a intentarlo."
                : "La autenticación no está disponible temporalmente. Inténtalo más tarde.");
        this.status = status;
        this.retryAfter = Math.max(1, retryAfter);
    }
    public int status() { return status; }
    public long retryAfter() { return retryAfter; }
}
