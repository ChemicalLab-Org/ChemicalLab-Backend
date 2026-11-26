package com.morales.chemicallab.subscription;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.StreamSupport;

@Component
class MercadoPagoSubscriptions {
    static final class Unverified extends RuntimeException {
        Unverified() { super("Mercado Pago response could not be verified"); }
    }

    private final SubscriptionSettings settings;
    private final ObjectMapper json;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(4))
            .followRedirects(HttpClient.Redirect.NEVER).build();

    MercadoPagoSubscriptions(SubscriptionSettings settings, ObjectMapper json) {
        this.settings = settings;
        this.json = json;
    }

    void verifyTestSeller() {
        if (settings.mode() == SubscriptionSettings.Mode.LOCAL_TEST) return;
        JsonNode user = get("/users/me");
        require(user.path("id").asLong() == settings.sellerId() && "MPE".equals(user.path("site_id").asText())
                && StreamSupport.stream(user.path("tags").spliterator(), false).anyMatch(tag -> "test_user".equals(tag.asText())));
    }

    JsonNode create(UUID reference, long institutionId, String name, BigDecimal amount, String currency) {
        return send("POST", "/preapproval", Map.of("status", "pending", "reason", reason(name, reference),
                "external_reference", reference.toString(), "payer_email", settings.buyerEmail(),
                "back_url", settings.returnUrl(institutionId), "auto_recurring", Map.of(
                        "frequency", 1, "frequency_type", "months", "transaction_amount", amount, "currency_id", currency)));
    }

    List<String> find(UUID reference, String name) {
        var query = "/preapproval/search?limit=20&q=" + URLEncoder.encode(reason(name, reference), StandardCharsets.UTF_8);
        return results(query).stream().filter(row -> reference.toString().equals(row.path("external_reference").asText()))
                .map(row -> checkedId(row.path("id").asText())).distinct().toList();
    }

    JsonNode subscription(String id) { return get("/preapproval/" + checkedId(id)); }
    JsonNode invoice(String id) { return get("/authorized_payments/" + checkedId(id)); }
    JsonNode payment(String id) { return get("/v1/payments/" + checkedId(id)); }

    List<String> invoices(String parameter, String id, int offset) {
        return results("/authorized_payments/search?limit=20&offset=" + offset + "&" + parameter + "=" + checkedId(id))
                .stream().map(row -> checkedId(row.path("id").asText())).distinct().toList();
    }

    void cancel(String id) {
        verifyTestSeller();
        send("PUT", "/preapproval/" + checkedId(id), Map.of("status", "canceled"));
    }

    private List<JsonNode> results(String path) {
        JsonNode results = get(path).path("results");
        require(results.isArray());
        return StreamSupport.stream(results.spliterator(), false).toList();
    }

    private JsonNode get(String path) { return send("GET", path, null); }

    private JsonNode send(String method, String path, Object body) {
        try {
            var request = HttpRequest.newBuilder(settings.api().resolve(path)).timeout(Duration.ofSeconds(8))
                    .header("Accept", "application/json");
            if (settings.mode() == SubscriptionSettings.Mode.MERCADO_PAGO_TEST) {
                request.header("Authorization", "Bearer " + settings.token());
            }
            if (body == null) request.GET();
            else request.header("Content-Type", "application/json").method(method,
                    HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
            var response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
            require(response.statusCode() >= 200 && response.statusCode() < 300);
            JsonNode result = json.readTree(response.body());
            require(result != null && result.isObject());
            return result;
        } catch (IOException ex) {
            throw new Unverified();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new Unverified();
        }
    }

    static String reason(String name, UUID ref) { return name + " " + ref; }

    static String checkedId(String id) {
        require(id != null && id.matches("[A-Za-z0-9_-]{1,80}"));
        return id;
    }

    static OffsetDateTime time(JsonNode node, String field) {
        if (!node.hasNonNull(field)) return null;
        try { return OffsetDateTime.parse(node.path(field).asText()); }
        catch (java.time.format.DateTimeParseException ex) { throw new Unverified(); }
    }

    static boolean amount(JsonNode node, String field, BigDecimal expected) {
        try { return new BigDecimal(node.path(field).asText()).compareTo(expected) == 0; }
        catch (NumberFormatException ex) { return false; }
    }

    static void require(boolean condition) {
        if (!condition) throw new Unverified();
    }
}
