package com.morales.chemicallab.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.morales.chemicallab.entity.UserAccount;
import com.morales.chemicallab.entity.institution.InstitutionMembership;
import com.morales.chemicallab.service.EvaluationDeliveryAccessService.Request;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.springframework.dao.DataAccessException;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static com.morales.chemicallab.service.EvaluationDeliveryAccessService.Action.READ_DELIVERY;
import static com.morales.chemicallab.service.EvaluationDeliveryAccessService.Contract.NEW;
import static org.assertj.core.api.Assertions.*;

/** Only covers newly composed discovery behavior. F/H command regressions have their own evidence. */
class InstitutionalEvaluationCatalogDbTest {
    private final InstitutionalJourneyFixture journey = new InstitutionalJourneyFixture();
    private final NewEvaluationExecutionDbTest f = journey.fixture;
    private static final String CLASSES = "/api/institutions/evaluation-catalog/NEW/v1/classes";

    @Test void catalogPaginatesAuthorizedRowsAndFollowsCurrentMembershipAndAppliedContext(TestInfo info) throws Exception {
        try {
            journey.start(info);
            var r = f.r;
            var pupil = f.h.pupil;
            var teacherA = f.h.teacher;
            var teacherB = f.f.login(journey.teacherB);
            var scopeA = catalog(r.institution, r.classId);
            var scopeB = catalog(r.foreign, r.foreignClass);
            var a = createAndAssign(r.institution, r.classId, r.episode, teacherA);
            var b = createAndAssign(r.foreign, r.foreignClass, f.e.c.foreignEpisode, teacherB);

            var items = get(pupil, CLASSES + "?limit=1");
            assertThat(items.path("items").get(0).path("classId").asLong()).isEqualTo(r.classId);
            var next = get(pupil, CLASSES + "?after=" + items.path("nextAfter").asLong());
            assertThat(next.path("items").findValuesAsText("classId"))
                .containsExactly(Long.toString(r.otherClass), Long.toString(r.foreignClass));
            assertThat(next.path("nextAfter").isNull()).isTrue();
            assertThat(f.f.request("GET", CLASSES + "?limit=51", pupil, null).statusCode()).isEqualTo(400);
            assertThat(f.f.request("GET", CLASSES, null, null).statusCode()).isEqualTo(401);
            assertThat(get(pupil, scopeA + "/own-deliveries?limit=1").path("nextAfter").isNull()).isTrue();
            assertThat(listed(pupil, scopeA)).containsExactly(Long.toString(f.q.deliveryId()));
            assertThat(listed(pupil, scopeB)).isEmpty();
            assertThat(get(teacherA, scopeA + "/revisions").path("items").findValuesAsText("revisionId"))
                .contains(Long.toString(a.revisionId())).doesNotContain(Long.toString(b.revisionId()));
            assertThat(get(teacherB, scopeB + "/enrollments").path("items").findValuesAsText("enrollmentId"))
                .containsExactly(Long.toString(f.e.c.foreignEpisode));
            assertThat(f.f.request("GET", scopeA + "/enrollments", teacherB, null).statusCode()).isEqualTo(403);
            assertThat(f.f.request("GET", scopeB + "/enrollments", teacherA, null).statusCode()).isEqualTo(403);
            assertThat(f.f.request("GET", catalog(r.institution, r.foreignClass) + "/own-deliveries", pupil, null).statusCode()).isEqualTo(403);
            assertThat(f.f.request("GET", catalog(r.foreign, r.classId) + "/own-deliveries", pupil, null).statusCode()).isEqualTo(403);
            assertThat(f.f.request("GET", "/api/evaluations/" + a.evaluationId(), teacherA, null).statusCode()).isNotEqualTo(200);

            for (Request request : List.of(a, b)) {
                var c = f.e.c;
                long proposal = c.service.propose(c.root("evaluations", request.evaluationId()),
                    journey.mapping(request.evaluationId(), request.institutionId()), "FIXTURE-CATALOG", f.op());
                c.approve(proposal, "PLATFORM", c.platform, c.platformGrant);
                assertThatThrownBy(() -> c.service.apply(proposal, f.op())).isInstanceOf(DataAccessException.class);
                c.approve(proposal, "INSTITUTION", c.school,
                    request.institutionId() == r.institution ? c.foreignSchoolGrant : c.schoolGrant);
                assertThat(c.service.apply(proposal, f.op())).isEqualTo("APPLIED");
            }

            var baseline = f.state();
            var first = get(pupil, scopeA + "/own-deliveries?limit=1");
            assertThat(first.path("items").findValuesAsText("deliveryId")).containsExactly(Long.toString(f.q.deliveryId()));
            assertThat(first.path("nextAfter").asLong()).isEqualTo(f.q.deliveryId());
            var second = get(pupil, scopeA + "/own-deliveries?limit=1&after=" + f.q.deliveryId());
            assertThat(second.path("items").findValuesAsText("deliveryId")).containsExactly(Long.toString(a.deliveryId()));
            assertThat(second.path("nextAfter").isNull()).isTrue();
            assertThat(listed(pupil, scopeB)).containsExactly(Long.toString(b.deliveryId()));
            assertThat(get(pupil, scopeA + "/own-deliveries/" + b.deliveryId() + "/attempts").path("items")).isEmpty();
            assertThat(get(pupil, scopeB + "/own-deliveries/" + a.deliveryId() + "/attempts").path("items")).isEmpty();
            assertThat(f.state()).isEqualTo(baseline);
            f.ok(f.send("GET", pupil, a, "", null));
            f.ok(f.send("GET", pupil, b, "", null));

            long attempt = f.begin(a, f.op()).path("attemptId").asLong();
            assertThat(get(pupil, scopeA + "/own-deliveries/" + a.deliveryId() + "/attempts").path("items")
                .findValuesAsText("attemptId")).containsExactly(Long.toString(attempt));
            assertThat(get(teacherA, scopeA + "/reviewable-attempts").path("items")).isEmpty();
            f.save(a, attempt, f.op(), 17L, null, "Fictitious");
            f.submit(a, attempt, f.op(), List.of(f.answer(a, 18L, 31L, null)));
            assertThat(get(teacherA, scopeA + "/reviewable-attempts").path("items")
                .findValuesAsText("attemptId")).containsExactly(Long.toString(attempt));
            assertThat(get(teacherB, scopeB + "/reviewable-attempts").path("items")).isEmpty();
            f.denied(f.send("GET", pupil, a, "/own-attempts/" + attempt + "/correction", null));

            r.institutions.transitionMembership(r.member, InstitutionMembership.Status.SUSPENDED, Instant.now());
            assertThat(f.f.request("GET", scopeA + "/own-deliveries", pupil, null).statusCode()).isEqualTo(403);
            assertThat(listed(pupil, scopeB)).containsExactly(Long.toString(b.deliveryId()));
            assertThat(get(pupil, CLASSES).path("items").findValuesAsText("institutionId"))
                .containsExactly(Long.toString(r.foreign));
            f.f.mutate(f.f.student, UserAccount::invalidateSessions);
            assertThat(f.f.request("GET", scopeA + "/own-deliveries", pupil, null).statusCode()).isEqualTo(401);
            assertThat(f.f.request("GET", scopeB + "/own-deliveries", pupil, null).statusCode()).isEqualTo(401);
        } finally {
            journey.stop();
        }
    }

