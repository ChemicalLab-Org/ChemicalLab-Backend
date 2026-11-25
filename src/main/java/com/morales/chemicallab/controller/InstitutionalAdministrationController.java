package com.morales.chemicallab.controller;

import com.morales.chemicallab.security.SessionPrincipal;
import com.morales.chemicallab.service.InstitutionalAdministration;
import com.morales.chemicallab.service.InstitutionalAdministration.*;
import com.morales.chemicallab.service.InstitutionalSupervision;
import com.morales.chemicallab.service.InstitutionalSupervision.Section;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api")
public class InstitutionalAdministrationController {
    private static final String SCOPES = "/institution-administration/v1";
    private static final String PLATFORM = "/platform/institutions/v1";
    private static final String INSTITUTION = "/institutions/{institution}/administration/v1";
    private final InstitutionalAdministration admin;
    private final InstitutionalSupervision supervision;

    public record Id(long id) {}
    public record InstitutionName(String name) {}
    public record Status(@NotNull Boolean active) {}

    @GetMapping(SCOPES + "/access")
    public ResponseEntity<Access> access(@AuthenticationPrincipal SessionPrincipal actor) {
        return fresh(admin.access(actor));
    }

    @GetMapping(SCOPES + "/institutions")
    public ResponseEntity<Page> institutions(@AuthenticationPrincipal SessionPrincipal actor,
            @RequestParam(defaultValue = "0") long after, @RequestParam(defaultValue = "20") int limit) {
        return fresh(admin.institutions(actor, false, after, limit));
    }

    @GetMapping(PLATFORM)
    public ResponseEntity<Page> platformInstitutions(@AuthenticationPrincipal SessionPrincipal actor,
            @RequestParam(defaultValue = "0") long after, @RequestParam(defaultValue = "20") int limit) {
        return fresh(admin.institutions(actor, true, after, limit));
    }

    @PostMapping(PLATFORM)
    public ResponseEntity<Id> create(@AuthenticationPrincipal SessionPrincipal actor, @RequestBody InstitutionName body) {
        return fresh(new Id(admin.createInstitution(actor, body.name())));
    }

    @PatchMapping(PLATFORM + "/{institution}/state")
    public ResponseEntity<Void> state(@AuthenticationPrincipal SessionPrincipal actor,
            @PathVariable long institution, @Valid @RequestBody Status body) {
        admin.institutionState(actor, institution, body.active());
        return noContent();
    }

    @PostMapping(PLATFORM + "/{institution}/first-administrator")
    public ResponseEntity<CreatedMember> firstAdmin(@AuthenticationPrincipal SessionPrincipal actor,
            @PathVariable long institution, @RequestBody NewAccount body) {
        return fresh(admin.firstAdmin(actor, institution, body));
    }

    @GetMapping(INSTITUTION + "/records/{section}")
    public ResponseEntity<Page> records(@AuthenticationPrincipal SessionPrincipal actor, @PathVariable long institution,
            @PathVariable Section section, @RequestParam(defaultValue = "EVALUATIONS") Library library,
            @RequestParam(defaultValue = "0") long after, @RequestParam(defaultValue = "20") int limit) {
        return fresh(supervision.list(actor, institution, section, library, after, limit));
    }

    @GetMapping(INSTITUTION + "/summary")
    public ResponseEntity<Map<String,Object>> summary(@AuthenticationPrincipal SessionPrincipal actor, @PathVariable long institution) {
        return fresh(supervision.summary(actor, institution));
    }

    @GetMapping(INSTITUTION + "/activity")
    public ResponseEntity<Page> activity(@AuthenticationPrincipal SessionPrincipal actor, @PathVariable long institution,
            @RequestParam(required = false) Long member, @RequestParam(defaultValue = "0") long before,
            @RequestParam(defaultValue = "20") int limit) {
        return fresh(supervision.events(actor, institution, false, member, before, limit));
    }

    @GetMapping(INSTITUTION + "/audit")
    public ResponseEntity<Page> audit(@AuthenticationPrincipal SessionPrincipal actor, @PathVariable long institution,
            @RequestParam(required = false) Long member, @RequestParam(defaultValue = "0") long before,
            @RequestParam(defaultValue = "20") int limit) {
        return fresh(supervision.events(actor, institution, true, member, before, limit));
    }

    @PostMapping(INSTITUTION + "/members")
    public ResponseEntity<CreatedMember> member(@AuthenticationPrincipal SessionPrincipal actor, @PathVariable long institution,
            @RequestBody NewAccount body) {
        return fresh(admin.member(actor, institution, body));
    }

