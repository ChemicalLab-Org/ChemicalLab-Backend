package com.morales.chemicallab.controller;

import com.morales.chemicallab.security.files.FileValidationService;
import org.springframework.http.*;
import java.nio.charset.StandardCharsets;
import java.util.Set;

/** One delivery policy for authenticated materials and final whiteboard snapshots. */
final class SafeFileResponse {
    private SafeFileResponse() {}
    static ResponseEntity<byte[]> of(byte[] data, String contentType, String name) {
        boolean image = Set.of("image/png", "image/jpeg").contains(contentType);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType(contentType));
        headers.setContentDisposition(ContentDisposition.builder(image ? "inline" : "attachment")
                .filename(FileValidationService.safeName(name), StandardCharsets.UTF_8).build());
        headers.setContentLength(data.length);
        headers.setCacheControl("no-store, private");
        headers.set("X-Content-Type-Options", "nosniff");
        headers.set("Content-Security-Policy", "sandbox; default-src 'none'; frame-ancestors 'none'");
        headers.set("X-Frame-Options", "DENY");
        return ResponseEntity.ok().headers(headers).body(data);
    }
}
