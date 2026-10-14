package com.morales.chemicallab.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.morales.chemicallab.entity.LogEventType;
import com.morales.chemicallab.entity.institution.AcademicRevision;
import com.morales.chemicallab.repository.AcademicRevisionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

/** P04 disposable-fixture maintainer, deliberately absent from controllers and live writers.
 * No caller-supplied deltas, inferred memberships, reconciliation repair or production adoption.
 */
@Service @RequiredArgsConstructor @Transactional
public class InstitutionStorageMaintenanceService {
    private final JdbcTemplate jdbc;
    private final AuditLogService audit;
    private final AcademicRevisionMaintenanceService academic;
    private final AcademicRevisionRepository revisions;
    private static final ObjectMapper JSON=new ObjectMapper();

    public String checksum() { lock();return jdbc.queryForObject("select t11b9_checksum()",String.class); }

    /** Only the first explicitly approved exact historical photograph may exceed fixture limits. */
    public long baseline(String checksum,String evidence,UUID operation) {
        lock();return command("BASELINE",Map.of("checksum",checksum,"evidence",evidence),checksum,operation);
    }
    public long limit(long institution,String category,long rows,long bytes,UUID operation) {
        lock();return command("LIMIT",Map.of("institution",institution,"category",category,"rows",rows,"bytes",bytes),null,operation);
    }
    /** Records independent source/counter differences; never changes either to hide divergence. */
    public long reconcile(UUID operation) { lock();return command("VERIFY",Map.of(),null,operation); }
    public String result(long command) {return jdbc.queryForObject("select result::text from t11b9_commands where id=?",String.class,command);}

    public AcademicRevision nextDraft(long published,UUID operation) {
        lock();String request=json(Map.of("nextDraft",published));Long prior=prior("SYNC",request,operation);
        if(prior!=null)return academicRevision(prior);
        fresh();var revision=academic.nextDraft(published,operation);
        command("SYNC",Map.of("nextDraft",published,"result",revision.id()),null,operation);
        return revision;
    }
    public AcademicRevision edit(long revision,long version,String payload,UUID operation) {
        lock();String request=json(Map.of("edit",revision,"version",version,"payloadDigest",digest(payload)));Long prior=prior("SYNC",request,operation);
        if(prior!=null)return academicRevision(prior);
        fresh();var changed=academic.edit(revision,version,payload,operation);
        command("SYNC",Map.of("edit",revision,"version",version,"payloadDigest",digest(payload),"result",changed.id()),null,operation);
        return changed;
    }
    /** Physical fixture removal only. Stored revisions and historical copies remain charged. */
    public long removeFixtureMaterial(long material,UUID operation) {
        lock();var request=Map.of("removeMaterial",material);Long previous=prior("SYNC",json(request),operation);if(previous!=null)return previous;
        fresh();if(jdbc.update("delete from concept_materials where id=?",material)!=1)throw new IllegalArgumentException("Existing source material required");
        return command("SYNC",request,null,operation);
    }
    private AcademicRevision academicRevision(long command) {
        long revision=jdbc.queryForObject("select (request->>'result')::bigint from t11b9_commands where id=?",Long.class,command);
        return revisions.revision(revision);
    }
    private void lock() {
        if(SecurityContextHolder.getContext().getAuthentication()!=null)throw new IllegalStateException("Internal fixtures only; T12 authorization pending");
        jdbc.execute("select t11b9_lock()");
    }
    private void fresh() {
        if(!Boolean.TRUE.equals(jdbc.queryForObject("select source_current from t11b9_observation",Boolean.class)))
            throw new IllegalStateException("Storage measurement stale or divergent; explicit reconciliation required, no automatic repair");
    }
    private Long prior(String kind,String request,UUID operation) {
        Objects.requireNonNull(operation,"Operation UUID required");
        return jdbc.query("select id,kind=? and request-'result'=?::jsonb identical from t11b9_commands where operation_id=?",r->{
            if(!r.next())return null;if(!r.getBoolean(2))throw new IllegalArgumentException("Operation UUID reused with another request");return r.getLong(1);
        },kind,request,operation);
    }
    private long command(String kind,Map<String,?> request,String checksum,UUID operation) {
        var comparison=new HashMap<String,Object>(request);comparison.remove("result");
        Long previous=prior(kind,json(comparison),operation);if(previous!=null)return previous;
        long id=jdbc.queryForObject("insert into t11b9_commands(operation_id,kind,request,expected_checksum) values(?,?,?::jsonb,?) returning id",Long.class,operation,kind,json(request),checksum);
        audit.recordInfo(LogEventType.ADMIN_ACTION,"StorageMeasurement",id,null,null,null,null);return id;
    }
    private static String json(Object value) {
        try{return JSON.writeValueAsString(value);}catch(Exception failure){throw new IllegalArgumentException("Invalid internal request",failure);}
    }
    private static String digest(String value) {
        Objects.requireNonNull(value,"Payload required");
        try{return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));}
        catch(java.security.NoSuchAlgorithmException failure){throw new IllegalStateException(failure);}
    }
}
