package com.morales.chemicallab.controller;

import com.morales.chemicallab.security.SessionPrincipal;
import com.morales.chemicallab.service.InstitutionalEvaluationAssignmentService;
import com.morales.chemicallab.service.InstitutionalEvaluationAssignmentService.Receipt;
import com.morales.chemicallab.service.InstitutionalEvaluationAuthoringService.Scope;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class InstitutionalEvaluationAssignmentController {
    private final InstitutionalEvaluationAssignmentService assignments;

    @PostMapping("/api/institutions/{institutionId}/classes/{classId}/evaluation-assignments/NEW/v1/evaluations/{evaluationId}/revisions/{revisionId}/deliveries")
    public ResponseEntity<Receipt> assign(@AuthenticationPrincipal SessionPrincipal actor, @PathVariable long institutionId,
            @PathVariable long classId, @PathVariable long evaluationId, @PathVariable long revisionId, @RequestBody byte[] body) {
        var receipt = assignments.assign(actor, new Scope(institutionId, classId, evaluationId, revisionId), body);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(receipt);
    }
}
