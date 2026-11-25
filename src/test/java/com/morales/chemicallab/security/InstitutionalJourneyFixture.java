package com.morales.chemicallab.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.morales.chemicallab.entity.Role;
import com.morales.chemicallab.entity.UserAccount;
import com.morales.chemicallab.entity.institution.TeachingAssignment;
import com.morales.chemicallab.service.DelegationMaintenanceService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.springframework.security.core.context.SecurityContextHolder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import static com.morales.chemicallab.entity.institution.MembershipRoleGrant.InstitutionalRole.DOCENTE;

/** Opt-in browser harness. The inbox and all credentials belong to the disposable workstation fixture. */
class InstitutionalJourneyFixture {
    final NewEvaluationExecutionDbTest fixture = new NewEvaluationExecutionDbTest();
    UserAccount teacherB;
    private String g;
    private String h;

    void start(TestInfo info) throws Exception {
        fixture.start(info);
        String suffix = fixture.f.schema.substring(4);
        fixture.sql().execute(fixture.r.script("t12g-v1"));
        g = "t12g_" + suffix;
        fixture.sql().execute(fixture.r.script("t12h-v1"));
        h = "t12h_" + suffix;
        fixture.h.activation.put("app.evaluations.institutional.new-authoring-enabled", true);
        fixture.h.activation.put("app.evaluations.institutional.new-assignment-enabled", true);

        teacherB = fixture.f.account(Role.DOCENTE, null, "3", "A");
        long member = fixture.r.membership(fixture.r.foreign, teacherB.getId(), DOCENTE);
        var teachers = fixture.f.app.getBean(DelegationMaintenanceService.class);
        Instant from = fixture.r.from.plusSeconds(12);
        var teaching = teachers.planCoTeacher(fixture.r.foreign, fixture.r.foreignClass, member,
            from, fixture.r.until, from.minusSeconds(1));
        teachers.transitionCoTeacher(teaching.id(), TeachingAssignment.Status.ACTIVE, from);
    }

    void stop() throws Exception {
        try {
            if (h != null) fixture.sql().execute("drop schema if exists " + h + " cascade");
            if (g != null) fixture.sql().execute("drop schema if exists " + g + " cascade");
        } finally {
            fixture.stop();
        }
    }

    @Test void serveBrowser(TestInfo info) throws Exception {
        Path inbox = Path.of(System.getProperty("t12.journey.dir")).toAbsolutePath();
        if (Files.exists(inbox.resolve("ready.json")) || Files.exists(inbox.resolve("request.json"))
                || Files.exists(inbox.resolve("response.json")) || Files.exists(inbox.resolve("stop"))) {
            throw new IllegalStateException("Use a new directory for this disposable run.");
        }
        Files.createDirectories(inbox);
        try {
            start(info);
            write(inbox.resolve("ready.json"), Map.of(
                "origin", fixture.f.origin, "schema", fixture.f.schema,
                "teacherA", fixture.f.teacher.getUsername(), "teacherB", teacherB.getUsername(),
                "student", fixture.f.student.getUsername(), "password", WhiteboardSecurityTransportDbTest.PASSWORD,
                "platform", fixture.e.c.platform.getUsername(), "school", fixture.e.c.school.getUsername(),
                "a", fixture.r.institution, "b", fixture.r.foreign));
            Instant cutoff = Instant.now().plus(Duration.ofMinutes(30));
            while (!Files.exists(inbox.resolve("stop")) && Instant.now().isBefore(cutoff)) {
                Path request = inbox.resolve("request.json");
                if (Files.exists(request)) {
                    JsonNode command = fixture.f.json.readTree(Files.readString(request));
                    Files.delete(request);
                    try {
                        write(inbox.resolve("response.json"), execute(command));
                    } catch (RuntimeException failure) {
                        write(inbox.resolve("response.json"), Map.of("error", failure.getClass().getSimpleName()));
                    }
                } else {
                    Thread.sleep(200);
                }
            }
        } finally {
            stop();
        }
    }

    private Object execute(JsonNode command) throws Exception {
        var maintenance = fixture.e.c.service;
        long proposal = command.path("proposal").asLong();
        return switch (command.path("action").asText()) {
            case "propose" -> {
                long evaluation = command.path("evaluation").asLong();
                long institution = fixture.count("select institution_id from " + h
                    + ".receipts where evaluation_id=? and action='ASSIGN' order by ordinal desc limit 1", evaluation);
                if (institution != fixture.r.institution && institution != fixture.r.foreign) {
                    throw new IllegalArgumentException("Only assigned local fixture evaluations may be reconciled.");
                }
                String map = mapping(evaluation, institution);
                long id = maintenance.propose(fixture.e.c.root("evaluations", evaluation), map,
                    "FIXTURE-BROWSER-JOURNEY", fixture.op());
                yield Map.of("proposal", id, "checksum", maintenance.approvalChecksum(id),
                    "mapping", fixture.f.json.readTree(map));
            }
            case "approve" -> {
                String role = command.path("role").asText();
                long destination = fixture.count("select institution_id from t11b10_proposals where id=?", proposal);
                long privilege = switch (role) {
                    case "PLATFORM" -> fixture.e.c.platformGrant;
                    case "INSTITUTION" -> destination == fixture.r.institution ? fixture.e.c.foreignSchoolGrant : fixture.e.c.schoolGrant;
                    default -> throw new IllegalArgumentException("Select one approval role.");
                };
                var sessions = fixture.f.app.getBean(AccountSessionService.class);
                var authentication = sessions.authenticate(command.path("token").asText());
                sessions.revalidate((SessionPrincipal) authentication.getPrincipal());
                SecurityContextHolder.getContext().setAuthentication(authentication);
                try {
                    yield Map.of("approval", maintenance.approve(proposal, role, privilege,
                        command.path("checksum").asText(), command.path("evidence").asText(), fixture.op()));
                } finally {
                    SecurityContextHolder.clearContext();
                }
            }
            case "apply" -> Map.of("status", maintenance.apply(proposal, fixture.op()));
            default -> throw new IllegalArgumentException("Unknown local maintenance command.");
        };
    }

    String mapping(long evaluation, long institution) throws Exception {
        long clazz = institution == fixture.r.institution ? fixture.r.classId : fixture.r.foreignClass;
        JsonNode map = fixture.f.json.readTree(fixture.e.c.mapping("evaluations", evaluation, institution, clazz));
        for (var item : map.path("resolutions")) {
            var row = (ObjectNode) item;
            long id = row.path("pk").path("id").asLong();
            switch (row.path("source").asText()) {
                case "t11b8_deliveries" ->
                    row.put("classId", fixture.count("select class_id from t11b8_deliveries where id=?", id));
                case "t11b8_recipients" -> {
                    var episode = fixture.sql().queryForMap("""
                        select e.id,e.class_id from t11b8_recipients r join enrollments e on e.id=r.enrollment_id
                        where r.id=? and r.institution_id=? and e.institution_id=?
                        """, id, institution, institution);
                    row.put("classId", ((Number) episode.get("class_id")).longValue());
                    row.put("enrollmentId", ((Number) episode.get("id")).longValue());
                    row.put("sourceTimezone", "America/Lima");
                }
                default -> row.remove("classId");
            }
        }
        return map.toString();
    }

    private void write(Path path, Object value) throws Exception {
        Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
        Files.writeString(temporary, fixture.f.json.writeValueAsString(value));
        Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE);
    }
}
