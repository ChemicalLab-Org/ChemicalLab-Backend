package com.morales.chemicallab.controller;

import com.morales.chemicallab.dto.WhiteboardGlobalInteractionRequest;
import com.morales.chemicallab.dto.WhiteboardParticipantInteractionRequest;
import com.morales.chemicallab.security.SessionPrincipal;
import com.morales.chemicallab.security.WhiteboardScope;
import com.morales.chemicallab.security.WhiteboardScope.Target;
import com.morales.chemicallab.service.InstitutionalWhiteboardAccess;
import com.morales.chemicallab.service.InstitutionalWhiteboardService;
import com.morales.chemicallab.service.InstitutionalWhiteboardService.Action;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/institutions")
public class InstitutionalWhiteboardController {
    private static final String BASE = "/{institution}/classes/{clazz}/whiteboards/v1";
    private static final String TEACHER = BASE + "/teacher";
    private static final String STUDENT = BASE + "/student";
    private static final String BOARD = BASE + "/{view}/{board}";
    private static final String KEY = "Idempotency-Key";
    private final InstitutionalWhiteboardService boards;
    private final InstitutionalWhiteboardAccess access;

    public record Create(String name, String description) {}

    @GetMapping("/whiteboards/v1/capability")
    public ResponseEntity<?> capability() {
        return response(Map.of("enabled", access.installed()));
    }

    @GetMapping(TEACHER)
    public ResponseEntity<?> teacherBoards(@AuthenticationPrincipal SessionPrincipal actor,
            @PathVariable long institution, @PathVariable long clazz,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "25") int size) {
        return response(boards.teacherBoards(actor, new WhiteboardScope(institution, clazz, true), page, size));
    }

    @PostMapping(TEACHER)
    public ResponseEntity<?> create(@AuthenticationPrincipal SessionPrincipal actor, @PathVariable long institution,
            @PathVariable long clazz, @RequestHeader(KEY) UUID operation, @RequestBody Create request) {
        return response(boards.create(actor, new WhiteboardScope(institution, clazz, true), operation, request.name(), request.description()));
    }

    @GetMapping(STUDENT + "/active")
    public ResponseEntity<?> active(@AuthenticationPrincipal SessionPrincipal actor,
            @PathVariable long institution, @PathVariable long clazz,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "25") int size) {
        return response(boards.liveBoards(actor, new WhiteboardScope(institution, clazz, false), page, size));
    }

    @GetMapping(STUDENT + "/history")
    public ResponseEntity<?> history(@AuthenticationPrincipal SessionPrincipal actor,
            @PathVariable long institution, @PathVariable long clazz,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "25") int size) {
        return response(boards.history(actor, new WhiteboardScope(institution, clazz, false), page, size));
    }

    @GetMapping(BOARD)
    public ResponseEntity<?> detail(@AuthenticationPrincipal SessionPrincipal actor, @PathVariable long institution,
            @PathVariable long clazz, @PathVariable String view, @PathVariable long board) {
        var target = target(institution, clazz, view, board);
        return response(target.scope().teacher() ? boards.teacherDetail(actor, target) : boards.studentDetail(actor, target));
    }

    @GetMapping(BOARD + "/state")
    public ResponseEntity<?> state(@AuthenticationPrincipal SessionPrincipal actor, @PathVariable long institution,
            @PathVariable long clazz, @PathVariable String view, @PathVariable long board) {
        return response(boards.state(actor, target(institution, clazz, view, board)));
    }

    @GetMapping(BOARD + "/snapshot")
    public ResponseEntity<?> snapshot(@AuthenticationPrincipal SessionPrincipal actor, @PathVariable long institution,
            @PathVariable long clazz, @PathVariable String view, @PathVariable long board) {
        var file = boards.snapshot(actor, target(institution, clazz, view, board));
        return SafeFileResponse.of(file.data(), file.contentType(), file.fileName());
    }

    @PostMapping(STUDENT + "/{board}/join")
    public ResponseEntity<?> join(@AuthenticationPrincipal SessionPrincipal actor, @PathVariable long institution,
            @PathVariable long clazz, @PathVariable long board, @RequestHeader(KEY) UUID operation) {
        return response(boards.join(actor, target(institution, clazz, "student", board), operation));
    }

    @PostMapping(TEACHER + "/{board}/{transition:pause|resume}")
    public ResponseEntity<?> transition(@AuthenticationPrincipal SessionPrincipal actor, @PathVariable long institution,
            @PathVariable long clazz, @PathVariable long board, @PathVariable String transition, @RequestHeader(KEY) UUID operation) {
        return response(boards.change(actor, target(institution, clazz, "teacher", board), operation,
            "pause".equals(transition) ? Action.PAUSE : Action.RESUME, null));
    }

    @PatchMapping(TEACHER + "/{board}/interaction")
    public ResponseEntity<?> interaction(@AuthenticationPrincipal SessionPrincipal actor, @PathVariable long institution,
            @PathVariable long clazz, @PathVariable long board, @RequestHeader(KEY) UUID operation,
            @RequestBody WhiteboardGlobalInteractionRequest request) {
        return response(boards.change(actor, target(institution, clazz, "teacher", board), operation, Action.INTERACTION,
            request.interactionEnabled()));
    }

    @GetMapping(TEACHER + "/{board}/participants")
    public ResponseEntity<?> participants(@AuthenticationPrincipal SessionPrincipal actor, @PathVariable long institution,
            @PathVariable long clazz, @PathVariable long board) {
        return response(boards.teacherDetail(actor, target(institution, clazz, "teacher", board)).participants());
    }

    @PatchMapping(TEACHER + "/{board}/participants/{student}/interaction")
    public ResponseEntity<?> permission(@AuthenticationPrincipal SessionPrincipal actor, @PathVariable long institution,
            @PathVariable long clazz, @PathVariable long board, @PathVariable long student, @RequestHeader(KEY) UUID operation,
            @RequestBody WhiteboardParticipantInteractionRequest request) {
        return response(boards.permission(actor, target(institution, clazz, "teacher", board), operation, student,
            request.interactionOverride()));
    }

    @PostMapping(path = TEACHER + "/{board}/close", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> close(@AuthenticationPrincipal SessionPrincipal actor, @PathVariable long institution,
            @PathVariable long clazz, @PathVariable long board, @RequestHeader(KEY) UUID operation,
            @RequestParam long revision, @RequestPart("snapshot") MultipartFile snapshot) {
        return response(boards.close(actor, target(institution, clazz, "teacher", board), operation, revision, snapshot));
    }

    private static Target target(long institution, long clazz, String view, long board) {
        return new Target(new WhiteboardScope(institution, clazz, switch (view) {
            case "teacher" -> true;
            case "student" -> false;
            default -> throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }), board);
    }

    private static ResponseEntity<?> response(Object payload) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(payload);
    }
}
