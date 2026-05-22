package com.morales.chemicallab.dto;

public record StudentResponse(
        Long id,
        Long userId,
        String studentCode,
        String username,
        String names,
        String lastNames,
        String grade,
        String section,
        Boolean active,
        Boolean temporaryPassword,
        Long teacherId
) {}
