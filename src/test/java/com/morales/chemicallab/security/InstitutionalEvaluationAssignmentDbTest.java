package com.morales.chemicallab.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.morales.chemicallab.service.EvaluationDeliveryAccessService.Request;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class InstitutionalEvaluationAssignmentDbTest {
    private final NewEvaluationExecutionDbTest f = new NewEvaluationExecutionDbTest();
    private String control;
    private String g;

    @BeforeEach void setup(TestInfo info) throws Exception {
        f.start(info);
        g = "t12g_" + f.f.schema.substring(4);
        control = "t12h_" + f.f.schema.substring(4);
        f.sql().execute(f.r.script("t12g-v1"));
        f.sql().execute(f.r.script("t12h-v1"));
    }

    @AfterEach void stop() throws Exception {
        try {
            if (control != null) f.sql().execute("drop schema if exists " + control + "," + g + " cascade");
        } finally {
            f.stop();
        }
    }

    @Test void contextDeltaPreservesApprovedDeliveryAndLeavesNewRowsPending() throws Exception {
        Request original = f.q;
        long attempt = f.begun();
        long oldHeads = f.count("select count(*) from t11b10_versions");
        long oldApprovals = f.count("select count(*) from t11b10_approvals");
        long oldProposal = f.count("select proposal_id from " + control + ".root_head(?)", original.evaluationId());
        JsonNode assignment = assignInFixture(original, f.r.otherClass, f.r.otherEpisode);
        Request next = target(original, f.r.otherClass, assignment.path("deliveryId").longValue());
        assertThat(f.count("select count(*) from t11b10_versions")).isEqualTo(oldHeads);
        assertThat(f.count("select count(*) from t11b10_approvals")).isEqualTo(oldApprovals);
        assertThat(f.sql().queryForObject("select t11p05_fresh(?)", Boolean.class, oldProposal)).isFalse();
        assertThat(f.sql().queryForObject("select " + control + ".fresh(?)", Boolean.class, oldProposal)).isTrue();
        f.denied(f.send("GET", f.h.pupil, next, "", null));
        f.denied(f.send("POST", f.h.pupil, next, "/own-attempts", f.body(f.op())));
        assertThat(f.own(original, attempt).path("attemptNumber").intValue()).isEqualTo(1);
        f.save(original, attempt, f.op(), 17, null, "Still active");
        f.submit(original, attempt, f.op(), List.of(f.answer(original, 18, 31L, null)));

        f.resolve(original, null);
        assertThat(f.sql().queryForObject("select " + control + ".fresh(?)", Boolean.class, oldProposal)).isFalse();
        assertThat(f.begin(next, f.op()).path("attemptNumber").intValue()).isEqualTo(2);
    }

    private Request target(Request original, long clazz, long delivery) {
        return new Request(original.contract(), original.action(), original.institutionId(), clazz, original.evaluationId(),
            delivery, original.revisionId(), original.recipientAccountId(), null);
    }

    private JsonNode assignInFixture(Request original, long clazz, long episode) throws Exception {
        var op = f.op();
        var actor = f.f.teacher.getId();
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        ObjectNode request = f.f.json.createObjectNode().put("actor", actor)
            .put("evaluation", original.evaluationId()).put("institution", original.institutionId())
            .put("class", clazz).put("revision", original.revisionId()).put("version", 2)
            .put("startsAt", now.minusSeconds(10).toString()).put("dueAt", now.plusSeconds(800).toString());
        request.putArray("enrollmentIds").add(episode);
        var tx = new TransactionTemplate(f.f.app.getBean(PlatformTransactionManager.class));
        String result = tx.execute(s -> {
            long audit = f.count("""
                insert into system_logs(category,event_type,severity,actor_user_id,actor_role,target_type,target_id,
                    action,description,outcome,correlation_id,created_at)
                values('EVALUATION','EVALUATION_ASSIGNED','INFO',?,'DOCENTE','ContextResolution',?,
                    'ASSIGN_NEW_DELIVERY','ASSIGN_NEW_DELIVERY','SUCCESS',?,clock_timestamp()) returning id
                """, actor, original.evaluationId(), op);
            return f.sql().queryForObject("select " + control + ".assign(?,?::jsonb,?)::text",
                String.class, op, request.toString(), audit);
        });
        return f.f.json.readTree(result);
    }
}
