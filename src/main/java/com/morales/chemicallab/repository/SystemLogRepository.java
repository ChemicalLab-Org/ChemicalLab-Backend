package com.morales.chemicallab.repository;

import com.morales.chemicallab.entity.LogCategory;
import com.morales.chemicallab.entity.LogEventType;
import com.morales.chemicallab.entity.LogSeverity;
import com.morales.chemicallab.entity.SystemLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

/**
 * Repositorio de los logs de trazabilidad.
 *
 * <p>El filtrado dinámico del listado se hace con {@link JpaSpecificationExecutor}: cada
 * filtro se agrega como predicado solo cuando tiene valor, de modo que la consulta nunca
 * envía parámetros nulos sin tipo. Esto evita errores de PostgreSQL del tipo
 * «could not determine data type of parameter» que ocurrían con una consulta JPQL que
 * comparaba parámetros opcionales con {@code IS NULL}.</p>
 */
@Repository
public interface SystemLogRepository
        extends JpaRepository<SystemLog, Long>, JpaSpecificationExecutor<SystemLog> {

    long countBySeverity(LogSeverity severity);

    long countByCategory(LogCategory category);

    long countByEventType(LogEventType eventType);
}
