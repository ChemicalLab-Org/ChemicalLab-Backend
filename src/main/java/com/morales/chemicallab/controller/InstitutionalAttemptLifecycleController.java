package com.morales.chemicallab.controller;

import com.morales.chemicallab.dto.InstitutionalAttemptLifecycle.*;
import com.morales.chemicallab.security.SessionPrincipal;
import com.morales.chemicallab.service.InstitutionalAttemptLifecycleService;
import com.morales.chemicallab.service.EvaluationDeliveryAccessService.Contract;
import jakarta.validation.Valid;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/institutions/{institutionId}/classes/{classId}/evaluation-writes/{contract}/evaluations/{evaluationId}/deliveries/{deliveryId}/revisions/{revisionId}/own-attempts")
public class InstitutionalAttemptLifecycleController {
    private final InstitutionalAttemptLifecycleService lifecycle;
    public InstitutionalAttemptLifecycleController(InstitutionalAttemptLifecycleService lifecycle){this.lifecycle=lifecycle;}
    @PostMapping
    public ResponseEntity<Receipt> start(@AuthenticationPrincipal SessionPrincipal principal,
            @PathVariable long institutionId,@PathVariable long classId,@PathVariable Contract contract,
            @PathVariable long evaluationId,@PathVariable long deliveryId,@PathVariable long revisionId,
            @Valid @RequestBody Start command) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(lifecycle.start(principal,contract,
                institutionId,classId,evaluationId,deliveryId,revisionId,command));
    }
    @PostMapping("/{attemptId}/submit")
    public ResponseEntity<Receipt> submit(@AuthenticationPrincipal SessionPrincipal principal,
            @PathVariable long institutionId,@PathVariable long classId,@PathVariable Contract contract,
            @PathVariable long evaluationId,@PathVariable long deliveryId,@PathVariable long revisionId,
            @PathVariable long attemptId,@Valid @RequestBody Submit command) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(lifecycle.submit(principal,contract,
                institutionId,classId,evaluationId,deliveryId,revisionId,attemptId,command));
    }
}
