package com.morales.chemicallab.dto;

import java.time.LocalDateTime;

public record ConceptAssignmentResponse(
        Long id,
        String grade,
        String section,
        Boolean active,
        LocalDateTime assignedAt
) {}
