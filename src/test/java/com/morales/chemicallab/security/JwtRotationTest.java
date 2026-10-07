package com.morales.chemicallab.security;

import com.morales.chemicallab.entity.Role;
import com.morales.chemicallab.entity.UserAccount;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.core.userdetails.User;

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
        var details = User.withUsername(account.getUsername()).password("unused").authorities("ADMINISTRADOR").build();
        String token = oldService.generateToken(account);
        assertThat(oldService.isTokenValid(token, details)).isTrue();
        assertThat(newService.isTokenValid(token, details)).isFalse();
        assertThat(newService.isTokenValid(newService.generateToken(account), details)).isTrue();
    }

    private String key() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getEncoder().encodeToString(bytes);
    }
}
