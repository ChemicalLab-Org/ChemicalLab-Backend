package com.morales.chemicallab.exception;

/** A rejected password-change form from an authenticated session, not an authentication failure. */
public class CurrentPasswordInvalidException extends RuntimeException {
    public CurrentPasswordInvalidException() {
        super("La contraseña actual es incorrecta.");
    }
}
