package com.morales.chemicallab.service;

import com.morales.chemicallab.dto.AuthResponse;
import com.morales.chemicallab.dto.ChangePasswordRequest;
import com.morales.chemicallab.dto.CurrentUserResponse;
import com.morales.chemicallab.dto.LoginRequest;
import com.morales.chemicallab.dto.PasswordChangeResponse;
import com.morales.chemicallab.entity.Role;
import com.morales.chemicallab.entity.UserAccount;
import com.morales.chemicallab.exception.CurrentPasswordInvalidException;
import com.morales.chemicallab.repository.StudentProfileRepository;
import com.morales.chemicallab.repository.TeacherProfileRepository;
import com.morales.chemicallab.repository.UserAccountRepository;
import com.morales.chemicallab.security.AccountSessionService;
import com.morales.chemicallab.security.AuthenticationLimits;
import com.morales.chemicallab.security.CompatiblePasswordEncoder;
import com.morales.chemicallab.exception.LoginRejectedException;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AuthService {

    private static final String BEARER = "Bearer";

    private final UserAccountRepository userAccountRepository;
    private final TeacherProfileRepository teacherProfileRepository;
    private final StudentProfileRepository studentProfileRepository;
    private final PasswordEncoder passwordEncoder;
    private final AccountSessionService sessions;
    private final AuditLogService auditLogService;

    @Transactional
    public PasswordChangeResponse changeTemporaryPassword(ChangePasswordRequest request) {
        UserAccount user = sessions.currentLocked();

        if (!passwordEncoder.matches(request.currentPassword(), user.getPassword())) {
            throw new CurrentPasswordInvalidException();
        }

        if (!request.newPassword().equals(request.confirmPassword())) {
            throw new IllegalArgumentException("La nueva contraseña y su confirmación no coinciden.");
        }

        if (passwordEncoder.matches(request.newPassword(), user.getPassword())) {
            throw new IllegalArgumentException("La nueva contraseña debe ser diferente a la contraseña actual.");
        }

        user.setPassword(passwordEncoder.encode(request.newPassword()));
        user.setTemporaryPassword(false);
        userAccountRepository.save(user);

        return new PasswordChangeResponse("La contraseña fue actualizada correctamente.", false,
                sessions.issue(user), BEARER);
    }

    /**
     * Devuelve los datos del usuario autenticado a partir del token JWT vigente. Lo usa el
     * frontend al arrancar para validar que la sesión almacenada sigue siendo válida. Lanza
     * BadCredentialsException si no hay autenticación (traducida a 401) y DisabledException si
     * la cuenta quedó inactiva, de modo que el cliente limpie la sesión y exija un nuevo login.
     * No expone el token, el hash de la contraseña ni ningún dato sensible.
     */
    public CurrentUserResponse getCurrentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken) {
            throw new BadCredentialsException("Usuario no autenticado.");
        }

        UserAccount user = userAccountRepository.findByUsername(authentication.getName())
                .orElseThrow(() -> new EntityNotFoundException("Usuario no encontrado."));

        if (Boolean.FALSE.equals(user.getActive())) {
            throw new DisabledException("La cuenta se encuentra inactiva.");
        }

        ProfileNames profileNames = Boolean.TRUE.equals(user.getTemporaryPassword())
                ? ProfileNames.EMPTY : resolveProfileNames(user);

        return new CurrentUserResponse(
                user.getId(),
                user.getUsername(),
                Boolean.TRUE.equals(user.getTemporaryPassword()) ? null : user.getEmail(),
                profileNames.names(),
                profileNames.lastNames(),
                user.getRole(),
                user.getActive(),
                user.getTemporaryPassword()
        );
    }

    @Transactional
    public AuthResponse login(LoginRequest request, AuthenticationLimits.LoginBudget budget) {
        String identifier = request.usernameOrEmail().trim();

        // Consultar ambas rutas para correos, conservando la prioridad de username.
        Optional<UserAccount> userOpt = userAccountRepository.findByUsername(identifier);
        if (identifier.contains("@")) {
            Optional<UserAccount> emailMatch = userAccountRepository.findByEmail(identifier);
            if (userOpt.isEmpty()) userOpt = emailMatch;
        }

        UserAccount user = userOpt.orElse(null);
        // The controller reserved budgets before this transaction: never hold an account
        // connection/lock while waiting for a second connection to the counter store.
        // Bind admission to the resolved ID, in case identity ownership changed meanwhile.
        boolean allowed = budget.allowed() && identifier.equals(budget.identifier())
                && java.util.Objects.equals(budget.accountId(), user == null ? null : user.getId());
        String verifiedHash = user == null ? null : user.getPassword();
        // Saturated accounts get the same cryptographic work, but cannot test their real
        // secret, issue a session or migrate a hash. Never expose the hidden budget.
        boolean matches = passwordEncoder.matches(request.password(), !allowed ? null : verifiedHash);
        boolean identity = user != null && (identifier.equals(user.getUsername()) || identifier.equals(user.getEmail()));
        boolean active = user != null && Boolean.TRUE.equals(user.getActive());
        if (!allowed || !matches || !identity || !active) {
            String reason = !allowed ? "ACCOUNT_RATE_LIMITED" : user == null ? "ACCOUNT_NOT_FOUND" : !active ? "ACCOUNT_DISABLED" : !identity ? "IDENTITY_CHANGED" : "PASSWORD_MISMATCH";
            auditLogService.recordLoginFailed(identifier, reason);
            throw new LoginRejectedException();
        }
        // Failed attempts never serialize on a real account's row: otherwise parallel
        // guesses could reveal alias association by waiting for another verification.
        sessions.lock(user);
        boolean stillMatches = java.util.Objects.equals(verifiedHash, user.getPassword())
                || passwordEncoder.matches(request.password(), user.getPassword());
        if (!stillMatches || !Boolean.TRUE.equals(user.getActive())
                || !(identifier.equals(user.getUsername()) || identifier.equals(user.getEmail()))) {
            auditLogService.recordLoginFailed(identifier, "ACCOUNT_CHANGED");
            throw new LoginRejectedException();
        }
        if (passwordEncoder instanceof CompatiblePasswordEncoder compatible && compatible.upgradeEncoding(user.getPassword())) {
            user.upgradePasswordHash(user.getPassword(), compatible.upgradeVerified(request.password()));
        }

        String token = sessions.issue(user);

        auditLogService.recordLoginSuccess(user);
        ProfileNames profileNames = Boolean.TRUE.equals(user.getTemporaryPassword())
                ? ProfileNames.EMPTY : resolveProfileNames(user);

        return new AuthResponse(
                token,
                BEARER,
                user.getId(),
                user.getUsername(),
                Boolean.TRUE.equals(user.getTemporaryPassword()) ? null : user.getEmail(),
                profileNames.names(),
                profileNames.lastNames(),
                user.getRole(),
                user.getActive(),
                user.getTemporaryPassword()
        );
    }

    @Transactional
    public void logout(boolean all) { sessions.logout(all); }

    private ProfileNames resolveProfileNames(UserAccount user) {
        if (user.getRole() == Role.DOCENTE) {
            return teacherProfileRepository.findByUser(user)
                    .map(profile -> new ProfileNames(profile.getNames(), profile.getLastNames()))
                    .orElse(ProfileNames.EMPTY);
        }
        if (user.getRole() == Role.ESTUDIANTE) {
            return studentProfileRepository.findByUser_Id(user.getId())
                    .map(profile -> new ProfileNames(profile.getNames(), profile.getLastNames()))
                    .orElse(ProfileNames.EMPTY);
        }
        return ProfileNames.EMPTY;
    }

    private record ProfileNames(String names, String lastNames) {
        private static final ProfileNames EMPTY = new ProfileNames(null, null);
    }
}
