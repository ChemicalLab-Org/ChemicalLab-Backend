package com.morales.chemicallab.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.morales.chemicallab.dto.InstitutionalContent.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class InstitutionalContentInput {
    private final ObjectMapper json;

    public Command read(byte[] body, boolean version, boolean text) {
        try {
            JsonNode node = json.readTree(body);
            fields(node, Set.of("operationId", "version", "text"));
            String key = value(node.get("operationId"), 36, true);
            UUID id = UUID.fromString(key);
            if (!id.toString().equalsIgnoreCase(key)) throw invalid();
            JsonNode v = node.get("version");
            if (version && (v == null || !v.isIntegralNumber() || !v.canConvertToLong() || v.longValue() < 1)) {
                throw invalid();
            }
            if (!version && v != null || !text && node.has("text")) throw invalid();
            return new Command(id, version ? v.longValue() : null, text ? readText(node.get("text")) : null);
        } catch (IOException failure) {
            throw invalid();
        } catch (IllegalArgumentException failure) {
            throw invalid();
        }
    }

    private Text readText(JsonNode node) {
        fields(node, Set.of("title", "category", "summary", "explanation", "formationSteps", "keyPoints",
            "examples", "suggestedActivity", "links"));
        var links = new ArrayList<Link>();
        for (JsonNode link : array(node.get("links"), 10)) {
            fields(link, Set.of("title", "url"));
            links.add(new Link(value(link.get("title"), 150, false), safeUrl(value(link.get("url"), 2048, true))));
        }
        return new Text(value(node.get("title"), 150, true), value(node.get("category"), 100, true),
            value(node.get("summary"), 500, false), value(node.get("explanation"), 20000, false),
            lines(node.get("formationSteps")), lines(node.get("keyPoints")), lines(node.get("examples")),
            value(node.get("suggestedActivity"), 2000, false), List.copyOf(links));
    }

    private static String safeUrl(String value) {
        var uri = URI.create(value);
        if (uri.getScheme() == null || !Set.of("http", "https").contains(uri.getScheme().toLowerCase(Locale.ROOT))
            || uri.getHost() == null || uri.getRawUserInfo() != null) throw invalid();
        return uri.toASCIIString();
    }

    private static List<String> lines(JsonNode node) {
        var result = new ArrayList<String>();
        for (JsonNode item : array(node, 15)) result.add(value(item, 500, true));
        return List.copyOf(result);
    }

    private static Iterable<JsonNode> array(JsonNode node, int max) {
        if (node == null) return List.of();
        if (!node.isArray() || node.size() > max) throw invalid();
        return node;
    }

    private static void fields(JsonNode node, Set<String> allowed) {
        if (node == null || !node.isObject()) throw invalid();
        node.fieldNames().forEachRemaining(name -> { if (!allowed.contains(name)) throw invalid(); });
    }

    private static String value(JsonNode node, int max, boolean required) {
        if (node == null || node.isNull()) {
            if (required) throw invalid();
            return null;
        }
        if (!node.isTextual()) throw invalid();
        return clean(node.textValue(), max, required);
    }

    static String clean(String value, int max, boolean required) {
        if (value == null || value.isBlank()) {
            if (required) throw invalid();
            return null;
        }
        if (value.length() > max || value.codePoints().anyMatch(c -> Character.isISOControl(c)
            && c != '\r' && c != '\n' && c != '\t')) throw invalid();
        return value.strip();
    }

    static IllegalArgumentException invalid() {
        return new IllegalArgumentException("Los datos del contenido o sus materiales no son válidos.");
    }
}
