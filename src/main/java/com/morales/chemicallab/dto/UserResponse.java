package com.morales.chemicallab.dto;

import com.morales.chemicallab.entity.Role;
import java.time.LocalDateTime;

public record UserResponse(
        Long id,
        String username,
        String email,
        Role role,
        Boolean active,
        Boolean temporaryPassword,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {}
