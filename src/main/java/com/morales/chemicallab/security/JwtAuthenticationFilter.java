package com.morales.chemicallab.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;

@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {
    private final AccountSessionService sessions;
    private final RestAuthenticationEntryPoint entryPoint;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null) {
            chain.doFilter(request, response);
            return;
        }
        try {
            if (!header.startsWith("Bearer ")) {
                throw new org.springframework.security.authentication.BadCredentialsException("Bearer requerido.");
            }
            var authentication = sessions.authenticate(header.substring(7).trim());
            authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
            SecurityContextHolder.getContext().setAuthentication(authentication);
            var principal = (SessionPrincipal) authentication.getPrincipal();
            if (principal.temporaryPassword() && !allowedWhileTemporary(request)) {
                response.setStatus(403);
                response.setContentType("application/json");
                response.setCharacterEncoding("UTF-8");
                response.getWriter().write("{\"status\":403,\"code\":\"PASSWORD_CHANGE_REQUIRED\",\"message\":\"Debe cambiar su contraseña.\"}");
                return;
            }
        } catch (AuthenticationException ex) {
            SecurityContextHolder.clearContext();
            entryPoint.commence(request, response, ex);
            return;
        }
        // Do not catch downstream controller exceptions as authentication failures.
        chain.doFilter(request, response);
    }

    private boolean allowedWhileTemporary(HttpServletRequest request) {
        String path = request.getServletPath();
        return switch (request.getMethod()) {
            case "GET" -> path.equals("/api/auth/me");
            case "PATCH" -> path.equals("/api/auth/change-temporary-password");
            case "POST" -> path.equals("/api/auth/logout") || path.equals("/api/auth/logout-all");
            default -> false;
        };
    }
}
