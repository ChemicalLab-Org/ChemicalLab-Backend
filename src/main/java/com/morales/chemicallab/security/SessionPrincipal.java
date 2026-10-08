package com.morales.chemicallab.security;

import com.morales.chemicallab.entity.Role;
import java.security.Principal;
import java.util.UUID;

/** No JWT, password or mutable JPA entity is stored in the security context. */
public record SessionPrincipal(Long userId, String username, UUID sessionId,
                               long credentialsVersion, Role role, boolean temporaryPassword) implements Principal {
    @Override public String getName() { return username; }
}
