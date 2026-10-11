package com.morales.chemicallab.exception;

import org.springframework.security.authentication.BadCredentialsException;

public final class LoginRejectedException extends BadCredentialsException {
    public LoginRejectedException() { super("Usuario o contraseña incorrectos."); }
}
