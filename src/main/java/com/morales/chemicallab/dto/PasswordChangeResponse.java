package com.morales.chemicallab.dto;

public record PasswordChangeResponse(
        String message,
        Boolean temporaryPassword
) {}
