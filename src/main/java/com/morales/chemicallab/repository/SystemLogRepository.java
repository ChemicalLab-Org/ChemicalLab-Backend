package com.morales.chemicallab.repository;

import com.morales.chemicallab.entity.LogCategory;
import com.morales.chemicallab.entity.LogEventType;
import com.morales.chemicallab.entity.LogSeverity;
import com.morales.chemicallab.entity.Role;
import com.morales.chemicallab.entity.SystemLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;

@Repository
public interface SystemLogRepository extends JpaRepository<SystemLog, Long> {

    /**
     * Búsqueda paginada de logs con filtros opcionales. Cada parámetro nulo se ignora,
     * de modo que un mismo método cubre todas las combinaciones de filtros del panel.
     * El texto de búsqueda se compara, sin distinguir mayúsculas, contra el usuario
     * actor, la etiqueta del recurso y la descripción.
     */
    @Query("""
            SELECT l FROM SystemLog l
            WHERE (:category IS NULL OR l.category = :category)
              AND (:eventType IS NULL OR l.eventType = :eventType)
              AND (:severity IS NULL OR l.severity = :severity)
              AND (:actorRole IS NULL OR l.actorRole = :actorRole)
              AND (:from IS NULL OR l.createdAt >= :from)
              AND (:to IS NULL OR l.createdAt <= :to)
              AND (:search IS NULL OR :search = '' OR
                   LOWER(COALESCE(l.actorUsername, '')) LIKE LOWER(CONCAT('%', :search, '%')) OR
                   LOWER(COALESCE(l.targetLabel, '')) LIKE LOWER(CONCAT('%', :search, '%')) OR
                   LOWER(COALESCE(l.description, '')) LIKE LOWER(CONCAT('%', :search, '%')))
            ORDER BY l.createdAt DESC
            """)
    Page<SystemLog> search(
            @Param("category") LogCategory category,
            @Param("eventType") LogEventType eventType,
            @Param("severity") LogSeverity severity,
            @Param("actorRole") Role actorRole,
            @Param("search") String search,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to,
            Pageable pageable);

    long countBySeverity(LogSeverity severity);

    long countByCategory(LogCategory category);
}
