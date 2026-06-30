package com.morales.chemicallab.service;

import com.morales.chemicallab.entity.Role;
import com.morales.chemicallab.entity.UsageEvent;
import com.morales.chemicallab.entity.UsageEventType;
import com.morales.chemicallab.entity.UsageModule;
import com.morales.chemicallab.repository.UsageEventRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Pruebas de integración contra la base de datos real que reproducen y blindan la regresión por
 * la que <strong>unirse a una sesión de pizarra como estudiante devolvía HTTP 500</strong>.
 *
 * <p>Causa: al unirse, {@code WhiteboardSessionService} registra una métrica de uso
 * ({@code module=WHITEBOARD}, {@code event_type=WHITEBOARD_SESSION_JOINED}). La restricción CHECK
 * heredada {@code usage_events_module_check} / {@code usage_events_event_type_check}, generada por
 * Hibernate cuando esos enums aún no incluían los valores de pizarra, rechazaba el INSERT. Como
 * {@code ddl-auto=update} no actualiza el CHECK al crecer el enum, {@code UsageEventSchemaMigration}
 * lo elimina al arrancar; además, la métrica se registra ahora en una transacción independiente
 * ({@code REQUIRES_NEW}) para que un fallo de la métrica nunca marque para rollback la transacción
 * de la unión.</p>
 */
@SpringBootTest
@Transactional
class WhiteboardJoinPersistenceDbTest {

    @Autowired
    private UsageEventRepository usageEventRepository;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void noExistenLosCheckHeredadosDeUsageEvents() {
        Integer module = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM pg_constraint WHERE conname = 'usage_events_module_check'",
                Integer.class);
        Integer eventType = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM pg_constraint WHERE conname = 'usage_events_event_type_check'",
                Integer.class);
        assertThat(module).isZero();
        assertThat(eventType).isZero();
    }

    @Test
    void persisteLaMetricaDeUnionAPizarra() {
        // module=WHITEBOARD y event_type=WHITEBOARD_SESSION_JOINED son los valores que provocaban el
        // 500 al unirse. Con saveAndFlush el INSERT es inmediato: si el CHECK heredado siguiera vivo,
        // fallaría aquí.
        assertThatCode(() -> usageEventRepository.saveAndFlush(UsageEvent.builder()
                .userId(1L)
                .username("EST0001")
                .userRole(Role.ESTUDIANTE)
                .module(UsageModule.WHITEBOARD)
                .eventType(UsageEventType.WHITEBOARD_SESSION_JOINED)
                .resourceType("WhiteboardSession")
                .resourceId("4")
                .occurredAt(LocalDateTime.now())
                .build()))
                .doesNotThrowAnyException();
    }
}
