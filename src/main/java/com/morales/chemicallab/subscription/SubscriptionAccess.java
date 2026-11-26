package com.morales.chemicallab.subscription;

import com.morales.chemicallab.security.SessionPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.Map;

@Configuration
public class SubscriptionAccess implements WebMvcConfigurer {
    private static final AntPathMatcher PATHS = new AntPathMatcher();
    private static final String CLASS = "/api/institutions/*/classes/*";
    private final SubscriptionSettings settings;
    private final SubscriptionLedger ledger;
    private final JdbcClient sql;

    public SubscriptionAccess(SubscriptionSettings settings, SubscriptionLedger ledger, JdbcClient sql) {
        this.settings = settings;
        this.ledger = ledger;
        this.sql = sql;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new HandlerInterceptor() {
            @Override
            public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws java.io.IOException {
                if (!settings.enabled() || !requiresEntitlement(request.getMethod(), request.getServletPath())) return true;
                var authentication = SecurityContextHolder.getContext().getAuthentication();
                if (authentication == null || !(authentication.getPrincipal() instanceof SessionPrincipal actor)) return true;
                if (!(request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE) instanceof Map<?, ?> variables)) return true;
                Object scope = variables.get("institutionId");
                if (scope == null) scope = variables.get("institution");
                if (scope == null || !scope.toString().matches("[0-9]{1,18}")) return true;
                long school = Long.parseLong(scope.toString());
                if (!ledger.blocked(school) || !hasMembership(school, actor.userId())) return true;
                response.setStatus(402);
                response.setContentType("application/json;charset=UTF-8");
                response.setHeader("Cache-Control", "no-store");
                response.getWriter().write("""
                        {"status":402,"code":"INSTITUTION_SUBSCRIPTION_REQUIRED","message":"La vigencia institucional terminó. Puede consultar el historial autorizado y gestionar la suscripción."}
                        """);
                return false;
            }
        }).addPathPatterns("/api/institutions/*/**");
    }

    private boolean hasMembership(long school, long actor) {
        return sql.sql("""
                SELECT EXISTS (SELECT 1 FROM agreement_runtime.valid_memberships WHERE institution_id=:school AND account_id=:actor)
                """).param("school", school).param("actor", actor).query(Boolean.class).single();
    }

    static boolean requiresEntitlement(String method, String path) {
        if (path.contains("/subscription/v1") || "OPTIONS".equals(method)) return false;
        if ("GET".equals(method) || "HEAD".equals(method)) {
            return PATHS.match(CLASS + "/whiteboards/v1/student/active", path)
                    || PATHS.match(CLASS + "/whiteboards/v1/*/*/state", path);
        }
        // Existing attempts may save and submit until their original academic deadline.
        if (PATHS.match(CLASS + "/**/own-attempts/*/answers", path)
                || PATHS.match(CLASS + "/**/own-attempts/*/submit", path)) return false;
        // These operations close existing access or preserve records.
        return !(path.endsWith("/close") || path.endsWith("/end") || "DELETE".equals(method)
                || PATHS.match("/api/institutions/*/administration/v1/members/*/state/SUSPENDED", path)
                || PATHS.match("/api/institutions/*/administration/v1/members/*/state/ENDED", path));
    }
}
