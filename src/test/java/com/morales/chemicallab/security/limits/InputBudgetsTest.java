package com.morales.chemicallab.security.limits;

import org.junit.jupiter.api.Test;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class InputBudgetsTest {
    final TransportLimits limits = new TransportLimits();
    final JsonInputGuard guard = new JsonInputGuard(limits);
    final BoardStateValidator board = new BoardStateValidator(limits, guard);
    void check(String raw) { guard.check(raw.getBytes(StandardCharsets.UTF_8), false, true); }
    @Test void metadataEdgesBeforeMapConstruction() {
        var entries = new ArrayList<String>();
        for (int i = 0; i < 32; i++) entries.add("\"key" + i + "\":\"" + "x".repeat(300) + "\"");
        check("{\"metadata\":{" + String.join(",", entries) + "}}");
        entries.add("\"extra\":\"x\"");
        assertThatThrownBy(() -> check("{\"metadata\":{" + String.join(",", entries) + "}}" )).isInstanceOf(InputRejectedException.class);
        check("{\"metadata\":{\"" + "k".repeat(60) + "\":\"x\"}}");
        assertThatThrownBy(() -> check("{\"metadata\":{\"" + "k".repeat(61) + "\":\"x\"}}" )).isInstanceOf(InputRejectedException.class);
        assertThatThrownBy(() -> check("{\"metadata\":{\"key\":\"" + "x".repeat(301) + "\"}}" )).isInstanceOf(InputRejectedException.class);
        assertThatThrownBy(() -> check("{\"metadata\":{\"key\":{}}}" )).isInstanceOf(InputRejectedException.class);
    }
    @Test void duplicateTrailingDepthStringsAndCollectionsAreRejected() {
        assertThatThrownBy(() -> check("{\"x\":1,\"x\":2}" )).isInstanceOf(InputRejectedException.class);
        assertThatThrownBy(() -> check("{}{}" )).isInstanceOf(InputRejectedException.class);
        check("{\"x\":\"" + "x".repeat(5000) + "\"}");
        assertThatThrownBy(() -> check("{\"x\":\"" + "x".repeat(5001) + "\"}" )).isInstanceOf(InputRejectedException.class);
        check("{\"x\":" + "[".repeat(11) + "0" + "]".repeat(11) + "}");
        assertThatThrownBy(() -> check("{\"x\":" + "[".repeat(12) + "0" + "]".repeat(12) + "}" )).isInstanceOf(InputRejectedException.class);
        check("{\"x\":[" + String.join(",", Collections.nCopies(10000, "0")) + "]}");
        assertThatThrownBy(() -> check("{\"x\":[" + String.join(",", Collections.nCopies(10001, "0")) + "]}" )).isInstanceOf(InputRejectedException.class);
    }
    @Test void completeBoardSchemaAndHistoricalPolicy() {
        String valid = "{\"v\":1,\"strokes\":[],\"texts\":[],\"shapes\":[{\"id\":\"a\",\"type\":\"LINE\",\"x1\":-100000,\"y1\":0,\"x2\":100000,\"y2\":1,\"color\":\"#123456\",\"strokeWidth\":100}]}";
        board.read(valid, false);
        for (String invalid : List.of(valid.replace("100000", "100001"), valid.replace("\"x2\":100000", "\"x2\":1e999"),
                valid.replace("\"v\":1", "\"v\":2"), valid.replace("\"strokeWidth\":100", "\"strokeWidth\":0"),
                valid.replace("\"type\":\"LINE\"", "\"type\":\"HTML\""), valid.replace("\"id\":\"a\"", "\"id\":\"\""),
                valid.replace("\"id\":\"a\"", "\"id\":\"a\",\"unknown\":1"), "{}", "{\"v\":1,\"texts\":[],\"strokes\":[1]}"))
            assertThatThrownBy(() -> board.read(invalid, true)).isInstanceOf(InputRejectedException.class);
    }
    @Test void objectPointTextAndIdentifierEdges() throws Exception {
        var json = new ObjectMapper();
        var root = board.read(null, false);
        var strokes = (com.fasterxml.jackson.databind.node.ArrayNode)root.get("strokes");
        for (int i = 0; i < 2000; i++) {
            var item = strokes.addObject().put("id", "s" + i).put("eventType", "DRAW");
            item.putArray("points").addObject().put("x", 0).put("y", 0);
        }
        board.read(root.toString(), false);
        strokes.add(strokes.get(0).deepCopy());
        assertThatThrownBy(() -> board.read(root.toString(), false)).isInstanceOf(InputRejectedException.class);
        strokes.remove(2000);
        ((com.fasterxml.jackson.databind.node.ObjectNode)strokes.get(1)).put("id", "s0");
        assertThatThrownBy(() -> board.read(root.toString(), false)).isInstanceOf(InputRejectedException.class);
        var textRoot = board.read(null, false);
        var text = ((com.fasterxml.jackson.databind.node.ArrayNode)textRoot.get("texts")).addObject().put("id", "t".repeat(100))
                .put("wx", 0).put("wy", 0).put("color", "#123").put("size", 400);
        text.putArray("runs").addObject().put("text", "x".repeat(5000)).put("bold", false).put("italic", false).put("underline", false);
        board.read(textRoot.toString(), false);
        text.put("id", "t".repeat(101));
        assertThatThrownBy(() -> board.read(textRoot.toString(), false)).isInstanceOf(InputRejectedException.class);
        limits.setBoardPoints(2000);
        ((com.fasterxml.jackson.databind.node.ObjectNode)strokes.get(1)).put("id", "s1");
        board.validate(root, false);
        ((com.fasterxml.jackson.databind.node.ArrayNode)strokes.get(0).get("points")).addObject().put("x", 1).put("y", 1);
        assertThatThrownBy(() -> board.validate(root, false)).isInstanceOf(InputRejectedException.class);
    }
    @Test void accountBudgetsSurviveMultipleSessionsExpireAndDoNotEvictLiveAccounts() {
        limits.setDrawBurst(2); limits.setDrawPerSecond(1); limits.setRateAccounts(2);
        var traffic = new AccountTrafficLimits(limits);
        traffic.take(1, "draw", 0); traffic.take(1, "draw", 0);
        assertThatThrownBy(() -> traffic.take(1, "draw", 0)).isInstanceOf(InputRejectedException.class);
        traffic.take(2, "draw", 0);
        assertThatThrownBy(() -> traffic.take(3, "draw", 0)).isInstanceOf(InputRejectedException.class).extracting("status").isEqualTo(503);
        traffic.take(1, "draw", 1_000_000_000L);
        traffic.take(3, "draw", 62_000_000_000L);
        assertThat(traffic.size()).isEqualTo(1);
    }
}
