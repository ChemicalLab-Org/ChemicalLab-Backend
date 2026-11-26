package com.morales.chemicallab.subscription;

import com.fasterxml.jackson.databind.JsonNode;
import com.morales.chemicallab.security.SessionPrincipal;
import com.morales.chemicallab.service.InstitutionalAdministration;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

import static com.morales.chemicallab.subscription.SubscriptionLedger.error;

@RestController
@RequestMapping("/api")
class SubscriptionController {
    static final String WEBHOOK = "/api/subscriptions/v1/mercado-pago/webhook";
    private static final String SCHOOL = "/institutions/{school}/subscription/v1";
    private static final String PLATFORM = "/platform/institutions/{school}/subscription/v1";
    private static final String EVIDENCE = "^[A-Z0-9][A-Z0-9_-]{5,63}$";

    record Verification(@Pattern(regexp = EVIDENCE) String evidenceReference) {}
    record Authority(@Positive long accountId, @Pattern(regexp = EVIDENCE) String evidenceReference,
                     OffsetDateTime validUntil) {}

    private final SubscriptionLedger ledger;
    private final SubscriptionWorkflow workflow;
    private final InstitutionalAdministration administration;
    private final SubscriptionSettings settings;

    SubscriptionController(SubscriptionLedger ledger, SubscriptionWorkflow workflow,
                           InstitutionalAdministration administration, SubscriptionSettings settings) {
        this.ledger = ledger;
        this.workflow = workflow;
        this.administration = administration;
        this.settings = settings;
    }

    @GetMapping("/subscriptions/v1/institutions")
    ResponseEntity<SubscriptionLedger.Options> options(@AuthenticationPrincipal SessionPrincipal actor,
                                                       @RequestParam(defaultValue = "0") long after) {
        return fresh(ledger.options(actor.userId(), after));
    }

    @GetMapping(SCHOOL)
    ResponseEntity<SubscriptionLedger.Overview> overview(@AuthenticationPrincipal SessionPrincipal actor, @PathVariable long school) {
        return fresh(ledger.overview(school, actor.userId()));
    }

    @GetMapping(SCHOOL + "/operations")
    ResponseEntity<List<SubscriptionLedger.Operation>> operations(@AuthenticationPrincipal SessionPrincipal actor,
            @PathVariable long school, @RequestParam(defaultValue = "9223372036854775807") long before) {
        return fresh(ledger.history(school, actor.userId(), before));
    }

    @PostMapping(SCHOOL + "/checkout")
    ResponseEntity<SubscriptionLedger.Overview> checkout(@AuthenticationPrincipal SessionPrincipal actor,
                                                         @PathVariable long school, @RequestBody JsonNode command) {
        if (!command.isObject() || command.size() != 1 || !command.path("confirmed").isBoolean()
                || !command.path("confirmed").asBoolean()) {
            throw error(HttpStatus.BAD_REQUEST, "Confirme la autorización para abrir el checkout de prueba.");
        }
        workflow.checkout(school, actor.userId());
        return fresh(ledger.overview(school, actor.userId()));
    }

    @PostMapping(SCHOOL + "/cancel-renewal")
    ResponseEntity<SubscriptionLedger.Overview> cancel(@AuthenticationPrincipal SessionPrincipal actor, @PathVariable long school) {
        ledger.requestCancellation(school, actor.userId());
        return fresh(ledger.overview(school, actor.userId()));
    }

    @PostMapping(PLATFORM + "/verify")
    ResponseEntity<Void> verify(@AuthenticationPrincipal SessionPrincipal actor, @PathVariable long school,
                                @Valid @RequestBody Verification input) {
        operator(actor);
        ledger.verifySchool(school, actor.userId(), evidence(input.evidenceReference()));
        return noContent();
    }

    @PostMapping(PLATFORM + "/authority")
    ResponseEntity<Void> authority(@AuthenticationPrincipal SessionPrincipal actor, @PathVariable long school,
                                   @Valid @RequestBody Authority input) {
        operator(actor);
        ledger.recordAuthority(school, input.accountId(), actor.userId(), evidence(input.evidenceReference()), input.validUntil());
        return noContent();
    }

    @PostMapping("/subscriptions/v1/mercado-pago/webhook")
    ResponseEntity<Void> notify(@RequestParam("data.id") String id,
            @RequestHeader("x-signature") String signature, @RequestHeader("x-request-id") String request,
            @RequestBody JsonNode body) {
        ledger.enabled();
        if (!validSignature(id, request, signature, settings.webhookSecret(), Instant.now())
                || !id.equalsIgnoreCase(body.path("data").path("id").asText())
                || body.path("user_id").asLong() != settings.sellerId()
                || !body.path("live_mode").isBoolean() || body.path("live_mode").asBoolean()) {
            throw error(HttpStatus.UNAUTHORIZED, "Notificación sin autenticar.");
        }
        String type = body.path("type").asText();
        if (SubscriptionWorkflow.TOPICS.contains(type)) ledger.notice(type, id.toLowerCase(Locale.ROOT), request);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).build();
    }

    static boolean validSignature(String id, String request, String signature, String secret, Instant now) {
        if (!id.matches("[A-Za-z0-9_-]{1,80}") || !request.matches("[A-Za-z0-9_-]{1,100}") || signature.length() > 256) return false;
        var fields = new java.util.HashMap<String,String>();
        for (String field : signature.split(",", -1)) {
            var pair = field.trim().split("=", 2);
            if (pair.length != 2 || fields.putIfAbsent(pair[0], pair[1]) != null) return false;
        }
        String ts = fields.getOrDefault("ts", "");
        String hash = fields.getOrDefault("v1", "");
        if (!ts.matches("[0-9]{10}|[0-9]{13}") || !hash.matches("[a-fA-F0-9]{64}")) return false;
        Instant created = ts.length() == 10 ? Instant.ofEpochSecond(Long.parseLong(ts)) : Instant.ofEpochMilli(Long.parseLong(ts));
        if (created.isAfter(now.plus(Duration.ofMinutes(5))) || created.isBefore(now.minus(Duration.ofDays(3)))) return false;
        String message = "id:" + id.toLowerCase(Locale.ROOT) + ";request-id:" + request + ";ts:" + ts + ";";
        try {
            Mac hmac = Mac.getInstance("HmacSHA256");
            hmac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return MessageDigest.isEqual(hmac.doFinal(message.getBytes(StandardCharsets.UTF_8)), HexFormat.of().parseHex(hash));
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("Webhook signature verification is unavailable", ex);
        }
    }

    private void operator(SessionPrincipal actor) {
        ledger.enabled();
        if (!administration.access(actor).platform()) throw error(HttpStatus.FORBIDDEN, "Se requiere un operador de plataforma.");
    }

    private static String evidence(String value) {
        if (value == null) throw error(HttpStatus.BAD_REQUEST, "Indique una referencia de verificación.");
        return value;
    }

    private static <T> ResponseEntity<T> fresh(T body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }

    private static ResponseEntity<Void> noContent() {
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }
}
