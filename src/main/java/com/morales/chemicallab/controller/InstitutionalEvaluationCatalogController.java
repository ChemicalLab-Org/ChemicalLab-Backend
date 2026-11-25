package com.morales.chemicallab.controller;

import com.morales.chemicallab.security.SessionPrincipal;
import com.morales.chemicallab.service.InstitutionalEvaluationCatalog;
import com.morales.chemicallab.service.InstitutionalEvaluationCatalog.*;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/institutions")
public class InstitutionalEvaluationCatalogController {
    private static final String CLASS = "/{institutionId}/classes/{classId}/evaluation-catalog/NEW/v1";
    private final InstitutionalEvaluationCatalog catalog;

    public InstitutionalEvaluationCatalogController(InstitutionalEvaluationCatalog catalog) {
        this.catalog = catalog;
    }

    @GetMapping("/evaluation-catalog/NEW/v1/classes")
    public ResponseEntity<Page<ClassChoice>> classes(@AuthenticationPrincipal SessionPrincipal actor,
            @RequestParam(defaultValue = "0") long after, @RequestParam(defaultValue = "20") int limit) {
        return noStore(catalog.classes(actor, after, limit));
    }

    @GetMapping(CLASS + "/own-deliveries")
    public ResponseEntity<Page<Delivery>> deliveries(@AuthenticationPrincipal SessionPrincipal actor,
            @PathVariable long institutionId, @PathVariable long classId, @RequestParam(defaultValue = "0") long after,
            @RequestParam(defaultValue = "20") int limit) {
        return noStore(catalog.deliveries(actor, institutionId, classId, after, limit));
    }

    @GetMapping(CLASS + "/own-deliveries/{deliveryId}/attempts")
    public ResponseEntity<Page<AttemptRow>> ownAttempts(@AuthenticationPrincipal SessionPrincipal actor,
            @PathVariable long institutionId, @PathVariable long classId, @PathVariable long deliveryId,
            @RequestParam(defaultValue = "0") long after, @RequestParam(defaultValue = "20") int limit) {
        return noStore(catalog.attempts(actor, institutionId, classId, deliveryId, false, after, limit));
    }

    @GetMapping(CLASS + "/reviewable-attempts")
    public ResponseEntity<Page<AttemptRow>> reviewableAttempts(@AuthenticationPrincipal SessionPrincipal actor,
            @PathVariable long institutionId, @PathVariable long classId, @RequestParam(defaultValue = "0") long after,
            @RequestParam(defaultValue = "20") int limit) {
        return noStore(catalog.attempts(actor, institutionId, classId, null, true, after, limit));
    }

    @GetMapping(CLASS + "/enrollments")
    public ResponseEntity<Page<EnrollmentRow>> enrollments(@AuthenticationPrincipal SessionPrincipal actor,
            @PathVariable long institutionId, @PathVariable long classId, @RequestParam(defaultValue = "0") long after,
            @RequestParam(defaultValue = "20") int limit) {
        return noStore(catalog.enrollments(actor, institutionId, classId, after, limit));
    }

    @GetMapping(CLASS + "/revisions")
    public ResponseEntity<Page<RevisionRow>> revisions(@AuthenticationPrincipal SessionPrincipal actor,
            @PathVariable long institutionId, @PathVariable long classId, @RequestParam(defaultValue = "0") long after,
            @RequestParam(defaultValue = "20") int limit) {
        return noStore(catalog.revisions(actor, institutionId, classId, after, limit));
    }

    private static <T> ResponseEntity<T> noStore(T payload) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(payload);
    }
}
