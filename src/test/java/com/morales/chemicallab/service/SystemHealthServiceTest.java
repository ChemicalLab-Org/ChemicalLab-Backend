package com.morales.chemicallab.service;

import com.morales.chemicallab.dto.SystemHealthResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SystemHealthServiceTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    @InjectMocks
    private SystemHealthService service;

    @Test
    void check_cuandoBaseDatosResponde_retornaEstadoUp() {
        when(jdbcTemplate.queryForObject(eq("SELECT 1"), eq(Integer.class))).thenReturn(1);

        SystemHealthResponse response = service.check();

        assertThat(response.status()).isEqualTo("UP");
        assertThat(response.backend().status()).isEqualTo("UP");
        assertThat(response.backend().framework()).isEqualTo("Spring Boot");
        assertThat(response.database().status()).isEqualTo("UP");
        assertThat(response.database().type()).isEqualTo("PostgreSQL");
        assertThat(response.database().latencyMs()).isNotNull().isGreaterThanOrEqualTo(0L);
        assertThat(response.database().message()).isNull();
        assertThat(response.timestamp()).isNotBlank();
    }

    @Test
    void check_cuandoBaseDatosFalla_retornaEstadoDegradado() {
        when(jdbcTemplate.queryForObject(eq("SELECT 1"), eq(Integer.class)))
                .thenThrow(new RuntimeException("Connection refused"));

        SystemHealthResponse response = service.check();

        assertThat(response.status()).isEqualTo("DEGRADED");
        assertThat(response.backend().status()).isEqualTo("UP");
        assertThat(response.database().status()).isEqualTo("DOWN");
        assertThat(response.database().message())
                .isEqualTo("No se pudo validar la conexión a la base de datos");
        assertThat(response.database().latencyMs()).isNull();
    }

    @Test
    void validateDatabase_noExponeCredencialesNiCadenaJdbc() {
        // Simula excepción que contiene información sensible en su mensaje
        when(jdbcTemplate.queryForObject(eq("SELECT 1"), eq(Integer.class)))
                .thenThrow(new RuntimeException(
                        "Connection to jdbc:postgresql://localhost:5432/lab_quimico_db refused. " +
                        "User: postgres, Password: admin"));

        SystemHealthResponse response = service.check();

        String message = response.database().message();
        assertThat(message).doesNotContain("jdbc:");
        assertThat(message).doesNotContain("postgres");
        assertThat(message).doesNotContain("admin");
        assertThat(message).doesNotContain("5432");
        assertThat(message).isEqualTo("No se pudo validar la conexión a la base de datos");
    }
}
