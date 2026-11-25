package com.morales.chemicallab.config;

import com.morales.chemicallab.service.InstitutionalContentService;
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
public class InstitutionalContentCutover implements WebMvcConfigurer {
    private final InstitutionalContentService contents;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new HandlerInterceptor() {
            @Override
            public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
                if (contents.installed()) {
                    throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Usa el recorrido institucional de contenidos.");
                }
                return true;
            }
        }).addPathPatterns("/api/concepts", "/api/concepts/**", "/api/admin/academic-supervision",
            "/api/admin/academic-supervision/**");
    }
}
