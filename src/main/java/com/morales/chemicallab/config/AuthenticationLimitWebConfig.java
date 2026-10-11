package com.morales.chemicallab.config;

import com.morales.chemicallab.security.AuthenticationLimits;
import jakarta.servlet.http.*;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.*;

@Configuration
@RequiredArgsConstructor
public class AuthenticationLimitWebConfig implements WebMvcConfigurer {
    private final AuthenticationLimits limits;
    @Override public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new HandlerInterceptor() {
            @Override public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
                String path = request.getServletPath(), method = request.getMethod();
                boolean loginOrCreate = method.equals("POST") && (path.equals("/api/auth/login") || path.equals("/api/admin/users")
                        || path.equals("/api/users/teachers") || path.matches("/api/users/teachers/[0-9]+/students"));
                boolean password = method.equals("PATCH") && (path.equals("/api/auth/change-temporary-password")
                        || path.matches("/api/admin/users/[0-9]+/password/reset")
                        || path.matches("/api/users/teachers/[0-9]+(?:/students/[0-9]+)?/reset-password"));
                if (loginOrCreate || password) limits.request(request);
                return true;
            }
        });
    }
}
