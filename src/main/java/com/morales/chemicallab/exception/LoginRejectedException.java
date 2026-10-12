package com.morales.chemicallab.exception;

import org.springframework.security.authentication.BadCredentialsException;

public final class LoginRejectedException extends BadCredentialsException {
    private final String auditReason;
    public LoginRejectedException(){this("PASSWORD_MISMATCH");}
    public LoginRejectedException(String reason){super("Usuario o contraseña incorrectos.");auditReason=reason;}
    public String auditReason(){return auditReason;}
}
