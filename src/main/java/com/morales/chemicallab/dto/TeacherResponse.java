package com.morales.chemicallab.dto;

public record TeacherResponse(
        Long id,
        Long userId,
        String username,
        String email,
        String names,
        String lastNames,
        Boolean active,
        Boolean temporaryPassword
) {}
