package com.morales.chemicallab.service;

import com.morales.chemicallab.security.SessionPrincipal;
import com.morales.chemicallab.security.WhiteboardScope;
import com.morales.chemicallab.security.WhiteboardScope.Target;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import static com.morales.chemicallab.service.EvaluationRuntime.require;

/** The same current class rights govern REST, the transport and the draw transaction. */
@Service
@RequiredArgsConstructor
public class InstitutionalWhiteboardAccess {
    private final JdbcTemplate jdbc;
    private final EvaluationRuntime runtime;
    private final WhiteboardRuntimeContract contract;

    public boolean installed() {
        return contract.installed();
    }

    long membership(SessionPrincipal actor, WhiteboardScope scope) {
        var rights = runtime.rights(actor, scope.institution(), scope.clazz());
        require(scope.teacher() ? rights.teacher() : rights.student());
        return rights.membership();
    }

    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW, timeout = 2)
    public void observe(SessionPrincipal actor, Target target, boolean live) {
        var scope = target.scope();
        // One statement for the scope, effective rights, admission episode and board status.
        boolean allowed = Boolean.TRUE.equals(jdbc.queryForObject(EvaluationRuntime.RIGHTS + """
            select exists(select 1 from rights r
              join board_runtime.boards c on c.institution_id=r.institution_id and c.class_id=r.class_id
              join whiteboard_sessions b on b.id=c.board_id
              where c.institution_id=? and c.class_id=? and c.board_id=? and (not ? or b.status<>'CLOSED')
                and case when ? then r.teacher else r.student and exists (
                  select 1 from board_runtime.current_admissions a where a.board_id=c.board_id and a.account_id=?
                ) end)
            """, Boolean.class,
            actor.userId(), scope.institution(), scope.clazz(), target.board(), live, scope.teacher(), actor.userId()));
        if (!allowed) throw new AccessDeniedException("BOARD_ACCESS_LOST");
    }
}
