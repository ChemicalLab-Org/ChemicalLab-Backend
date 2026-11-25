package com.morales.chemicallab.controller;

import com.morales.chemicallab.dto.InstitutionalContent.*;
import com.morales.chemicallab.dto.MaterialDownload;
import com.morales.chemicallab.security.SessionPrincipal;
import com.morales.chemicallab.service.InstitutionalContentInput;
import com.morales.chemicallab.service.InstitutionalContentService;
import com.morales.chemicallab.service.InstitutionalContentService.Action;
import com.morales.chemicallab.service.InstitutionalEvaluationCatalog.Page;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/institutions")
public class InstitutionalContentController {
    private static final String ROOT = "/{institution}/classes/{clazz}/contents/NEW/v1";
    private static final String TEACHER = ROOT + "/library/{content}/revisions/{revision}";
    private static final String STUDENT = ROOT + "/assigned/{delivery}/contents/{content}/revisions/{revision}";
    private final InstitutionalContentService contents;
    private final InstitutionalContentInput input;

    @GetMapping("/content-runtime")
    public ResponseEntity<Map<String, Boolean>> capability() {
        return response(Map.of("enabled", contents.installed()));
    }

    @GetMapping("/content-catalog/NEW/v1/classes")
    public ResponseEntity<Page<Choice>> choices(@AuthenticationPrincipal SessionPrincipal actor,
            @RequestParam(defaultValue = "0") long after, @RequestParam(defaultValue = "20") int limit) {
        return response(contents.choices(actor, after, limit));
    }

    @GetMapping(ROOT + "/library")
    public ResponseEntity<Page<Row>> library(@AuthenticationPrincipal SessionPrincipal actor, @PathVariable long institution,
            @PathVariable long clazz, @RequestParam(defaultValue = "0") long after,
            @RequestParam(defaultValue = "20") int limit) {
        return response(contents.teacherList(actor, new Scope(institution, clazz), after, limit));
    }

    @GetMapping(ROOT + "/assigned")
    public ResponseEntity<Page<Delivery>> assigned(@AuthenticationPrincipal SessionPrincipal actor, @PathVariable long institution,
            @PathVariable long clazz, @RequestParam(defaultValue = "0") long after,
            @RequestParam(defaultValue = "20") int limit) {
        return response(contents.studentList(actor, new Scope(institution, clazz), after, limit));
    }

    @GetMapping(TEACHER)
    public ResponseEntity<Detail> teacherDetail(@AuthenticationPrincipal SessionPrincipal actor,
            @PathVariable long institution, @PathVariable long clazz, @PathVariable long content, @PathVariable long revision) {
        return response(contents.detail(actor, new Scope(institution, clazz), new Revision(content, revision), null));
    }

    @GetMapping(STUDENT)
    public ResponseEntity<Detail> studentDetail(@AuthenticationPrincipal SessionPrincipal actor, @PathVariable long institution,
            @PathVariable long clazz, @PathVariable long delivery, @PathVariable long content, @PathVariable long revision) {
        return response(contents.detail(actor, new Scope(institution, clazz), new Revision(content, revision), delivery));
    }

    @PostMapping(value = ROOT + "/library", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Receipt> create(@AuthenticationPrincipal SessionPrincipal actor, @PathVariable long institution,
            @PathVariable long clazz, @RequestBody byte[] body) {
        return response(contents.write(actor, new Scope(institution, clazz), null, Action.CREATE, input.read(body, false, true)));
    }

    @PutMapping(value = TEACHER, consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Receipt> edit(@AuthenticationPrincipal SessionPrincipal actor, @PathVariable long institution,
            @PathVariable long clazz, @PathVariable long content, @PathVariable long revision, @RequestBody byte[] body) {
        return response(contents.write(actor, new Scope(institution, clazz), new Revision(content, revision),
            Action.EDIT, input.read(body, true, true)));
    }

    @PostMapping(value = TEACHER + "/{operation:publish|successor|assign|remove-file}", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Receipt> transition(@AuthenticationPrincipal SessionPrincipal actor, @PathVariable long institution,
            @PathVariable long clazz, @PathVariable long content, @PathVariable long revision,
            @PathVariable String operation, @RequestBody byte[] body) {
        Action action = switch (operation) {
            case "publish" -> Action.PUBLISH;
            case "successor" -> Action.SUCCESSOR;
            case "assign" -> Action.ASSIGN;
            case "remove-file" -> Action.REMOVE_FILE;
            default -> throw new IllegalStateException("Unexpected content transition");
        };
        return response(contents.write(actor, new Scope(institution, clazz), new Revision(content, revision),
            action, input.read(body, true, false)));
    }

    @PostMapping(value = TEACHER + "/file", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Receipt> file(@AuthenticationPrincipal SessionPrincipal actor, @PathVariable long institution,
            @PathVariable long clazz, @PathVariable long content, @PathVariable long revision,
            @RequestParam UUID operationId, @RequestParam long version, @RequestPart MultipartFile file,
            @RequestParam(required = false) String title) {
        return response(contents.upload(actor, new Scope(institution, clazz), new Revision(content, revision),
            operationId, version, file, title));
    }

    @GetMapping(TEACHER + "/files/{file}")
    public ResponseEntity<byte[]> teacherDownload(@AuthenticationPrincipal SessionPrincipal actor, @PathVariable long institution,
            @PathVariable long clazz, @PathVariable long content, @PathVariable long revision, @PathVariable long file,
            @RequestParam(defaultValue = "false") boolean preview) {
        return binary(contents.download(actor, new Scope(institution, clazz), new Revision(content, revision), null, file, preview));
    }

    @GetMapping(STUDENT + "/files/{file}")
    public ResponseEntity<byte[]> studentDownload(@AuthenticationPrincipal SessionPrincipal actor, @PathVariable long institution,
            @PathVariable long clazz, @PathVariable long delivery, @PathVariable long content, @PathVariable long revision,
            @PathVariable long file, @RequestParam(defaultValue = "false") boolean preview) {
        return binary(contents.download(actor, new Scope(institution, clazz), new Revision(content, revision), delivery, file, preview));
    }

    private static ResponseEntity<byte[]> binary(MaterialDownload file) {
        return SafeFileResponse.of(file.data(), file.contentType(), file.fileName());
    }

    private static <T> ResponseEntity<T> response(T value) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(value);
    }
}
