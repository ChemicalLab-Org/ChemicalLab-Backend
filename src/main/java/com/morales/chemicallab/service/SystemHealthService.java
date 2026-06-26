package com.morales.chemicallab.service;

import com.morales.chemicallab.dto.SystemHealthResponse;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Service
public class SystemHealthService {

    private final JdbcTemplate jdbcTemplate;

    public SystemHealthService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public SystemHealthResponse check() {
        SystemHealthResponse.BackendInfo backend =
                new SystemHealthResponse.BackendInfo("UP", "Spring Boot");

        SystemHealthResponse.DatabaseInfo database = validateDatabase();

        String overallStatus = "UP".equals(database.status()) ? "UP" : "DEGRADED";
        String timestamp = LocalDateTime.now().toString();

        return new SystemHealthResponse(overallStatus, backend, database, timestamp);
    }

    // Paquete-visible para tests unitarios sin Spring context.
    SystemHealthResponse.DatabaseInfo validateDatabase() {
        long start = System.currentTimeMillis();
        try {
            Integer result = jdbcTemplate.queryForObject("SELECT 1", Integer.class);
            long latencyMs = System.currentTimeMillis() - start;

            if (Integer.valueOf(1).equals(result)) {
                return new SystemHealthResponse.DatabaseInfo(
                        "UP", "PostgreSQL", "SELECT 1", latencyMs, null);
            }
            return new SystemHealthResponse.DatabaseInfo(
                    "DOWN", "PostgreSQL", "SELECT 1", null,
                    "La validación retornó un resultado inesperado");
        } catch (Exception e) {
            // No se propaga el mensaje de la excepción para evitar exponer
            // la cadena de conexión, credenciales u otros datos sensibles.
            return new SystemHealthResponse.DatabaseInfo(
                    "DOWN", "PostgreSQL", "SELECT 1", null,
                    "No se pudo validar la conexión a la base de datos");
        }
    }
}
