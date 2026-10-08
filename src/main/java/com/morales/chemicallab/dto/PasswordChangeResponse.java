package com.morales.chemicallab.dto;

public record PasswordChangeResponse(String message, Boolean temporaryPassword, String token, String tokenType) {
    // Administrative resets do not issue a session for the target account.
    public PasswordChangeResponse(String message, Boolean temporaryPassword) {
        this(message, temporaryPassword, null, null);
    }
}
