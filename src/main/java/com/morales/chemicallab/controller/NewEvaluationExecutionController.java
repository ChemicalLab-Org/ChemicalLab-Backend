package com.morales.chemicallab.controller;

import com.morales.chemicallab.dto.NewEvaluationExecution.*;
import com.morales.chemicallab.dto.InstitutionalAttemptReview;
import com.morales.chemicallab.security.SessionPrincipal;
import com.morales.chemicallab.service.NewEvaluationExecutionService;
import jakarta.validation.Valid;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.math.BigDecimal;
import java.util.*;

/** Versioned namespace: every attempt ID here selects ONLY NEW_V1 persistent execution. */
@RestController
@RequestMapping("/api/institutions/{institutionId}/classes/{classId}/evaluation-execution/NEW/v1/evaluations/{evaluationId}/deliveries/{deliveryId}/revisions/{revisionId}")
public class NewEvaluationExecutionController {
    private final NewEvaluationExecutionService service;
    public NewEvaluationExecutionController(NewEvaluationExecutionService service){this.service=service;}
    private long id(Map<String,String> ids,String name){return Long.parseLong(ids.get(name));}
    private Long attempt(Map<String,String> ids){return ids.containsKey("attemptId")?id(ids,"attemptId"):null;}
    private ResponseEntity<Object> read(SessionPrincipal actor,Map<String,String> ids,String projection,Long recipient) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.read(actor,id(ids,"institutionId"),id(ids,"classId"),id(ids,"evaluationId"),id(ids,"deliveryId"),id(ids,"revisionId"),attempt(ids),projection,recipient));
    }
    private ResponseEntity<Receipt> write(SessionPrincipal actor,Map<String,String> ids,String operation,Long child,UUID key,Answer answer,List<Answer> answers,Integer score,BigDecimal amount,String text) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.write(actor,id(ids,"institutionId"),id(ids,"classId"),id(ids,"evaluationId"),id(ids,"deliveryId"),id(ids,"revisionId"),attempt(ids),operation,child,key,answer,answers,score,amount,text));
    }
    @GetMapping
    public ResponseEntity<Object> delivery(@AuthenticationPrincipal SessionPrincipal actor,@PathVariable Map<String,String> ids,@RequestParam(required=false) Long recipientAccountId){return read(actor,ids,"delivery",recipientAccountId);}
    @GetMapping("/own-attempts/{attemptId}")
    public ResponseEntity<Object> own(@AuthenticationPrincipal SessionPrincipal actor,@PathVariable Map<String,String> ids){return read(actor,ids,"own",null);}
    @GetMapping("/own-attempts/{attemptId}/correction")
    public ResponseEntity<Object> correction(@AuthenticationPrincipal SessionPrincipal actor,@PathVariable Map<String,String> ids){return read(actor,ids,"correction",null);}
    @GetMapping("/attempts/{attemptId}/review")
    public ResponseEntity<Object> review(@AuthenticationPrincipal SessionPrincipal actor,@PathVariable Map<String,String> ids){return read(actor,ids,"review",null);}
    @PostMapping("/own-attempts")
    public ResponseEntity<Receipt> start(@AuthenticationPrincipal SessionPrincipal actor,@PathVariable Map<String,String> ids,@Valid @RequestBody Command body){return write(actor,ids,"START",null,body.operationId(),null,null,null,null,null);}
    @PostMapping("/own-attempts/{attemptId}/answers")
    public ResponseEntity<Receipt> save(@AuthenticationPrincipal SessionPrincipal actor,@PathVariable Map<String,String> ids,@Valid @RequestBody Save body){return write(actor,ids,"SAVE",null,body.operationId(),body.answer(),null,null,null,null);}
    @PostMapping("/own-attempts/{attemptId}/submit")
    public ResponseEntity<Receipt> submit(@AuthenticationPrincipal SessionPrincipal actor,@PathVariable Map<String,String> ids,@Valid @RequestBody Submit body){return write(actor,ids,"SUBMIT",null,body.operationId(),null,body.answers(),null,null,null);}
    @PostMapping("/attempts/{attemptId}/review/answers/{answerId}")
    public ResponseEntity<Receipt> grade(@AuthenticationPrincipal SessionPrincipal actor,@PathVariable Map<String,String> ids,@Valid @RequestBody InstitutionalAttemptReview.Grade body){return write(actor,ids,"GRADE",id(ids,"answerId"),body.operationId(),null,null,body.score(),null,body.feedback());}
    @PostMapping("/attempts/{attemptId}/review/complete")
    public ResponseEntity<Receipt> complete(@AuthenticationPrincipal SessionPrincipal actor,@PathVariable Map<String,String> ids,@Valid @RequestBody Command body){return write(actor,ids,"COMPLETE",null,body.operationId(),null,null,null,null,null);}
    @PostMapping("/attempts/{attemptId}/review/adjustments")
    public ResponseEntity<Receipt> add(@AuthenticationPrincipal SessionPrincipal actor,@PathVariable Map<String,String> ids,@Valid @RequestBody InstitutionalAttemptReview.Adjustment body){return write(actor,ids,"ADD",null,body.operationId(),null,null,null,body.amount(),body.reason());}
    @PostMapping("/attempts/{attemptId}/review/adjustments/{adjustmentId}/void")
    public ResponseEntity<Receipt> remove(@AuthenticationPrincipal SessionPrincipal actor,@PathVariable Map<String,String> ids,@Valid @RequestBody Command body){return write(actor,ids,"REMOVE",id(ids,"adjustmentId"),body.operationId(),null,null,null,null,null);}
    @PostMapping("/attempts/{attemptId}/review/feedback")
    public ResponseEntity<Receipt> feedback(@AuthenticationPrincipal SessionPrincipal actor,@PathVariable Map<String,String> ids,@Valid @RequestBody InstitutionalAttemptReview.Feedback body){return write(actor,ids,"FEEDBACK",null,body.operationId(),null,null,null,null,body.overallFeedback());}
    @PostMapping("/attempts/{attemptId}/review/close")
    public ResponseEntity<Receipt> close(@AuthenticationPrincipal SessionPrincipal actor,@PathVariable Map<String,String> ids,@Valid @RequestBody Command body){return write(actor,ids,"CLOSE",null,body.operationId(),null,null,null,null,null);}
}
