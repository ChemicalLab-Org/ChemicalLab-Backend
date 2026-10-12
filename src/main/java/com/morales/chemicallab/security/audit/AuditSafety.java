package com.morales.chemicallab.security.audit;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Closed metadata vocabulary. Values of content, questions, feedback and secrets are discarded. */
public final class AuditSafety {
    private static final Set<String> IDS=Set.of("attemptId","answerId","questionId","adjustmentId","participantUserId","credentialsVersion","size","institutionId");
    private static final Set<String> FLAGS=Set.of("outOfTime","exited","interactionEnabled","revokedAll");
    private static final Set<String> ENUMS=Set.of("ADMINISTRADOR","DOCENTE","ESTUDIANTE","FILE","LINK","BONUS","PENALTY","FOLLOW_GLOBAL","ALLOWED","BLOCKED");
    private static final Set<String> REASONS=Set.of("ACCOUNT_RATE_LIMITED","ACCOUNT_NOT_FOUND","ACCOUNT_DISABLED","IDENTITY_CHANGED","PASSWORD_MISMATCH","ACCOUNT_CHANGED","HTTP_401","HTTP_403","HTTP_429","HTTP_400","HTTP_409","HTTP_413","HTTP_5XX","STOMP_AUTH","STOMP_PERMISSION","STOMP_INPUT","STOMP_FAILURE","STORAGE_QUOTA_EXCEEDED");
    public static String text(String value,int max){
        if(value==null)return null;
        return value.codePoints().filter(c->!Character.isISOControl(c) && Character.getType(c)!=Character.FORMAT && c!=0x2028 && c!=0x2029)
            .limit(max).collect(StringBuilder::new,StringBuilder::appendCodePoint,StringBuilder::append).toString();
    }
    public static String target(String value){return Set.of("UserAccount","Evaluation","ConceptContent","WhiteboardSession","TechnicalRetention","REST","STOMP","Institution","InstitutionMembership","MembershipRoleGrant").contains(value==null?"":value)?value:null;}
    public static Long requestedId(String path){
        if(path==null || path.length()>512)return null;
        var match=java.util.regex.Pattern.compile("/(?:whiteboards|users|concepts|evaluations|attempts)/(?:teacher/|student/|admin/)?([1-9][0-9]{0,17})(?:/|$)").matcher(path);
        return match.find()?Long.valueOf(match.group(1)):null;
    }
    public static String metadata(String input){
        if(input==null || input.length()>2048)return null;
        List<String> safe=new ArrayList<>();Set<String> seen=new HashSet<>();
        for(String part:input.split(";")){
            String[] kv=part.split("=",2);if(kv.length!=2 || !seen.add(kv[0]))continue;
            String k=kv[0],v=kv[1];
            boolean allowed=(IDS.contains(k)&&v.matches("[0-9]{1,18}")) || (FLAGS.contains(k)&&Set.of("true","false").contains(v))
                || (Set.of("role","type","override").contains(k)&&ENUMS.contains(v)) || (k.equals("reason")&&REASONS.contains(v)) || (k.equals("source")&&Set.of("BOOTSTRAP","DEMO").contains(v))
                || (Set.of("candidates","deleted","batchSize").contains(k)&&v.matches("[0-9]{1,4}")&&Integer.parseInt(v)<=1000)
                || (k.equals("cutoff")&&cutoff(v));
            if(allowed && safe.size()<12)safe.add(k+"="+v);
        }
        return safe.isEmpty()?null:String.join(";",safe);
    }
    private static boolean cutoff(String value){
        if(!value.matches("[+-]?[0-9]{4,9}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(?:\\.[0-9]{1,9})?"))return false;
        try {java.time.LocalDateTime.parse(value,java.time.format.DateTimeFormatter.ISO_LOCAL_DATE_TIME);return true;}
        catch(java.time.format.DateTimeParseException ex){return false;}
    }
    /** A declared login string may itself contain a pasted credential. Never store it verbatim. */
    public static String identifierFingerprint(String input){
        if(input==null)return null;
        try {return "sha256:"+HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text(input,100).trim().getBytes(StandardCharsets.UTF_8)));}
        catch(java.security.NoSuchAlgorithmException ex){throw new IllegalStateException("SHA-256 unavailable");}
    }
}
