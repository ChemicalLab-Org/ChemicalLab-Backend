package com.morales.chemicallab.repository;

import com.morales.chemicallab.entity.UsageEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

/**
 * Repositorio de las métricas de uso e interacción.
 *
 * <p>El filtrado y los conteos agregados se hacen con {@link JpaSpecificationExecutor}:
 * cada filtro (módulo, rol, tipo de evento, rango de fechas) se agrega como predicado solo
 * cuando tiene valor, de modo que la consulta nunca envía parámetros nulos sin tipo. Esto
 * evita el error de PostgreSQL «could not determine data type of parameter» que aparece con
 * consultas JPQL que comparan parámetros opcionales con {@code IS NULL}.</p>
 */
@Repository
public interface UsageEventRepository
        extends JpaRepository<UsageEvent, Long>, JpaSpecificationExecutor<UsageEvent> {
}
