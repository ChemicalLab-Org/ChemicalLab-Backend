package com.morales.chemicallab.repository;

import com.morales.chemicallab.entity.UsageEvent;
import com.morales.chemicallab.entity.UsageEventType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

/**
 * Repositorio de las métricas de uso e interacción.
 *
 * <p>El filtrado y los conteos agregados se hacen con {@link JpaSpecificationExecutor}:
 * cada filtro (módulo, rol, tipo de evento, rango de fechas) se agrega como predicado solo
 * cuando tiene valor, de modo que la consulta nunca envía parámetros nulos sin tipo. Esto
 * evita el error de PostgreSQL «could not determine data type of parameter» que aparece con
 * consultas JPQL que comparan parámetros opcionales con {@code IS NULL}.</p>
 *
 * <p>Los conteos del panel de indicadores no reciben parámetros opcionales, por lo que se
 * expresan como consultas derivadas o JPQL fijas sin binds nulos.</p>
 */
@Repository
public interface UsageEventRepository
        extends JpaRepository<UsageEvent, Long>, JpaSpecificationExecutor<UsageEvent> {

    long countByEventType(UsageEventType eventType);

    /** Usuarios distintos que han generado al menos un evento de uso. */
    @Query("select count(distinct u.userId) from UsageEvent u")
    long countDistinctUsers();

    /** Módulos distintos con al menos un evento de uso registrado. */
    @Query("select count(distinct u.module) from UsageEvent u")
    long countDistinctModulesUsed();
}
