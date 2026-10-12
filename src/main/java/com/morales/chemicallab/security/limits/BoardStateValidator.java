package com.morales.chemicallab.security.limits;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import java.util.*;

/** Complete v1 schema; authorship is still established exclusively by T03 ownership rows. */
@Component @RequiredArgsConstructor
public class BoardStateValidator {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final TransportLimits limits;
    private final JsonInputGuard guard;
    public ObjectNode read(String raw, boolean legacy) {
        if (raw == null) {
            var empty = JSON.createObjectNode().put("v", 1);
            empty.putArray("strokes"); empty.putArray("texts"); empty.putArray("shapes"); return empty;
        }
        guard.checkState(raw);
        try {
            JsonNode node = JSON.readTree(raw);
            validate(node, legacy);
            return (ObjectNode) node;
        } catch (InputRejectedException ex) { throw ex; }
        catch (Exception ex) { throw InputRejectedException.invalid(); }
    }
    public void validate(JsonNode root, boolean legacy) {
        fields(root, "v", "revision", "strokes", "texts", "shapes");
        if (!root.path("v").isIntegralNumber() || root.path("v").asInt() != 1) throw InputRejectedException.invalid();
        if (root.has("revision")) integer(root.get("revision"), 0);
        int total = 0, points = 0;
        for (String kind : List.of("strokes", "texts", "shapes")) {
            JsonNode items = root.get(kind);
            if (items == null && kind.equals("shapes")) continue;
            if (items == null || !items.isArray()) throw InputRejectedException.invalid();
            total += items.size();
            if (total > limits.getBoardObjects()) throw InputRejectedException.size();
            Set<String> ids = new HashSet<>();
            for (JsonNode item : items) {
                String id = item.path("id").asText("");
                if (legacy && kind.equals("strokes") && !item.has("id")) { /* T03 assigns neutral legacy ids */ }
                else { identifier(item.get("id")); if (!ids.add(id)) throw InputRejectedException.invalid(); }
                if (item.hasNonNull("ownerUserId")) integer(item.get("ownerUserId"), 1);
                switch (kind) {
                    case "strokes" -> {
                        fields(item, "id", "ownerUserId", "eventType", "color", "strokeWidth", "eraserSize", "points");
                        oneOf(item.get("eventType"), "DRAW", "ERASE"); color(item.get("color"), true);
                        measure(item.get("strokeWidth"), 0.000001, 100, true); measure(item.get("eraserSize"), 0.000001, 200, true);
                        var coordinates = item.get("points");
                        if (coordinates == null || !coordinates.isArray() || coordinates.isEmpty()) throw InputRejectedException.invalid();
                        if (coordinates.size() > limits.getEventPoints()) throw InputRejectedException.size();
                        points += coordinates.size();
                        if (points > limits.getBoardPoints()) throw InputRejectedException.size();
                        for (JsonNode point : coordinates) { fields(point, "x", "y"); coordinate(point.get("x")); coordinate(point.get("y")); }
                    }
                    case "texts" -> {
                        fields(item, "id", "ownerUserId", "wx", "wy", "color", "size", "runs");
                        coordinate(item.get("wx")); coordinate(item.get("wy")); color(item.get("color"), false);
                        measure(item.get("size"), 4, 400, false);
                        var runs = item.get("runs");
                        if (runs == null || !runs.isArray() || runs.isEmpty()) throw InputRejectedException.invalid();
                        if (runs.size() > limits.getTextRuns()) throw InputRejectedException.size();
                        int length = 0;
                        for (JsonNode run : runs) {
                            fields(run, "text", "bold", "italic", "underline");
                            if (!run.path("text").isTextual()) throw InputRejectedException.invalid();
                            length += run.get("text").textValue().length();
                            for (String style : List.of("bold", "italic", "underline")) if (!run.path(style).isBoolean()) throw InputRejectedException.invalid();
                        }
                        if (length == 0) throw InputRejectedException.invalid();
                        if (length > limits.getTextChars()) throw InputRejectedException.size();
                    }
                    case "shapes" -> {
                        fields(item, "id", "ownerUserId", "type", "x1", "y1", "x2", "y2", "color", "strokeWidth");
                        oneOf(item.get("type"), "RECTANGLE", "CIRCLE", "LINE", "ARROW");
                        for (String axis : List.of("x1", "y1", "x2", "y2")) coordinate(item.get(axis));
                        color(item.get("color"), false); measure(item.get("strokeWidth"), 0.000001, 100, false);
                    }
                }
            }
        }
    }
    private void fields(JsonNode node, String... allowed) {
        if (node == null || !node.isObject()) throw InputRejectedException.invalid();
        var names = Set.of(allowed);
        node.fieldNames().forEachRemaining(field -> { if (!names.contains(field)) throw InputRejectedException.invalid(); });
    }
    private void identifier(JsonNode node) {
        if (node == null || !node.isTextual() || node.textValue().isBlank()) throw InputRejectedException.invalid();
        if (node.textValue().length() > 100) throw InputRejectedException.size();
    }
    private void integer(JsonNode node, long min) {
        if (!node.isIntegralNumber() || !node.canConvertToLong() || node.longValue() < min) throw InputRejectedException.invalid();
    }
    private void coordinate(JsonNode node) { measure(node, -limits.getCoordinate(), limits.getCoordinate(), false); }
    private void measure(JsonNode node, double min, double max, boolean nullable) {
        if (nullable && (node == null || node.isNull())) return;
        if (node == null || !node.isNumber() || !Double.isFinite(node.doubleValue()) || node.doubleValue() < min || node.doubleValue() > max) throw InputRejectedException.invalid();
    }
    private void color(JsonNode node, boolean nullable) {
        if (nullable && (node == null || node.isNull())) return;
        if (node == null || !node.isTextual() || !node.textValue().matches("#[0-9a-fA-F]{3}([0-9a-fA-F]{3})?")) throw InputRejectedException.invalid();
    }
    private void oneOf(JsonNode node, String... values) {
        if (node == null || !node.isTextual() || !Set.of(values).contains(node.textValue())) throw InputRejectedException.invalid();
    }
}