    private Request createAndAssign(long institution, long clazz, long enrollment, String teacher) throws Exception {
        String scope = "/api/institutions/" + institution + "/classes/" + clazz;
        String authoring = scope + "/evaluation-authoring/NEW/v1/evaluations";
        var created = f.ok(post(authoring, teacher, f.body(f.op(), "payload",
            f.f.json.readTree(f.payload(1, "ALL_AT_ONCE", false, 0)))));
        long evaluation = created.path("evaluationId").asLong();
        long revision = created.path("revisionId").asLong();
        String suffix = "/evaluations/" + evaluation + "/revisions/" + revision;
        f.ok(post(scope + "/evaluation-authoring/NEW/v1" + suffix + "/publish", teacher,
            f.body(f.op(), "expectedVersion", 1)));
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        var assigned = f.ok(post(scope + "/evaluation-assignments/NEW/v1" + suffix + "/deliveries", teacher,
            f.body(f.op(), "expectedRevisionVersion", 2, "startsAt", now.minusSeconds(60).toString(),
                "dueAt", now.plusSeconds(1200).toString(), "enrollmentIds", List.of(enrollment))));
        return new Request(NEW, READ_DELIVERY, institution, clazz, evaluation,
            assigned.path("deliveryId").asLong(), revision, f.f.student.getId(), null);
    }

    private List<String> listed(String token, String scope) throws Exception {
        return get(token, scope + "/own-deliveries").path("items").findValuesAsText("deliveryId");
    }

    private HttpResponse<String> post(String path, String token, String body) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(f.f.origin + path)).timeout(Duration.ofSeconds(60))
            .header("Content-Type", "application/json").header("Authorization", "Bearer " + token)
            .POST(HttpRequest.BodyPublishers.ofString(body)).build();
        return f.f.http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode get(String token, String path) throws Exception {
        return f.ok(f.f.request("GET", path, token, null));
    }

    private static String catalog(long institution, long clazz) {
        return "/api/institutions/" + institution + "/classes/" + clazz + "/evaluation-catalog/NEW/v1";
    }
}
