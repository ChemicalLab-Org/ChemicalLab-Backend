package com.morales.chemicallab.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Internal P06 fixture protocol. Each call is one durable transaction, never an HTTP/job writer.
 * SQL couples the aggregate, quotas, scrubbed mandatory T08 SUCCESS and checkpoint.
 * A caller with an unknown outcome queries lookup(operation) before retrying that exact request.
 */
@Service
@RequiredArgsConstructor
@Transactional(timeout = 60)
public class TransitionBatchMaintenanceService {
    private final JdbcTemplate jdbc;
    private static final ObjectMapper JSON = new ObjectMapper();

    public record Limits(int rows, long bytes, int aggregateRows, long aggregateBytes) {
        public static Limits fixture() { return new Limits(20000, 67108864, 256, 2097152); }
    }

    public JsonNode create(UUID plan, List<Long> proposals, Limits limits, UUID operation) {
        return command(operation, "CREATE", Map.of("plan", plan, "proposals", List.copyOf(proposals), "limits", limits));
    }
    public JsonNode pause(UUID plan, long epoch, UUID operation) {
        return command(operation, "PAUSE", Map.of("plan", plan, "epoch", epoch));
    }
    public JsonNode resume(UUID plan, long epoch, UUID operation) {
        return command(operation, "RESUME", Map.of("plan", plan, "epoch", epoch));
    }
    public JsonNode step(UUID plan, int ordinal, long epoch, UUID operation) {
        return command(operation, "STEP", Map.of("plan", plan, "ordinal", ordinal, "epoch", epoch));
    }
    public JsonNode observe(UUID plan) {
        internal(); return parse(jdbc.queryForObject("select t11p06_observe(?)::text", String.class, plan));
    }
    public JsonNode lookup(UUID operation) {
        internal(); return parse(jdbc.queryForObject("select t11p06_lookup(?)::text", String.class, operation));
    }
    private JsonNode command(UUID operation, String action, Object request) {
        internal(); Objects.requireNonNull(operation, "Operation UUID required");
        jdbc.execute("set local lock_timeout='3s'");
        jdbc.execute("set local statement_timeout='55s'");
        return parse(jdbc.queryForObject("select t11p06_execute(?,?,?::jsonb)::text", String.class, operation, action, json(request)));
    }
    private void internal() {
        if (SecurityContextHolder.getContext().getAuthentication() != null)
            throw new IllegalStateException("Internal fixture maintainer only; T12 pending");
        jdbc.execute("select t11b5_fixture()");
    }
    private static String json(Object value) {
        try { return JSON.writeValueAsString(value); }
        catch (Exception failure) { throw new IllegalArgumentException("Invalid fixture request", failure); }
    }
    private static JsonNode parse(String value) {
        try { return value == null ? JSON.nullNode() : JSON.readTree(value); }
        catch (Exception failure) { throw new IllegalStateException("Invalid persisted P06 result", failure); }
    }
}
