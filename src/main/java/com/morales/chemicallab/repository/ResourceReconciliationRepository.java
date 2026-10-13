package com.morales.chemicallab.repository;

import com.morales.chemicallab.entity.institution.AccountAttemptBudget;
import com.morales.chemicallab.entity.institution.EvaluationOpportunityGroup;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Internal preparation only; these observations are neither authorization nor admission. */
@Repository
@RequiredArgsConstructor
public class ResourceReconciliationRepository {
    private final JdbcTemplate jdbc;
    public EvaluationOpportunityGroup legacyGroup(long evaluationId) {
        return jdbc.queryForObject("select * from t11b3_opportunity_groups where origin='FICTITIOUS_LEGACY' and evaluation_id=?",
            (r,n)->new EvaluationOpportunityGroup(r.getLong("id"),r.getString("origin"),r.getLong("evaluation_id"),r.getInt("max_attempts"),
                r.getString("configuration"),r.getString("assignments"),r.getBoolean("opportunities_closed"),
                r.getTimestamp("evaluated_at_local").toLocalDateTime(),r.getLong("reconciliation_id")),evaluationId);
    }
    public AccountAttemptBudget reservedBudget(long groupId,long accountId) {
        return jdbc.queryForObject("select * from t11b3_budget_observations where group_id=? and account_id=?",
            (r,n)->new AccountAttemptBudget(r.getLong("id"),r.getLong("group_id"),r.getLong("account_id"),r.getLong("used_count"),
                r.getObject("next_number",Long.class),r.getObject("active_attempt_id",Long.class),AccountAttemptBudget.Status.valueOf(r.getString("status")),
                r.getBoolean("source_current"),r.getString("reconciliation_status")),groupId,accountId);
    }
}
