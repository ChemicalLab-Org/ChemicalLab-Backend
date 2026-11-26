package com.morales.chemicallab.agreement;

import com.morales.chemicallab.security.SessionPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/agreements")
@RequiredArgsConstructor
public class AgreementController {
    private final AgreementService agreements;
    private final AgreementReviews reviews;

    @GetMapping("/public")
    public ResponseEntity<List<AgreementService.Document>> documents() {
        return fresh(agreements.publicDocuments());
    }

    @GetMapping("/public/{id}")
    public ResponseEntity<AgreementService.Text> publicText(@PathVariable long id) {
        return fresh(agreements.publicText(id));
    }

    @GetMapping("/account")
    public ResponseEntity<AgreementService.Overview> overview(@AuthenticationPrincipal SessionPrincipal actor) {
        return fresh(agreements.overview(actor.userId()));
    }

    @GetMapping("/account/versions/{id}")
    public ResponseEntity<AgreementService.Text> accountText(@AuthenticationPrincipal SessionPrincipal actor,
                                                              @PathVariable long id) {
        return fresh(agreements.accountText(actor.userId(), id));
    }

    @GetMapping("/account/history")
    public ResponseEntity<AgreementService.History> history(@AuthenticationPrincipal SessionPrincipal actor,
            @RequestParam(defaultValue = "9223372036854775807") long before) {
        return fresh(agreements.history(actor.userId(), before));
    }

    @PostMapping("/account/versions/{id}/decisions")
    public ResponseEntity<AgreementService.Decision> submit(@AuthenticationPrincipal SessionPrincipal actor,
            @PathVariable long id, @RequestBody AgreementService.Submission input) {
        return fresh(agreements.submit(actor.userId(), id, input));
    }

    @GetMapping("/account/family-status")
    public ResponseEntity<List<AgreementReviews.FamilyStatus>> familyStatus(@AuthenticationPrincipal SessionPrincipal actor) {
        return fresh(reviews.familyStatus(actor.userId()));
    }

    @GetMapping("/review/permissions")
    public ResponseEntity<AgreementReviews.Permissions> permissions(@AuthenticationPrincipal SessionPrincipal actor) {
        return fresh(reviews.permissions(actor.userId()));
    }

    @PostMapping("/review/directors/{institution}/{membership}")
    public ResponseEntity<AgreementReviews.Review> reviewDirector(@AuthenticationPrincipal SessionPrincipal actor,
            @PathVariable long institution, @PathVariable long membership, @RequestBody AgreementReviews.DirectorInput input) {
        return fresh(reviews.director(actor.userId(), institution, membership, input));
    }

    @GetMapping("/review/directors/{institution}/{membership}")
    public ResponseEntity<List<AgreementReviews.Review>> directorHistory(@AuthenticationPrincipal SessionPrincipal actor,
            @PathVariable long institution, @PathVariable long membership, @RequestParam long document,
            @RequestParam(defaultValue = "9223372036854775807") long before) {
        return fresh(reviews.directorHistory(actor.userId(), institution, membership, document, before));
    }

    @PostMapping("/review/families/{institution}/{membership}")
    public ResponseEntity<AgreementReviews.Review> reviewFamily(@AuthenticationPrincipal SessionPrincipal actor,
            @PathVariable long institution, @PathVariable long membership, @RequestBody AgreementReviews.FamilyInput input) {
        return fresh(reviews.family(actor.userId(), institution, membership, input));
    }

    @GetMapping("/review/families/{institution}/{membership}")
    public ResponseEntity<List<AgreementReviews.Review>> familyHistory(@AuthenticationPrincipal SessionPrincipal actor,
            @PathVariable long institution, @PathVariable long membership,
            @RequestParam(defaultValue = "9223372036854775807") long before) {
        return fresh(reviews.familyHistory(actor.userId(), institution, membership, before));
    }

    private static <T> ResponseEntity<T> fresh(T body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }
}
