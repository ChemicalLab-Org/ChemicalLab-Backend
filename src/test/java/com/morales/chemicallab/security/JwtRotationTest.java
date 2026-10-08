package com.morales.chemicallab.security;

import com.morales.chemicallab.entity.Role;
import com.morales.chemicallab.entity.UserAccount;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import com.morales.chemicallab.entity.AccountSession;
import java.time.Instant;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.security.SecureRandom;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

class JwtRotationTest {
    @Test
    void rotatingKeyRejectsPreviouslyIssuedTokenAndAcceptsNewToken() {
        var environment = new MockEnvironment();
        environment.setActiveProfiles("prod");
        var oldService = new JwtService(key(), 60_000, environment);
        var newService = new JwtService(key(), 60_000, environment);
        var account = UserAccount.builder().id(1L).username("fictitiousadmin").role(Role.ADMINISTRADOR).build();
        var session = AccountSession.builder().id(UUID.randomUUID()).user(account)
                .createdAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).build();
        String token = oldService.generateToken(account, session);
        assertThat(oldService.parseClaims(token).getSubject()).isEqualTo(account.getUsername());
        assertThatThrownBy(() -> newService.parseClaims(token)).isInstanceOf(io.jsonwebtoken.JwtException.class);
        assertThat(newService.parseClaims(newService.generateToken(account, session)).getSubject())
                .isEqualTo(account.getUsername());
    }

    private String key() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getEncoder().encodeToString(bytes);
    }
}
