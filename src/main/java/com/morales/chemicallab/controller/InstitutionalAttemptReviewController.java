package com.morales.chemicallab.controller;

import com.morales.chemicallab.dto.InstitutionalAttemptReview.*;
import com.morales.chemicallab.security.SessionPrincipal;
import com.morales.chemicallab.service.InstitutionalAttemptReviewService;
import com.morales.chemicallab.service.EvaluationDeliveryAccessService.Contract;
import jakarta.validation.Valid;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/institutions/{institutionId}/classes/{classId}/evaluation-writes/{contract}/evaluations/{evaluationId}/deliveries/{deliveryId}/revisions/{revisionId}/attempts/{attemptId}/review")
public class InstitutionalAttemptReviewController {
    private final InstitutionalAttemptReviewService service;
    public InstitutionalAttemptReviewController(InstitutionalAttemptReviewService service){this.service=service;}
    @GetMapping
    public ResponseEntity<Review> read(@AuthenticationPrincipal SessionPrincipal actor,@PathVariable long institutionId,
            @PathVariable long classId,@PathVariable Contract contract,@PathVariable long evaluationId,
            @PathVariable long deliveryId,@PathVariable long revisionId,@PathVariable long attemptId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.read(actor,contract,institutionId,classId,evaluationId,deliveryId,revisionId,attemptId));
    }
    @PostMapping("/answers/{answerId}")
    public ResponseEntity<Receipt> grade(@AuthenticationPrincipal SessionPrincipal actor,@PathVariable long institutionId,
            @PathVariable long classId,@PathVariable Contract contract,@PathVariable long evaluationId,
            @PathVariable long deliveryId,@PathVariable long revisionId,@PathVariable long attemptId,
            @PathVariable long answerId,@Valid @RequestBody Grade body) {
        return result(service.write(actor,contract,institutionId,classId,evaluationId,deliveryId,revisionId,attemptId,
            "GRADE",answerId,body.operationId(),body.score(),null,body.feedback()));
    }
    @PostMapping("/complete")
    public ResponseEntity<Receipt> complete(@AuthenticationPrincipal SessionPrincipal actor,@PathVariable long institutionId,
            @PathVariable long classId,@PathVariable Contract contract,@PathVariable long evaluationId,
            @PathVariable long deliveryId,@PathVariable long revisionId,@PathVariable long attemptId,@Valid @RequestBody Command body) {
        return result(service.write(actor,contract,institutionId,classId,evaluationId,deliveryId,revisionId,attemptId,
            "COMPLETE",null,body.operationId(),null,null,null));
    }
    @PostMapping("/adjustments")
    public ResponseEntity<Receipt> add(@AuthenticationPrincipal SessionPrincipal actor,@PathVariable long institutionId,
            @PathVariable long classId,@PathVariable Contract contract,@PathVariable long evaluationId,
            @PathVariable long deliveryId,@PathVariable long revisionId,@PathVariable long attemptId,@Valid @RequestBody Adjustment body) {
        return result(service.write(actor,contract,institutionId,classId,evaluationId,deliveryId,revisionId,attemptId,
            "ADD",null,body.operationId(),null,body.amount(),body.reason()));
    }
    @PostMapping("/adjustments/{adjustmentId}/void")
    public ResponseEntity<Receipt> remove(@AuthenticationPrincipal SessionPrincipal actor,@PathVariable long institutionId,
            @PathVariable long classId,@PathVariable Contract contract,@PathVariable long evaluationId,
            @PathVariable long deliveryId,@PathVariable long revisionId,@PathVariable long attemptId,
            @PathVariable long adjustmentId,@Valid @RequestBody Command body) {
        return result(service.write(actor,contract,institutionId,classId,evaluationId,deliveryId,revisionId,attemptId,
            "REMOVE",adjustmentId,body.operationId(),null,null,null));
    }
    @PostMapping("/feedback")
    public ResponseEntity<Receipt> feedback(@AuthenticationPrincipal SessionPrincipal actor,@PathVariable long institutionId,
            @PathVariable long classId,@PathVariable Contract contract,@PathVariable long evaluationId,
            @PathVariable long deliveryId,@PathVariable long revisionId,@PathVariable long attemptId,@Valid @RequestBody Feedback body) {
        return result(service.write(actor,contract,institutionId,classId,evaluationId,deliveryId,revisionId,attemptId,
            "FEEDBACK",null,body.operationId(),null,null,body.overallFeedback()));
    }
    @PostMapping("/close")
    public ResponseEntity<Receipt> close(@AuthenticationPrincipal SessionPrincipal actor,@PathVariable long institutionId,
            @PathVariable long classId,@PathVariable Contract contract,@PathVariable long evaluationId,
            @PathVariable long deliveryId,@PathVariable long revisionId,@PathVariable long attemptId,@Valid @RequestBody Command body) {
        return result(service.write(actor,contract,institutionId,classId,evaluationId,deliveryId,revisionId,attemptId,
            "CLOSE",null,body.operationId(),null,null,null));
    }
    private ResponseEntity<Receipt> result(Receipt receipt){return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(receipt);}
}
