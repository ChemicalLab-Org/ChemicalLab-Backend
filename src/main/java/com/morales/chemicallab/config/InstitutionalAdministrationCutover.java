package com.morales.chemicallab.config;

import com.morales.chemicallab.service.WhiteboardRuntimeContract;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
@RequiredArgsConstructor
public class InstitutionalAdministrationCutover implements WebMvcConfigurer {
    private final WhiteboardRuntimeContract runtime;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new HandlerInterceptor() {
            @Override
            public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
                if (runtime.installed()) {
                    throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                            "La operación global anterior está bloqueada en instalaciones institucionales.");
                }
                return true;
            }
        }).addPathPatterns("/api/admin", "/api/admin/**", "/api/users", "/api/users/**",
                "/api/usage-metrics", "/api/usage-metrics/**");
    }
}
