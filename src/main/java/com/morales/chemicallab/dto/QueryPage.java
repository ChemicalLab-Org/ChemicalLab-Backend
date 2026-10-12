package com.morales.chemicallab.dto;
import java.util.List;
public record QueryPage<T>(List<T> content, int number, int size, long totalElements, int totalPages) {
    public static <T> QueryPage<T> of(List<T> content, int page, int size, long total) {
        return new QueryPage<>(content, page, size, total, (int)((total + size - 1) / size));
    }
}
