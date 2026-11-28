package com.morales.chemicallab.security;

import com.morales.chemicallab.entity.Role;
import com.morales.chemicallab.repository.AccountSessionRepository;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.BadCredentialsException;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LockedSessionExpiryTest {
    @Test void expirationIsCheckedAfterReadingTheDatabase() {
        var actor = new SessionPrincipal(10L, "FictitiousStudent", UUID.randomUUID(), 3, Role.ESTUDIANTE, false);
        var repository = mock(AccountSessionRepository.class);
        when(repository.findSessionValidity(actor.sessionId(), actor.userId(), actor.role(),
            actor.credentialsVersion())).thenAnswer(call -> {
                Instant expiry = Instant.now().plusMillis(20);
                while (Instant.now().isBefore(expiry)) Thread.sleep(5);
                var validity = mock(AccountSessionRepository.SessionValidity.class);
                when(validity.getUsername()).thenReturn(actor.username());
                when(validity.getExpiresAt()).thenReturn(expiry);
                return Optional.of(validity);
            });
        var sessions = new AccountSessionService(repository, null, null, null, null);

        assertThatThrownBy(() -> sessions.revalidateLocked(actor)).isInstanceOf(BadCredentialsException.class);
    }
}