    @PatchMapping(INSTITUTION + "/members/{member}/state/{state}")
    public ResponseEntity<Void> memberState(@AuthenticationPrincipal SessionPrincipal actor, @PathVariable long institution,
            @PathVariable long member, @PathVariable MemberState state) {
        admin.membershipState(actor, institution, member, state);
        return noContent();
    }

    @PutMapping(INSTITUTION + "/members/{member}/roles/{role}")
    public ResponseEntity<Id> role(@AuthenticationPrincipal SessionPrincipal actor, @PathVariable long institution,
            @PathVariable long member, @PathVariable MemberRole role) {
        return fresh(new Id(admin.role(actor, institution, member, role)));
    }

    @DeleteMapping(INSTITUTION + "/members/{member}/roles/{role}")
    public ResponseEntity<Void> revokeRole(@AuthenticationPrincipal SessionPrincipal actor, @PathVariable long institution,
            @PathVariable long member, @PathVariable MemberRole role) {
        admin.revokeRole(actor, institution, member, role);
        return noContent();
    }

    @PostMapping(INSTITUTION + "/periods")
    public ResponseEntity<Id> period(@AuthenticationPrincipal SessionPrincipal actor, @PathVariable long institution,
            @RequestBody NewPeriod body) {
        return fresh(new Id(admin.period(actor, institution, body)));
    }

    @PostMapping(INSTITUTION + "/classes")
    public ResponseEntity<Id> clazz(@AuthenticationPrincipal SessionPrincipal actor, @PathVariable long institution,
            @RequestBody NewClass body) {
        return fresh(new Id(admin.createClass(actor, institution, body)));
    }

    @PatchMapping(INSTITUTION + "/periods/{id}/close")
    public ResponseEntity<Void> closePeriod(@AuthenticationPrincipal SessionPrincipal actor, @PathVariable long institution,
            @PathVariable long id) {
        admin.close(actor, institution, id, true);
        return noContent();
    }

    @PatchMapping(INSTITUTION + "/classes/{id}/close")
    public ResponseEntity<Void> closeClass(@AuthenticationPrincipal SessionPrincipal actor, @PathVariable long institution,
            @PathVariable long id) {
        admin.close(actor, institution, id, false);
        return noContent();
    }

    @PostMapping(INSTITUTION + "/enrollments")
    public ResponseEntity<Id> enroll(@AuthenticationPrincipal SessionPrincipal actor, @PathVariable long institution,
            @RequestBody NewEpisode body) {
        return fresh(new Id(admin.episode(actor, institution, body, null)));
    }

    @PostMapping(INSTITUTION + "/teaching")
    public ResponseEntity<Id> teach(@AuthenticationPrincipal SessionPrincipal actor, @PathVariable long institution,
            @RequestBody NewTeaching body) {
        if (body.faculty() == null) throw new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.BAD_REQUEST, "Selecciona la función docente.");
        return fresh(new Id(admin.episode(actor, institution,
                new NewEpisode(body.classId(), body.membershipId(), body.until()), body.faculty())));
    }

    @PatchMapping(INSTITUTION + "/enrollments/{id}/end")
    public ResponseEntity<Void> endEnrollment(@AuthenticationPrincipal SessionPrincipal actor, @PathVariable long institution,
            @PathVariable long id) {
        admin.endEpisode(actor, institution, id, false);
        return noContent();
    }

    @PatchMapping(INSTITUTION + "/teaching/{id}/end")
    public ResponseEntity<Void> endTeaching(@AuthenticationPrincipal SessionPrincipal actor, @PathVariable long institution,
            @PathVariable long id) {
        admin.endEpisode(actor, institution, id, true);
        return noContent();
    }

    @PostMapping(INSTITUTION + "/library/{library}/faculties")
    public ResponseEntity<Id> faculty(@AuthenticationPrincipal SessionPrincipal actor, @PathVariable long institution,
            @PathVariable Library library, @RequestBody NewFaculty body) {
        if (body.faculty() == null) throw new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.BAD_REQUEST, "Selecciona la facultad de biblioteca.");
        return fresh(new Id(admin.faculty(actor, institution, library, body)));
    }

    @DeleteMapping(INSTITUTION + "/library/{library}/faculties/{id}")
    public ResponseEntity<Void> revokeFaculty(@AuthenticationPrincipal SessionPrincipal actor, @PathVariable long institution,
            @PathVariable Library library, @PathVariable long id) {
        admin.revokeFaculty(actor, institution, library, id);
        return noContent();
    }

    private static ResponseEntity<Void> noContent() {
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    private static <T> ResponseEntity<T> fresh(T value) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(value);
    }
}
