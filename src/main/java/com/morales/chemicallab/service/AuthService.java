package com.morales.chemicallab.service;

import com.morales.chemicallab.dto.AuthResponse;
import com.morales.chemicallab.dto.ChangePasswordRequest;
import com.morales.chemicallab.dto.LoginRequest;
import com.morales.chemicallab.dto.PasswordChangeResponse;
import com.morales.chemicallab.entity.UserAccount;
import com.morales.chemicallab.repository.UserAccountRepository;
import com.morales.chemicallab.security.JwtService;
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
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    @Transactional
    public PasswordChangeResponse changeTemporaryPassword(ChangePasswordRequest request) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken) {
            throw new BadCredentialsException("Usuario no autenticado.");
        }

        String username = authentication.getName();

        UserAccount user = userAccountRepository.findByUsername(username)
                .orElseThrow(() -> new EntityNotFoundException("Usuario no encontrado."));

        if (Boolean.FALSE.equals(user.getActive())) {
            throw new DisabledException("La cuenta se encuentra inactiva.");
        }

        if (!passwordEncoder.matches(request.currentPassword(), user.getPassword())) {
            throw new BadCredentialsException("La contraseña actual es incorrecta.");
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

        return new PasswordChangeResponse("La contraseña fue actualizada correctamente.", false);
    }

    public AuthResponse login(LoginRequest request) {
        String identifier = request.usernameOrEmail().trim();

        // Buscar primero por username; si no existe y el identificador parece correo, buscar por email
        Optional<UserAccount> userOpt = userAccountRepository.findByUsername(identifier);
        if (userOpt.isEmpty() && identifier.contains("@")) {
            userOpt = userAccountRepository.findByEmail(identifier);
        }

        UserAccount user = userOpt.orElseThrow(
                () -> new BadCredentialsException("El usuario o correo no está registrado.")
        );

        if (Boolean.FALSE.equals(user.getActive())) {
            throw new DisabledException("La cuenta se encuentra inactiva. Contacte al administrador.");
        }

        if (!passwordEncoder.matches(request.password(), user.getPassword())) {
            throw new BadCredentialsException("La contraseña es incorrecta.");
        }

        String token = jwtService.generateToken(user);

        return new AuthResponse(
                token,
                BEARER,
                user.getId(),
                user.getUsername(),
                user.getEmail(),
                user.getRole(),
                user.getActive(),
                user.getTemporaryPassword()
        );
    }
}
