package com.morales.chemicallab.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;

/** Strict executable subset of B8. Reject incompatible publication; never repair it. */
final class PublishedEvaluationContent {
    record Option(long id,String text,int order,boolean correct) {}
    record Question(long id,String text,String type,int points,int order,boolean required,
                    String expected,String explanation,List<Option> options) {
        boolean open(){return type.equals("OPEN_TEXT");}
    }
    final ObjectNode configuration;
    final List<Question> questions;
    final Integer minutes;
    final boolean sequential,random;
    PublishedEvaluationContent(JsonNode payload) {
        check(payload!=null && payload.isObject() && payload.path("resource").isObject()
            && payload.path("questions").isArray() && payload.path("options").isArray());
        configuration=(ObjectNode)payload.get("resource").deepCopy();
        text(configuration,"title",200,true);text(configuration,"description",5000,false);
        text(configuration,"instructions",5000,false);text(configuration,"topic",200,false);
        integer(configuration,"max_attempts",1,10,true);
        minutes=integer(configuration,"time_limit_minutes",1,240,false);
        String mode=text(configuration,"question_display_mode",30,true);
        check(Set.of("ALL_AT_ONCE","ONE_BY_ONE").contains(mode));sequential=mode.equals("ONE_BY_ONE");
        random=bool(configuration,"randomize_questions",false);
        for(String name:List.of("allow_chemical_calculator","allow_periodic_table","track_tab_exit"))bool(configuration,name,false);
        check(payload.get("questions").size()<=200 && payload.get("options").size()<=2000);
        Set<Long> ids=new HashSet<>(),optionIds=new HashSet<>();
        Map<Long,List<Option>> options=new HashMap<>();
        for(JsonNode q:payload.get("questions")){check(q.isObject());check(ids.add(id(q.get("id"))));}
        for(JsonNode o:payload.get("options")) {
            check(o.isObject());long id=id(o.get("id")),parent=id(o.get("question_id"));
            check(optionIds.add(id) && ids.contains(parent));
            if(!bool(o,"active",true))continue;
            var list=options.computeIfAbsent(parent,k->new ArrayList<>());
            list.add(new Option(id,text(o,"option_text",2000,true),integer(o,"order_index",0,100000,false)==null?0:o.get("order_index").intValue(),bool(o,"correct",false)));
            check(list.size()<=10);
        }
        var result=new ArrayList<Question>();int maximum=0;
        for(JsonNode q:payload.get("questions")) {
            if(!bool(q,"active",true))continue;
            long id=id(q.get("id"));String type=text(q,"question_type",30,true);
            check(Set.of("MULTIPLE_CHOICE","OPEN_TEXT").contains(type));
            var choices=new ArrayList<>(options.getOrDefault(id,List.of()));
            choices.sort(Comparator.comparingInt(Option::order).thenComparingLong(Option::id));
            check(type.equals("OPEN_TEXT")?choices.isEmpty():choices.size()>=2 && choices.stream().filter(Option::correct).count()==1);
            int points=integer(q,"points",1,100000,true);maximum=Math.addExact(maximum,points);
            Integer order=integer(q,"order_index",0,100000,false);
            result.add(new Question(id,text(q,"question_text",5000,true),type,points,order==null?0:order,bool(q,"required",true),
                text(q,"expected_answer",3000,false),text(q,"explanation",5000,false),List.copyOf(choices)));
        }
        check(!result.isEmpty());result.sort(Comparator.comparingInt(Question::order).thenComparingLong(Question::id));questions=List.copyOf(result);
    }
    Question question(long id){return questions.stream().filter(q->q.id()==id).findFirst().orElseThrow(PublishedEvaluationContent::invalid);}
    static long id(JsonNode n){check(n!=null && n.isIntegralNumber() && n.canConvertToLong() && n.longValue()>0);return n.longValue();}
    static String text(JsonNode n,String key,int maximum,boolean required) {
        JsonNode v=n.get(key);if(v==null || v.isNull()){check(!required);return null;}
        check(v.isTextual() && v.textValue().length()<=maximum && (!required || !v.textValue().isBlank()));return v.textValue();
    }
    static Integer integer(JsonNode n,String key,int low,int high,boolean required) {
        JsonNode v=n.get(key);if(v==null || v.isNull()){check(!required);return null;}
        check(v.isIntegralNumber() && v.canConvertToInt() && v.intValue()>=low && v.intValue()<=high);return v.intValue();
    }
    static boolean bool(JsonNode n,String key,boolean fallback){JsonNode v=n.get(key);if(v==null)return fallback;check(v.isBoolean());return v.booleanValue();}
    static void check(boolean valid){if(!valid)throw invalid();}
    static ResponseStatusException invalid(){return new ResponseStatusException(HttpStatus.FORBIDDEN,"Publicación incompatible.");}
}
