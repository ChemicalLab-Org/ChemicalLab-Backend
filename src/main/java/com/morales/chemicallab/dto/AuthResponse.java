package com.morales.chemicallab.dto;

import com.morales.chemicallab.entity.Role;

public record AuthResponse(
        String token,
        String tokenType,
        Long userId,
        String username,
        String email,
        Role role,
        Boolean active,
        Boolean temporaryPassword
) {}
