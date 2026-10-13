package com.morales.chemicallab.security;

import com.morales.chemicallab.entity.LogEventType;
import com.morales.chemicallab.service.BoardActivityReconciliationService;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;
import static com.morales.chemicallab.service.BoardActivityReconciliationService.SourceKind.*;

/** A real v1 -> v2 transition, including an erroneous immutable historical association. */
class BoardAuditParentTransitionDbTest {
    @Test void certifiedV1RowsAndGlobalEventsSurviveAtomicV2AndRequireFreshExplicitApproval() throws Exception {
        var t=new BoardActivityReconciliationDbTest();
        try {
            t.base.start();t.service=t.f.app.getBean(BoardActivityReconciliationService.class);
            String v1=t.base.script("t11b4-v1"),v2=t.base.script("t11b4-v2");t.f.sql().execute(v1);t.fixture();
            t.appliedBoard();
            long event=t.audit(LogEventType.ADMIN_ACTION,"WhiteboardSession",t.f.board);
            var wrong=t.typedBoardMap();wrong.put("institutionId",t.base.foreign);wrong.put("classId",t.base.foreignClass);
            long old=t.approved(AUDIT,event,wrong);assertThat(t.service.apply(old)).isEqualTo("APPLIED");
            long global=t.audit(LogEventType.SYSTEM_HEALTH_CHECK,"WhiteboardSession",t.f.board);
            long globalEntry=t.approved(AUDIT,global,Map.of("scope","GLOBAL","evidence","FIXTURE-GLOBAL-UPGRADE"));
            assertThat(t.service.apply(globalEntry)).isEqualTo("APPLIED");
            var before=t.rehearsal();var versions=t.versions();
            t.tx().executeWithoutResult(s->{t.f.sql().execute(v2);assertThat(t.f.sql().queryForObject("select source_current from t11b4_audit_observations where event_id=?",Boolean.class,event)).isFalse();s.setRollbackOnly();});
            assertThat(t.rehearsal()).isEqualTo(before);assertThat(t.versions()).isEqualTo(versions);t.f.sql().execute(v1);
            assertThat(t.f.sql().queryForObject("select to_regclass(current_schema()||'.t11b4_apply_version') is null",Boolean.class)).isTrue();
            t.f.sql().execute(v2);t.f.sql().execute(v2);t.f.sql().execute(t.base.expansion);
            assertThat(t.rehearsal()).isEqualTo(before);assertThat(t.versions()).isEqualTo(versions);
            assertThat(t.f.sql().queryForObject("select predecessor_fingerprint from t11b4_apply_version",String.class)).isEqualTo(t.f.sql().queryForObject("select fingerprint from t11b4_schema_version",String.class));
            assertThatThrownBy(()->t.f.sql().execute(v1)).isInstanceOf(DataAccessException.class).hasMessageContaining("divergence");
            assertThat(t.f.sql().queryForObject("select source_current from t11b4_audit_observations where event_id=?",Boolean.class,event)).isFalse();
            assertThat(t.service.apply(old)).isEqualTo("STALE");var stale=t.rehearsal();assertThat(t.service.apply(old)).isEqualTo("STALE");assertThat(t.rehearsal()).isEqualTo(stale);
            assertThat(t.service.apply(globalEntry)).isEqualTo("APPLIED");
            long newEntry=t.proposed(AUDIT,event,wrong);assertThat(newEntry).isNotEqualTo(old);
            assertThatThrownBy(()->t.service.apply(newEntry)).isInstanceOf(DataAccessException.class);t.approve(newEntry);assertThat(t.service.apply(newEntry)).isEqualTo("CONFLICT");
            // The wrong historical row is retained as stale evidence, never rewritten to the other school.
            assertThat(t.count("select institution_id from t11b4_audit_contexts where event_id=?",event)).isEqualTo(t.base.foreign);
            t.f.sql().execute(v2);
            t.tx().executeWithoutResult(s->{
                t.f.sql().execute("alter table t11b4_audit_contexts disable trigger t11b4_reference");
                assertThatThrownBy(()->t.f.sql().execute(v2)).isInstanceOf(DataAccessException.class).hasMessageContaining("divergence");s.setRollbackOnly();
            });t.f.sql().execute(v2);
            assertThatThrownBy(()->t.f.sql().execute("update t11b4_apply_version set fingerprint='invented'")).isInstanceOf(DataAccessException.class).hasMessageContaining("immutable");
        } finally {t.close();t.stop();}
    }
}
