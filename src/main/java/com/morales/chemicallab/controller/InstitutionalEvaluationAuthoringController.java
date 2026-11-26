package com.morales.chemicallab.controller;

import com.morales.chemicallab.security.SessionPrincipal;
import com.morales.chemicallab.service.InstitutionalEvaluationAuthoringService;
import com.morales.chemicallab.service.InstitutionalEvaluationAuthoringService.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/institutions/{institutionId}/classes/{classId}/evaluation-authoring/NEW/v1/evaluations")
public class InstitutionalEvaluationAuthoringController {
    private static final String REVISION = "/{evaluationId}/revisions/{revisionId}";
    private final InstitutionalEvaluationAuthoringService authoring;

    @PostMapping
    public ResponseEntity<Receipt> create(@AuthenticationPrincipal SessionPrincipal actor, @PathVariable long institutionId,
            @PathVariable long classId, @RequestBody byte[] body) {
        return result(authoring.write(actor, new Scope(institutionId, classId, null, null), Action.CREATE, body));
    }

    @GetMapping(REVISION)
    public ResponseEntity<Draft> get(@AuthenticationPrincipal SessionPrincipal actor, @PathVariable long institutionId,
            @PathVariable long classId, @PathVariable long evaluationId, @PathVariable long revisionId) {
        return result(authoring.read(actor, new Scope(institutionId, classId, evaluationId, revisionId)));
    }

    @PutMapping(REVISION)
    public ResponseEntity<Receipt> edit(@AuthenticationPrincipal SessionPrincipal actor, @PathVariable long institutionId,
            @PathVariable long classId, @PathVariable long evaluationId, @PathVariable long revisionId, @RequestBody byte[] body) {
        return result(authoring.write(actor, new Scope(institutionId, classId, evaluationId, revisionId), Action.EDIT, body));
    }

    @PostMapping(REVISION + "/publish")
    public ResponseEntity<Receipt> publish(@AuthenticationPrincipal SessionPrincipal actor, @PathVariable long institutionId,
            @PathVariable long classId, @PathVariable long evaluationId, @PathVariable long revisionId, @RequestBody byte[] body) {
        return result(authoring.write(actor, new Scope(institutionId, classId, evaluationId, revisionId), Action.PUBLISH, body));
    }

    @PostMapping(REVISION + "/successor")
    public ResponseEntity<Receipt> successor(@AuthenticationPrincipal SessionPrincipal actor, @PathVariable long institutionId,
            @PathVariable long classId, @PathVariable long evaluationId, @PathVariable long revisionId, @RequestBody byte[] body) {
        return result(authoring.write(actor, new Scope(institutionId, classId, evaluationId, revisionId), Action.SUCCESSOR, body));
    }

    private static <T> ResponseEntity<T> result(T value) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(value);
    }
}
