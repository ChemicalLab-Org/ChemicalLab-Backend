package com.morales.chemicallab.security.limits;

import com.fasterxml.jackson.core.*;
import org.springframework.stereotype.Component;
import java.io.*;
import java.util.*;

/** Streaming preflight before MVC/STOMP DTOs or board trees are allocated. No global mapper overrides. */
@Component
public class JsonInputGuard {
    private final TransportLimits limits;
    private final JsonFactory factory;
    private final JsonFactory ordinaryFactory;
    public JsonInputGuard(TransportLimits limits) {
        this.limits = limits;
        factory = JsonFactory.builder().streamReadConstraints(StreamReadConstraints.builder()
                .maxNestingDepth(limits.getJsonDepth()).maxStringLength(limits.getStateChars())
                .maxNameLength(Math.max(100, limits.getMetadataKeyChars())).maxNumberLength(32).build())
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();
        ordinaryFactory = JsonFactory.builder().streamReadConstraints(StreamReadConstraints.builder()
                .maxNestingDepth(limits.getJsonDepth()).maxStringLength(limits.getJsonStringChars())
                .maxNameLength(Math.max(100, limits.getMetadataKeyChars())).maxNumberLength(32).build())
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();
    }
    public void check(byte[] bytes, boolean stateEnvelope, boolean telemetry) {
        try (var parser = (stateEnvelope ? factory : ordinaryFactory).createParser(bytes)) { check(parser, stateEnvelope, telemetry); }
        catch (InputRejectedException ex) { throw ex; }
        catch (com.fasterxml.jackson.core.exc.StreamConstraintsException ex) { throw InputRejectedException.size(); }
        catch (IOException ex) { throw InputRejectedException.invalid(); }
    }
    public void checkState(String raw) {
        if (raw.length() > limits.getStateChars()) throw InputRejectedException.size();
        try (var parser = ordinaryFactory.createParser(raw)) { check(parser, false, false); }
        catch (InputRejectedException ex) { throw ex; }
        catch (com.fasterxml.jackson.core.exc.StreamConstraintsException ex) { throw InputRejectedException.size(); }
        catch (IOException ex) { throw InputRejectedException.invalid(); }
    }
    /** NEW_V1 uses exact typed identities/content; do not let MVC coerce another JSON type. */
    public void checkExecution(byte[] bytes) {
        check(bytes,false,false);
        try(var parser=ordinaryFactory.createParser(bytes)) {
            String field=null;JsonToken token;
            while((token=parser.nextToken())!=null) {
                if(token==JsonToken.FIELD_NAME){field=parser.currentName();continue;}
                if(field!=null) {
                    switch(field) {
                        case "revisionId","questionId","selectedOptionId" -> {
                            if(token!=JsonToken.VALUE_NULL){if(token!=JsonToken.VALUE_NUMBER_INT)throw InputRejectedException.invalid();parser.getLongValue();}
                        }
                        case "score" -> {
                            if(token!=JsonToken.VALUE_NULL){if(token!=JsonToken.VALUE_NUMBER_INT)throw InputRejectedException.invalid();parser.getIntValue();}
                        }
                        case "operationId","answerText","feedback","overallFeedback","reason" -> {
                            if(token!=JsonToken.VALUE_NULL && token!=JsonToken.VALUE_STRING)throw InputRejectedException.invalid();
                        }
                        case "amount" -> {
                            if(token!=JsonToken.VALUE_NULL && token!=JsonToken.VALUE_NUMBER_INT && token!=JsonToken.VALUE_NUMBER_FLOAT)throw InputRejectedException.invalid();
                            if(token!=JsonToken.VALUE_NULL) {
                                var value=parser.getDecimalValue();
                                if(value.precision()>32 || value.scale() < -32 || value.scale()>32)throw InputRejectedException.invalid();
                            }
                        }
                        default -> { }
                    }
                    field=null;
                }
            }
        } catch(InputRejectedException ex){throw ex;}
          catch(IOException ex){throw InputRejectedException.invalid();}
    }
    private void check(JsonParser parser, boolean envelope, boolean telemetry) throws IOException {
        record Frame(boolean object, boolean metadata, int[] count) {}
        var stack = new ArrayDeque<Frame>();
        int roots = 0;
        String field = null;
        JsonToken token;
        while ((token = parser.nextToken()) != null) {
            if (token == JsonToken.FIELD_NAME) {
                field = parser.currentName();
                var parent = stack.peek();
                if (parent == null || !parent.object()) throw InputRejectedException.invalid();
                if (++parent.count()[0] > (parent.metadata() ? limits.getMetadataEntries() : limits.getJsonProperties())) throw InputRejectedException.size();
                if (parent.metadata() && field.length() > limits.getMetadataKeyChars()) throw InputRejectedException.size();
                continue;
            }
            if (token == JsonToken.END_ARRAY || token == JsonToken.END_OBJECT) { stack.pop(); field = null; continue; }
            var parent = stack.peek();
            if (parent == null) { if (++roots > 1 || token != JsonToken.START_OBJECT) throw InputRejectedException.invalid(); }
            else if (!parent.object() && ++parent.count()[0] > limits.getJsonCollection()) throw InputRejectedException.size();
            if (token == JsonToken.START_OBJECT || token == JsonToken.START_ARRAY) {
                if (parent != null && parent.metadata()) throw InputRejectedException.invalid();
                boolean metadata = telemetry && stack.size() == 1 && "metadata".equals(field);
                if (metadata && token != JsonToken.START_OBJECT) throw InputRejectedException.invalid();
                stack.push(new Frame(token == JsonToken.START_OBJECT, metadata, new int[1]));
            } else {
                if (parent != null && parent.metadata() && token != JsonToken.VALUE_STRING && token != JsonToken.VALUE_NULL) throw InputRejectedException.invalid();
                if (token == JsonToken.VALUE_STRING) {
                    int max = parent != null && parent.metadata() ? limits.getMetadataValueChars()
                            : envelope && stack.size() == 1 && "stateJson".equals(field) ? limits.getStateChars() : limits.getJsonStringChars();
                    if (parser.getTextLength() > max) throw InputRejectedException.size();
                }
            }
            field = null;
        }
        if (roots != 1 || !stack.isEmpty()) throw InputRejectedException.invalid();
    }
    public void metadata(Map<String, String> metadata) {
        if (metadata == null) return;
        if (metadata.size() > limits.getMetadataEntries()) throw InputRejectedException.size();
        metadata.forEach((key, value) -> {
            if (key == null || key.length() > limits.getMetadataKeyChars() || value != null && value.length() > limits.getMetadataValueChars()) throw InputRejectedException.size();
        });
    }
}
