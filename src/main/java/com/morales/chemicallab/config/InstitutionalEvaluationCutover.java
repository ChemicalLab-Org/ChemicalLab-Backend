package com.morales.chemicallab.config;

import com.morales.chemicallab.service.EvaluationRuntime;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class InstitutionalEvaluationCutover implements WebMvcConfigurer {
    private final EvaluationRuntime runtime;

    public InstitutionalEvaluationCutover(EvaluationRuntime runtime) {
        this.runtime = runtime;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new HandlerInterceptor() {
            @Override
            public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
                if (runtime.installed()) {
                    throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Usa el recorrido institucional de evaluaciones.");
                }
                return true;
            }
        }).addPathPatterns("/api/evaluations", "/api/evaluations/**",
            "/api/admin/academic-supervision/evaluations", "/api/admin/academic-supervision/evaluations/**",
            "/api/institutions/*/classes/*/evaluation-reads/**",
            "/api/institutions/*/classes/*/evaluation-writes/**");
    }
}
