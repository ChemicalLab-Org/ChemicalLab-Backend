package com.morales.chemicallab.service;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.morales.chemicallab.dto.*;
import com.morales.chemicallab.entity.*;
import com.morales.chemicallab.repository.WhiteboardObjectOwnershipRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import java.util.*;

/** Called only while holding the board row lock. State and authorship commit together. */
@Service
@RequiredArgsConstructor
public class WhiteboardObjectState {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final WhiteboardObjectOwnershipRepository ownership;

    /** Never expose client-declared authorship from a pre-T03 snapshot as verified metadata. */
    public static String snapshotForClient(WhiteboardSession board) {
        String raw = board.getCurrentStateJson();
        if (raw == null || board.getStateRevision() > 0) return raw;
        try {
            JsonNode root = JSON.readTree(raw);
            for (String field : List.of("texts", "shapes", "strokes")) {
                if (root.path(field) instanceof ArrayNode items) {
                    for (JsonNode item : items) if (item instanceof ObjectNode object) object.putNull("ownerUserId");
                }
            }
            return root.toString();
        } catch (Exception ex) { return raw; } // preserve historical data; edits still fail closed
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public WhiteboardDrawEventResponse apply(WhiteboardSession board, UserAccount actor, WhiteboardDrawEventResponse event) {
        ObjectNode state = read(board.getCurrentStateJson());
        Map<String, WhiteboardObjectOwnership> identities = new HashMap<>();
        ownership.findByBoard_Id(board.getId()).forEach(row -> identities.put(key(row.getKind(), row.getObjectId()), row));
        // Import the existing snapshot conservatively: visible names/prefixes never establish authorship.
        for (String kind : List.of("TEXT", "SHAPE", "STROKE")) {
            ArrayNode items = array(state, kind);
            for (JsonNode value : items) {
                if (!(value instanceof ObjectNode item)) throw invalidState();
                String id = item.path("id").asText();
                if (id.isBlank()) { id = "legacy-" + UUID.randomUUID(); item.put("id", id); }
                if (id.length() > 100) throw invalidState();
                var row = identities.get(key(kind, id));
                if (row == null) {
                    row = ownership.save(WhiteboardObjectOwnership.builder().board(board).kind(kind).objectId(id).build());
                    identities.put(key(kind, id), row);
                }
                putOwner(item, row);
            }
        }
        Long ownerId = null;
        String strokeId = event.strokeId();
        if (event.eventType() == WhiteboardDrawEventType.CLEAR) {
            identities.values().forEach(row -> row.setDeleted(true));
            for (String kind : List.of("TEXT", "SHAPE", "STROKE")) array(state, kind).removeAll();
        } else {
            String kind = switch (event.eventType()) {
                case TEXT, TEXT_DELETE -> "TEXT";
                case SHAPE, SHAPE_DELETE -> "SHAPE";
                default -> "STROKE";
            };
            boolean delete = switch (event.eventType()) {
                case TEXT_DELETE, SHAPE_DELETE, STROKE_DELETE -> true;
                default -> false;
            };
            if (kind.equals("STROKE") && strokeId == null) strokeId = UUID.randomUUID().toString();
            String id = switch (kind) { case "TEXT" -> event.textId(); case "SHAPE" -> event.shapeId(); default -> strokeId; };
            var row = identities.get(key(kind, id));
            if (row == null) {
                if (delete) throw new IllegalArgumentException("El objeto no existe.");
                row = ownership.save(WhiteboardObjectOwnership.builder().board(board).kind(kind).objectId(id).owner(actor).build());
            } else if (actor.getRole() != Role.DOCENTE && (row.getOwner() == null || !row.getOwner().getId().equals(actor.getId()))) {
                throw new IllegalArgumentException("Solo puedes modificar, borrar o restaurar tus propios objetos.");
            }
            ownerId = row.getOwner() == null ? null : row.getOwner().getId();
            row.setDeleted(delete);
            ArrayNode items = array(state, kind);
            int previous = -1;
            for (int i = items.size() - 1; i >= 0; i--) if (id.equals(items.get(i).path("id").asText())) { previous = i; items.remove(i); }
            if (!delete) {
                ObjectNode item = JSON.createObjectNode().put("id", id);
                putOwner(item, row);
                if (kind.equals("TEXT")) {
                    item.put("wx", event.points().get(0).x()).put("wy", event.points().get(0).y());
                    item.put("color", event.color() == null ? "#1a1a16" : event.color()).put("size", event.fontSize());
                    item.set("runs", JSON.valueToTree(event.runs()));
                } else if (kind.equals("SHAPE")) {
                    item.put("type", event.tool().name());
                    item.put("x1", event.points().get(0).x()).put("y1", event.points().get(0).y());
                    item.put("x2", event.points().get(1).x()).put("y2", event.points().get(1).y());
                    item.put("color", event.color() == null ? "#000000" : event.color());
                    item.put("strokeWidth", event.strokeWidth() == null ? 4 : event.strokeWidth());
                } else {
                    item.put("eventType", event.eventType().name()).put("color", event.color());
                    item.put("strokeWidth", event.strokeWidth()).put("eraserSize", event.eraserSize());
                    item.set("points", JSON.valueToTree(event.points()));
                }
                int at = previous >= 0 ? previous : kind.equals("STROKE") && event.strokeIndex() != null ? event.strokeIndex() : items.size();
                items.insert(Math.min(at, items.size()), item);
            }
        }
        long revision = board.getStateRevision() + 1;
        state.put("v", 1).put("revision", revision);
        String serialized = state.toString();
        if (serialized.length() > 2_000_000) throw new IllegalArgumentException("El estado de la pizarra supera el tamaño máximo permitido.");
        board.setCurrentStateJson(serialized);
        board.setStateRevision(revision);
        board.setStateUpdatedAt(LocalDateTime.now());
        return new WhiteboardDrawEventResponse(event.sessionId(), event.eventType(), event.tool(), event.color(),
                event.strokeWidth(), event.eraserSize(), event.points(), event.actorRole(), event.actorDisplayName(),
                event.clientEventId(), event.occurredAt(), event.textId(), event.fontSize(), event.runs(), event.shapeId(),
                strokeId, event.strokeIndex(), ownerId, revision);
    }

    private ObjectNode read(String raw) {
        if (raw == null || raw.isBlank()) return JSON.createObjectNode();
        try { if (JSON.readTree(raw) instanceof ObjectNode node) return node; }
        catch (Exception ignored) { /* preserve unparseable historical data, fail closed on edits */ }
        throw invalidState();
    }
    private ArrayNode array(ObjectNode state, String kind) {
        String field = switch (kind) { case "TEXT" -> "texts"; case "SHAPE" -> "shapes"; default -> "strokes"; };
        if (!state.has(field)) return state.putArray(field);
        if (state.get(field) instanceof ArrayNode array) return array;
        throw invalidState();
    }
    private void putOwner(ObjectNode item, WhiteboardObjectOwnership row) {
        if (row.getOwner() == null) item.putNull("ownerUserId"); else item.put("ownerUserId", row.getOwner().getId());
    }
    private String key(String kind, String id) { return kind + ":" + id; }
    private IllegalArgumentException invalidState() { return new IllegalArgumentException("El estado histórico no se puede editar de forma segura."); }
}
