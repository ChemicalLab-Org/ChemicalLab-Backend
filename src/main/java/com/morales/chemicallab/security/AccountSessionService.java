package com.morales.chemicallab.security;

import com.morales.chemicallab.entity.AccountSession;
import com.morales.chemicallab.entity.UserAccount;
import com.morales.chemicallab.repository.AccountSessionRepository;
import com.morales.chemicallab.repository.UserAccountRepository;
import io.jsonwebtoken.JwtException;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityNotFoundException;
import jakarta.persistence.LockModeType;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AccountSessionService {
    private final AccountSessionRepository sessions;
    private final UserAccountRepository users;
    private final JwtService jwt;
    private final EntityManager entityManager;

    /** All account writers and login must lock before examining/changing credentials. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void lock(UserAccount user) {
        // refresh matters when the account was already loaded through a profile or OSIV.
        entityManager.refresh(user, LockModeType.PESSIMISTIC_WRITE);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public String issue(UserAccount lockedUser) {
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        AccountSession session = AccountSession.builder().id(UUID.randomUUID()).user(lockedUser)
                .credentialsVersion(lockedUser.getCredentialsVersion()).createdAt(now)
                .expiresAt(now.plusMillis(jwt.getExpirationMs()).truncatedTo(ChronoUnit.SECONDS)).build();
        sessions.save(session);
        return jwt.generateToken(lockedUser, session);
    }

    @Transactional(readOnly = true)
    public UsernamePasswordAuthenticationToken authenticate(String token) {
        try {
            var claims = jwt.parseClaims(token);
            if (claims.getId() == null) throw invalid();
            UUID id = UUID.fromString(claims.getId());
            Long version = claims.get("cv", Long.class);
            Long userId = claims.get("userId", Long.class);
            if (version == null || userId == null || claims.getExpiration() == null) throw invalid();
            AccountSession session = sessions.findWithUserById(id).orElseThrow(AccountSessionService::invalid);
            UserAccount user = session.getUser();
            validate(session, user, version);
            if (!user.getId().equals(userId) || !user.getUsername().equals(claims.getSubject())
                    || !session.getExpiresAt().equals(claims.getExpiration().toInstant())) throw invalid();
            var principal = new SessionPrincipal(user.getId(), user.getUsername(), id, version,
                    user.getRole(), Boolean.TRUE.equals(user.getTemporaryPassword()));
            return new UsernamePasswordAuthenticationToken(principal, null,
                    List.of(new SimpleGrantedAuthority("ROLE_" + user.getRole().name())));
        } catch (JwtException | IllegalArgumentException ex) {
            throw invalid();
        }
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public UserAccount currentLocked() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof SessionPrincipal principal)) throw invalid();
        UserAccount user = users.findById(principal.userId()).orElseThrow(AccountSessionService::invalid);
        lock(user);
        AccountSession session = sessions.findById(principal.sessionId()).orElseThrow(AccountSessionService::invalid);
        try {
            entityManager.refresh(session);
        } catch (EntityNotFoundException ex) {
            // Expiry cleanup may remove the row between lookup and refresh.
            throw invalid();
        }
        validate(session, user, principal.credentialsVersion());
        return user;
    }

    @Transactional
    public void logout(boolean all) {
        UserAccount user = currentLocked();
        if (all) {
            user.invalidateSessions();
        } else {
            var principal = (SessionPrincipal) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
            sessions.findById(principal.sessionId()).orElseThrow(AccountSessionService::invalid).setRevokedAt(Instant.now());
        }
    }

    private void validate(AccountSession session, UserAccount user, long version) {
        if (!Boolean.TRUE.equals(user.getActive()) || session.getRevokedAt() != null
                || !session.getExpiresAt().isAfter(Instant.now())
                || session.getCredentialsVersion() != version || user.getCredentialsVersion() != version
                || !session.getUser().getId().equals(user.getId())) throw invalid();
    }

    private static BadCredentialsException invalid() {
        return new BadCredentialsException("Sesión inválida, expirada o revocada.");
    }
}
