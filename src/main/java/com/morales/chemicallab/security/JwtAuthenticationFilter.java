package com.morales.chemicallab.security;

import com.morales.chemicallab.agreement.AgreementService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.dao.DataAccessException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;

@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {
    private static final AntPathMatcher PATHS = new AntPathMatcher();
    private static final String CLASS_PATH = "/api/institutions/*/classes/*";
    private static final String AGREEMENTS = "/api/agreements";
    private final AccountSessionService sessions;
    private final RestAuthenticationEntryPoint entryPoint;
    private final AgreementService agreements;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getServletPath();
        return read(request) && (path.equals(AGREEMENTS + "/public") || PATHS.match(AGREEMENTS + "/public/*", path));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null) {
            chain.doFilter(request, response);
            return;
        }
        SessionPrincipal principal;
        try {
            if (!header.startsWith("Bearer ")) {
                throw new org.springframework.security.authentication.BadCredentialsException("Bearer requerido.");
            }
            var authentication = sessions.authenticate(header.substring(7).trim());
            authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
            SecurityContextHolder.getContext().setAuthentication(authentication);
            principal = (SessionPrincipal) authentication.getPrincipal();
            request.setAttribute(com.morales.chemicallab.security.audit.AuditRequestFilter.ACTOR,principal);
        } catch (AuthenticationException ex) {
            SecurityContextHolder.clearContext();
            entryPoint.commence(request, response, ex);
            return;
        }
        if (!recoveryOrAgreement(request)) {
            if (principal.temporaryPassword()) {
                reject(response, 403, "PASSWORD_CHANGE_REQUIRED", "Debe cambiar su contraseña.");
                return;
            }
            if (request.getServletPath().startsWith("/api/") && !publishedHistory(request) && !subscriptionManagement(request)) {
                try {
                    String path = request.getServletPath();
                    Long institution = institution(path);
                    var pending = agreements.pendingScope(principal.userId(), institution);
                    if (pending != null) {
                        Long affected = pending == AgreementService.PendingScope.INSTITUTION ? institution : null;
                        reject(response, 403, "AGREEMENTS_REQUIRED", "Revise los acuerdos pendientes para continuar.", affected);
                        return;
                    }
                    if (institution != null && path.contains("/classes/") && agreements.academicHold(principal.userId(), institution)) {
                        reject(response, 403, "ACADEMIC_AUTHORIZATION_REQUIRED", "La participación académica está pendiente en este colegio.", institution);
                        return;
                    }
                } catch (DataAccessException ex) {
                    reject(response, 503, "AGREEMENTS_UNAVAILABLE", "No se pudieron comprobar las condiciones de acceso.");
                    return;
                }
            }
        }
        // Do not catch downstream controller exceptions as authentication failures.
        chain.doFilter(request, response);
    }

    private static boolean subscriptionManagement(HttpServletRequest request) {
        String path = request.getServletPath();
        return path.equals("/api/subscriptions/v1/institutions")
                || PATHS.match("/api/institutions/*/subscription/v1", path)
                || PATHS.match("/api/institutions/*/subscription/v1/*", path);
    }

    private static boolean publishedHistory(HttpServletRequest request) {
        if (!read(request)) return false;
        String path = request.getServletPath();
        return path.equals("/api/evaluations/student/results")
                || PATHS.match("/api/evaluations/student/attempts/*/result", path)
                || PATHS.match(CLASS_PATH + "/evaluation-execution/NEW/v1/evaluations/*/deliveries/*/revisions/*/own-attempts/*/correction", path)
                || PATHS.match(CLASS_PATH + "/evaluation-reads/*/evaluations/*/deliveries/*/revisions/*/own-attempts/*/correction", path)
                || PATHS.match(CLASS_PATH + "/whiteboards/v1/student/history", path)
                || PATHS.match(CLASS_PATH + "/whiteboards/v1/student/*/snapshot", path);
    }

    private static boolean recoveryOrAgreement(HttpServletRequest request) {
        String path = request.getServletPath();
        return switch (request.getMethod()) {
            case "GET", "HEAD" -> path.equals("/api/auth/me") || path.equals("/api/health")
                    || path.equals(AGREEMENTS + "/account") || path.equals(AGREEMENTS + "/account/history")
                    || path.equals(AGREEMENTS + "/account/family-status") || PATHS.match(AGREEMENTS + "/account/versions/*", path);
            case "PATCH" -> path.equals("/api/auth/change-temporary-password");
            case "POST" -> path.equals("/api/auth/logout") || path.equals("/api/auth/logout-all")
                    || path.equals("/api/auth/login") || PATHS.match(AGREEMENTS + "/account/versions/*/decisions", path);
            default -> false;
        };
    }

    private static boolean read(HttpServletRequest request) {
        return "GET".equals(request.getMethod()) || "HEAD".equals(request.getMethod());
    }

    private static Long institution(String path) {
        String prefix = "/api/institutions/";
        if (!path.startsWith(prefix)) return null;
        int end = path.indexOf('/', prefix.length());
        String segment = end == -1 ? path.substring(prefix.length()) : path.substring(prefix.length(), end);
        try { return Long.valueOf(segment); }
        catch (NumberFormatException ex) { return null; } // The institution catalog has a nonnumeric segment.
    }

    private static void reject(HttpServletResponse response, int status, String code, String message) throws IOException {
        reject(response, status, code, message, null);
    }

    private static void reject(HttpServletResponse response, int status, String code, String message, Long institution) throws IOException {
        response.setStatus(status);
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        String scope = institution == null ? "" : ",\"institutionId\":" + institution;
        response.getWriter().write("{\"status\":" + status + ",\"code\":\"" + code + "\",\"message\":\"" + message + "\"" + scope + "}");
    }
}
