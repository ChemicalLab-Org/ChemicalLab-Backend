package com.morales.chemicallab.security;

import com.morales.chemicallab.entity.UserAccount;
import com.morales.chemicallab.repository.UserAccountRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CustomUserDetailsService implements UserDetailsService {

    // Prefijo requerido por Spring Security para que hasRole("DOCENTE") funcione correctamente
    private static final String ROLE_PREFIX = "ROLE_";

    private final UserAccountRepository userAccountRepository;

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        UserAccount user = userAccountRepository.findByUsername(username)
                .orElseThrow(() -> new UsernameNotFoundException(
                        "Usuario no encontrado: " + username
                ));

        // Construir UserDetails sin exponer la contraseña en respuestas — solo se usa internamente para autenticación
        return User.builder()
                .username(user.getUsername())
                .password(user.getPassword())
                .disabled(Boolean.FALSE.equals(user.getActive()))
                .authorities(Collections.singletonList(
                        new SimpleGrantedAuthority(ROLE_PREFIX + user.getRole().name())
                ))
                .build();
    }
}
