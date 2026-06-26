package com.morales.chemicallab.dto;

/**
 * Respuesta del endpoint /api/health. Informa el estado general del sistema,
 * el estado del backend y el estado de la base de datos, sin exponer credenciales
 * ni cadenas de conexión.
 *
 * <p>El campo {@code status} refleja el estado combinado:
 * <ul>
 *   <li>"UP" — backend y base de datos disponibles.</li>
 *   <li>"DEGRADED" — backend disponible, base de datos con problemas.</li>
 * </ul>
 *
 * <p>El endpoint siempre devuelve HTTP 200 cuando el backend está vivo, de modo que
 * el cliente pueda distinguir entre "backend caído" (error de red) y
 * "backend vivo con base de datos caída" (HTTP 200 con status "DEGRADED").</p>
 */
public record SystemHealthResponse(
        String status,
        BackendInfo backend,
        DatabaseInfo database,
        String timestamp
) {

    public record BackendInfo(
            String status,
            String framework
    ) {}

    public record DatabaseInfo(
            String status,
            String type,
            String validation,
            Long latencyMs,
            String message
    ) {}
}
