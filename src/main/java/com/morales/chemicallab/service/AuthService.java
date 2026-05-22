package com.morales.chemicallab.service;

import com.morales.chemicallab.dto.AuthResponse;
import com.morales.chemicallab.dto.LoginRequest;
import com.morales.chemicallab.entity.UserAccount;
import com.morales.chemicallab.repository.UserAccountRepository;
import com.morales.chemicallab.security.JwtService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
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
