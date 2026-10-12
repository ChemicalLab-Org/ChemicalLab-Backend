package com.morales.chemicallab.security.storage;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.data.domain.PageRequest;

@Component @ConfigurationProperties(prefix = "app.queries") @Getter @Setter
public class QueryLimits {
    private int maxPageSize = 100;
    private int maxPage = 10000;
    public PageRequest page(int page, int size) {
        if (maxPageSize < 1 || maxPage < 1 || page < 0 || page > maxPage || size < 1 || size > maxPageSize)
            throw new IllegalArgumentException("Página inválida. Tamaño permitido: 1–" + maxPageSize + ".");
        return PageRequest.of(page, size);
    }
    public static String search(String value) {
        if (value != null && value.length() > 100) throw new IllegalArgumentException("La búsqueda admite hasta 100 caracteres.");
        return value == null ? null : value.trim();
    }
}
