package com.morales.chemicallab.controller;

import com.morales.chemicallab.dto.InstitutionalAnswerSave.*;
import com.morales.chemicallab.security.SessionPrincipal;
import com.morales.chemicallab.service.InstitutionalAnswerSaveService;
import com.morales.chemicallab.service.EvaluationDeliveryAccessService.Contract;
import jakarta.validation.Valid;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/institutions/{institutionId}/classes/{classId}/evaluation-writes/{contract}/evaluations/{evaluationId}/deliveries/{deliveryId}/revisions/{revisionId}/own-attempts/{attemptId}/answers")
public class InstitutionalAnswerSaveController {
    private final InstitutionalAnswerSaveService saves;
    public InstitutionalAnswerSaveController(InstitutionalAnswerSaveService saves){this.saves=saves;}
    @PostMapping
    public ResponseEntity<Saved> save(@AuthenticationPrincipal SessionPrincipal principal,
            @PathVariable long institutionId,@PathVariable long classId,@PathVariable Contract contract,
            @PathVariable long evaluationId,@PathVariable long deliveryId,@PathVariable long revisionId,
            @PathVariable long attemptId,@Valid @RequestBody Command command) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(saves.save(principal,contract,institutionId,classId,evaluationId,deliveryId,revisionId,attemptId,command));
    }
}
