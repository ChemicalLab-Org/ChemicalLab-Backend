package com.morales.chemicallab.controller;

import com.morales.chemicallab.security.SessionPrincipal;
import com.morales.chemicallab.service.InstitutionalEvaluationReadService;
import com.morales.chemicallab.service.EvaluationDeliveryAccessService.Contract;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/** Unit reads only. Global roles do not select institutional faculties. */
@RestController
@RequestMapping("/api/institutions/{institutionId}/classes/{classId}/evaluation-reads/{contract}/evaluations/{evaluationId}/deliveries/{deliveryId}/revisions/{revisionId}")
public class InstitutionalEvaluationReadController {
    private final InstitutionalEvaluationReadService reads;
    public InstitutionalEvaluationReadController(InstitutionalEvaluationReadService reads) { this.reads = reads; }

    @GetMapping
    public ResponseEntity<?> delivery(@AuthenticationPrincipal SessionPrincipal principal,
            @PathVariable long institutionId, @PathVariable long classId, @PathVariable Contract contract,
            @PathVariable long evaluationId, @PathVariable long deliveryId, @PathVariable long revisionId,
            @RequestParam(required = false) Long recipientAccountId) {
        return response(reads.delivery(principal, contract, institutionId, classId, evaluationId, deliveryId, revisionId, recipientAccountId));
    }

    @GetMapping("/own-attempts/{attemptId}")
    public ResponseEntity<?> attempt(@AuthenticationPrincipal SessionPrincipal principal,
            @PathVariable long institutionId, @PathVariable long classId, @PathVariable Contract contract,
            @PathVariable long evaluationId, @PathVariable long deliveryId, @PathVariable long revisionId,
            @PathVariable long attemptId) {
        return response(reads.attempt(principal, contract, institutionId, classId, evaluationId, deliveryId, revisionId, attemptId));
    }

    @GetMapping("/own-attempts/{attemptId}/correction")
    public ResponseEntity<?> correction(@AuthenticationPrincipal SessionPrincipal principal,
            @PathVariable long institutionId, @PathVariable long classId, @PathVariable Contract contract,
            @PathVariable long evaluationId, @PathVariable long deliveryId, @PathVariable long revisionId,
            @PathVariable long attemptId) {
        return response(reads.correction(principal, contract, institutionId, classId, evaluationId, deliveryId, revisionId, attemptId));
    }
    private ResponseEntity<?> response(Object dto) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(dto);
    }
}
