package com.morales.chemicallab.config;

import com.morales.chemicallab.entity.Role;
import com.morales.chemicallab.security.CustomUserDetailsService;
import com.morales.chemicallab.security.JwtAuthenticationFilter;
import com.morales.chemicallab.security.RestAccessDeniedHandler;
import com.morales.chemicallab.security.RestAuthenticationEntryPoint;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@RequiredArgsConstructor
public class SecurityConfig {

    // Nombres de roles sin prefijo ROLE_ — Spring Security lo agrega automáticamente al usar hasRole(...)
    private static final String ADMIN = Role.ADMINISTRADOR.name();
    private static final String DOCENTE = Role.DOCENTE.name();

    private final CustomUserDetailsService userDetailsService;
    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final RestAuthenticationEntryPoint authenticationEntryPoint;
    private final RestAccessDeniedHandler accessDeniedHandler;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                // API REST sin estado: no necesitamos CSRF (no hay cookies de sesión) ni sesiones HTTP
                .csrf(csrf -> csrf.disable())
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // Rutas públicas
                        .requestMatchers(HttpMethod.GET, "/api/health").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/auth/login").permitAll()

                        // Cambio de contraseña temporal — requiere autenticación JWT
                        .requestMatchers(HttpMethod.PATCH, "/api/auth/change-temporary-password").authenticated()

                        // Documentación Swagger / OpenAPI — útil en desarrollo
                        .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()

                        // Gestión de docentes — solo ADMINISTRADOR
                        .requestMatchers(HttpMethod.POST, "/api/users/teachers").hasRole(ADMIN)
                        .requestMatchers(HttpMethod.GET, "/api/users/teachers").hasRole(ADMIN)
                        .requestMatchers(HttpMethod.PATCH, "/api/users/teachers/*/deactivate").hasRole(ADMIN)

                        // Gestión de estudiantes — solo DOCENTE
                        .requestMatchers(HttpMethod.POST, "/api/users/teachers/*/students").hasRole(DOCENTE)
                        .requestMatchers(HttpMethod.GET, "/api/users/teachers/*/students").hasRole(DOCENTE)
                        .requestMatchers(HttpMethod.PUT, "/api/users/teachers/*/students/*").hasRole(DOCENTE)
                        .requestMatchers(HttpMethod.PATCH, "/api/users/teachers/*/students/*/deactivate").hasRole(DOCENTE)

                        // Desactivación general de usuarios — solo ADMINISTRADOR
                        .requestMatchers(HttpMethod.PATCH, "/api/users/*/deactivate").hasRole(ADMIN)

                        // Motor químico — cualquier usuario autenticado
                        .requestMatchers("/api/chemistry/**").authenticated()

                        // Cualquier otro endpoint requiere autenticación
                        .anyRequest().authenticated()
                )
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler)
                )
                .authenticationProvider(authenticationProvider())
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                .formLogin(form -> form.disable())
                .httpBasic(httpBasic -> httpBasic.disable());

        return http.build();
    }

    @Bean
    public AuthenticationProvider authenticationProvider() {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider(userDetailsService);
        provider.setPasswordEncoder(passwordEncoder());
        return provider;
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration config) throws Exception {
        return config.getAuthenticationManager();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
