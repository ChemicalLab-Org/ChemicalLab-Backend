package com.morales.chemicallab.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static com.morales.chemicallab.service.PublishedEvaluationContent.*;

/** B8/F content profiles with a wider state space for unfinished drafts. */
final class EvaluationAuthoringContent {
    private static final Set<String> ROOT = Set.of("resource", "questions", "options");
    private static final Set<String> RESOURCE = Set.of("title", "description", "instructions", "topic", "max_attempts",
        "time_limit_minutes", "question_display_mode", "randomize_questions", "allow_chemical_calculator",
        "allow_periodic_table", "track_tab_exit");
    private static final Set<String> QUESTION = Set.of("id", "question_text", "question_type", "points",
        "order_index", "active", "required", "expected_answer", "explanation");
    private static final Set<String> OPTION = Set.of("id", "question_id", "option_text", "order_index", "active", "correct");
    private static final List<String> PROVENANCE = List.of("id", "created_by_teacher_id", "created_at", "updated_at", "status", "active");

    private EvaluationAuthoringContent() {}

    static ObjectNode input(JsonNode value) {
        try {
            fields(value, ROOT);
            JsonNode resource = value.path("resource");
            fields(resource, RESOURCE);
            text(resource, "title", 150, true);
            text(resource, "description", 1000, false);
            text(resource, "instructions", 2000, false);
            text(resource, "topic", 120, false);
            integer(resource, "max_attempts", 1, 10, true);
            integer(resource, "time_limit_minutes", 1, 240, false);
            check(Set.of("ONE_BY_ONE", "ALL_AT_ONCE").contains(text(resource, "question_display_mode", 30, true)));
            for (String key : List.of("randomize_questions", "allow_chemical_calculator", "allow_periodic_table", "track_tab_exit"))
                bool(resource, key, false);

            JsonNode questions = value.path("questions");
            JsonNode options = value.path("options");
            check(questions.isArray() && questions.size() <= 200 && options.isArray() && options.size() <= 2000);
            var questionIds = new HashSet<Long>();
            var choiceIds = new HashSet<Long>();
            var optionIds = new HashSet<Long>();
            for (JsonNode question : questions) {
                fields(question, QUESTION);
                long id = id(question.get("id"));
                check(questionIds.add(id));
                String type = text(question, "question_type", 30, true);
                check(Set.of("MULTIPLE_CHOICE", "OPEN_TEXT").contains(type));
                if (type.equals("MULTIPLE_CHOICE")) choiceIds.add(id);
                text(question, "question_text", 5000, true);
                text(question, "expected_answer", 3000, false);
                text(question, "explanation", 5000, false);
                integer(question, "points", 1, 100000, true);
                integer(question, "order_index", 0, 100000, false);
                bool(question, "active", true);
                bool(question, "required", true);
            }
            for (JsonNode option : options) {
                fields(option, OPTION);
                check(optionIds.add(id(option.get("id"))) && choiceIds.contains(id(option.get("question_id"))));
                text(option, "option_text", 2000, true);
                integer(option, "order_index", 0, 100000, false);
                bool(option, "active", true);
                bool(option, "correct", false);
            }
            return ((ObjectNode)value).deepCopy();
        } catch (ResponseStatusException invalid) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Contenido académico no válido.");
        }
    }

    static ObjectNode draftView(ObjectNode stored) {
        ObjectNode view = stored.deepCopy();
        view.retain(ROOT);
        ((ObjectNode)view.path("resource")).retain(RESOURCE);
        for (JsonNode question : view.path("questions")) ((ObjectNode)question).retain(QUESTION);
        for (JsonNode option : view.path("options")) ((ObjectNode)option).retain(OPTION);
        return view;
    }

    static void preserveProvenance(ObjectNode next, ObjectNode old) {
        if (old.has("author")) next.set("author", old.get("author"));
        var resource = (ObjectNode) next.path("resource");
        for (String key : PROVENANCE) if (old.path("resource").has(key)) resource.set(key, old.path("resource").get(key));
    }

    private static void fields(JsonNode value, Set<String> allowed) {
        check(value.isObject());
        value.fieldNames().forEachRemaining(name -> check(allowed.contains(name)));
    }
}
