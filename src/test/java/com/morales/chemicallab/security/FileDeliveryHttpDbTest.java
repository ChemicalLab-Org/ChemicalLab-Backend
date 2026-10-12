package com.morales.chemicallab.security;

import com.morales.chemicallab.entity.*;
import com.morales.chemicallab.repository.*;
import com.morales.chemicallab.security.files.*;
import com.morales.chemicallab.service.ConceptMaterialService;
import org.junit.jupiter.api.*;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

/** Real HTTP/filter/JPA delivery tests. Reuses T03's guarded, disposable PostgreSQL harness. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FileDeliveryHttpDbTest {
    final WhiteboardSecurityTransportDbTest host = new WhiteboardSecurityTransportDbTest() {
        @Override ConfigurableApplicationContext startApp(String... extra) {
            return System.getProperty("t06.fixture-file") == null ? super.startApp(extra)
                    : super.startApp("--server.port=18089", "--app.cors.allowed-origins=http://127.0.0.1:4206");
        }
    };
    UserAccount admin;
    long concept, foreignConcept;
    String teacherToken, studentToken, adminToken, foreignToken, outsiderToken;
    @BeforeAll void start() throws Exception { host.start(); }
    @AfterAll void cleanup() throws Exception { host.cleanup(); }
    @BeforeEach void fixture() throws Exception {
        host.fixture(); admin = host.account(Role.ADMINISTRADOR,null,"3","A");
        concept = concept(host.teacher,ConceptStatus.PUBLISHED,true);
        foreignConcept = concept(host.otherTeacher,ConceptStatus.PUBLISHED,true);
        teacherToken=host.login(host.teacher); studentToken=host.login(host.student); adminToken=host.login(admin);
        foreignToken=host.login(host.otherTeacher); outsiderToken=host.login(host.outsider);
    }
    long concept(UserAccount owner, ConceptStatus status, boolean assigned) {
        var tp=host.app.getBean(TeacherProfileRepository.class).findByUser(owner).orElseThrow();
        var content=host.app.getBean(ConceptContentRepository.class).saveAndFlush(ConceptContent.builder()
                .title("Materiales T06 ficticios").category("Óxidos").explanation("Contenido de prueba inocuo")
                .createdByTeacher(tp).status(status).active(true).build());
        if (assigned) host.app.getBean(ConceptAssignmentRepository.class).saveAndFlush(ConceptAssignment.builder()
                .conceptContent(content).teacher(tp).grade("3").section("A").active(true).build());
        return content.getId();
    }
    HttpResponse<byte[]> multipart(String path,String part,String name,String mime,byte[] bytes,String token) throws Exception {
        var out=new ByteArrayOutputStream(); String boundary="T06-fictitious-boundary";
        out.write(("--"+boundary+"\r\nContent-Disposition: form-data; name=\""+part+"\"; filename=\""+name+"\"\r\nContent-Type: "+mime+"\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(bytes);out.write(("\r\n--"+boundary+"--\r\n").getBytes(StandardCharsets.UTF_8));
        var builder=HttpRequest.newBuilder(URI.create(host.origin+path)).timeout(Duration.ofSeconds(25))
                .header("Content-Type","multipart/form-data; boundary="+boundary);
        if(token!=null)builder.header("Authorization","Bearer "+token);
        return host.http.send(builder.POST(HttpRequest.BodyPublishers.ofByteArray(out.toByteArray())).build(),HttpResponse.BodyHandlers.ofByteArray());
    }
    HttpResponse<byte[]> upload(long id,String ext,String mime,byte[] bytes,String token) throws Exception {
        return multipart("/api/concepts/teacher/"+id+"/materials/file","file","ficticio."+ext,mime,bytes,token);
    }
    long material(HttpResponse<byte[]> response) throws Exception {
        assertThat(response.statusCode()).as(new String(response.body(),StandardCharsets.UTF_8)).isEqualTo(201);
        return host.json.readTree(response.body()).path("materialId").asLong();
    }
    String path(long id,long material) { return "/api/concepts/"+id+"/materials/"+material+"/download"; }
    HttpResponse<byte[]> get(String path,String token) throws Exception {
        var builder=HttpRequest.newBuilder(URI.create(host.origin+path)).timeout(Duration.ofSeconds(25));
        if(token!=null)builder.header("Authorization","Bearer "+token);
        return host.http.send(builder.GET().build(),HttpResponse.BodyHandlers.ofByteArray());
    }
    byte[] stored(long material) { return host.sql().queryForObject("select file_data from concept_materials where id=?",byte[].class,material); }
    void headers(HttpResponse<byte[]> response,String mime,boolean image) {
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type")).contains(mime);
        assertThat(response.headers().firstValue("Content-Disposition").orElseThrow()).startsWith(image?"inline;":"attachment;");
        assertThat(response.headers().firstValue("X-Content-Type-Options")).contains("nosniff");
        assertThat(response.headers().firstValue("Cache-Control").orElseThrow()).contains("no-store");
        assertThat(response.headers().firstValue("Content-Security-Policy").orElseThrow()).contains("sandbox","default-src 'none'");
        assertThat(response.headers().firstValue("X-Frame-Options")).contains("DENY");
        assertThat(response.headers().firstValue("Content-Length")).contains(Integer.toString(response.body().length));
    }
    @Test void allFormatsUploadReplaceAndDeliverOnlyAfterValidation() throws Exception {
        var formats=List.of(new Object[]{"pdf","application/pdf",FileFixtures.pdf()},
                new Object[]{"ppt","application/vnd.ms-powerpoint",FileFixtures.ppt()},
                new Object[]{"pptx","application/vnd.openxmlformats-officedocument.presentationml.presentation",FileFixtures.pptx()},
                new Object[]{"png","image/png",FileFixtures.png()},new Object[]{"jpg","image/jpeg",FileFixtures.jpeg()});
        for(var format:formats) {
            var uploaded=upload(concept,(String)format[0],(String)format[1],(byte[])format[2],teacherToken);
            long id=material(uploaded);boolean image=((String)format[1]).startsWith("image/");
            assertThat(host.json.readTree(uploaded.body()).path("previewAvailable").asBoolean()).isEqualTo(image);
            for(String token:List.of(teacherToken,studentToken,adminToken)) headers(get(path(concept,id),token),(String)format[1],image);
            assertThat(host.sql().queryForObject("select count(*) from concept_materials where concept_content_id=?",Integer.class,concept)).isEqualTo(1);
        }
    }
    @Test void sec10RejectedReplacementPreservesBytesIdMetadataAndCount() throws Exception {
        long id=material(upload(concept,"pdf","application/pdf",FileFixtures.pdf(),teacherToken));
        byte[] before=stored(id);var metadata=host.sql().queryForMap("select id,title,file_size,content_type,original_file_name from concept_materials where id=?",id);
        for(byte[] bad:List.of("Texto ficticio SEC-10".getBytes(),new byte[0],Arrays.copyOf(FileFixtures.pdf(),30),new byte[10*1024*1024+1])) {
            assertThat(upload(concept,"pdf","application/pdf",bad,teacherToken).statusCode()).isIn(400,413);
            assertThat(stored(id)).isEqualTo(before);
            assertThat(host.sql().queryForMap("select id,title,file_size,content_type,original_file_name from concept_materials where id=?",id)).isEqualTo(metadata);
            assertThat(host.sql().queryForObject("select count(*) from concept_materials where concept_content_id=?",Integer.class,concept)).isEqualTo(1);
        }
        headers(get(path(concept,id),studentToken),"application/pdf",false);
        // A failure later in the surrounding transaction also rolls replacement back.
        assertThatThrownBy(() -> new TransactionTemplate(host.app.getBean(PlatformTransactionManager.class)).execute(status -> {
            host.app.getBean(ConceptMaterialService.class).addFileMaterial(host.teacher.getUsername(),concept,
                    new MockMultipartFile("file","valid.png","image/png",FileFixtures.png()),null);
            throw new IllegalStateException("Fictitious rollback");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(stored(id)).isEqualTo(before);
        assertThat(host.sql().queryForObject("select count(*) from concept_materials where concept_content_id=?",Integer.class,concept)).isEqualTo(1);
    }
    @Test void ownershipRolesCrossIdsPublicationAndAssignmentAreEnforced() throws Exception {
        long id=material(upload(concept,"png","image/png",FileFixtures.png(),teacherToken));
        assertThat(get(path(concept,id),null).statusCode()).isEqualTo(401);
        assertThat(get(path(concept,id),foreignToken).statusCode()).isIn(400,403);
        assertThat(get(path(concept,id),outsiderToken).statusCode()).isEqualTo(404);
        assertThat(get(path(foreignConcept,id),adminToken).statusCode()).isEqualTo(404);
        for(String token:List.of(studentToken,adminToken,foreignToken))
            assertThat(upload(concept,"png","image/png",FileFixtures.png(),token).statusCode()).isIn(400,403);
        host.sql().update("update concept_contents set status='DRAFT' where id=?",concept);
        assertThat(get(path(concept,id),studentToken).statusCode()).isEqualTo(404);
        headers(get(path(concept,id),teacherToken),"image/png",true);
        host.sql().update("update concept_contents set status='PUBLISHED' where id=?",concept);
        host.sql().update("update concept_assignments set active=false where concept_content_id=?",concept);
        assertThat(get(path(concept,id),studentToken).statusCode()).isEqualTo(404);
    }
    @Test void historicalBytesAreRevalidatedWithoutWritesAndNamesTypesAreDerived() throws Exception {
        long id=material(upload(concept,"png","image/png",FileFixtures.png(),teacherToken));
        byte[] historical=FileFixtures.png();
        host.sql().update("update concept_materials set content_type='text/html',original_file_name=?,file_size=1,file_data=? where id=?",
                "../histórico.html\r\n\u0001",historical,id);
        headers(get(path(concept,id),studentToken),"image/png",true);
        assertThat(get(path(concept,id),studentToken).headers().firstValue("Content-Disposition").orElseThrow()).contains(".png").doesNotContain(".html");
        assertThat(stored(id)).isEqualTo(historical);
        assertThat(host.sql().queryForObject("select content_type from concept_materials where id=?",String.class,id)).isEqualTo("text/html");
        host.sql().update("update concept_materials set file_data=? where id=?","Texto ficticio histórico".getBytes(),id);
        assertThat(get(path(concept,id),teacherToken).statusCode()).isEqualTo(400);
        assertThat(host.sql().queryForObject("select count(*) from concept_materials where id=?",Integer.class,id)).isEqualTo(1);
    }
    @Test void invalidCaptureDoesNotCloseOrPublishAndValidCaptureKeepsT03Permissions() throws Exception {
        String close="/api/whiteboards/teacher/"+host.board+"/close";
        assertThat(multipart(close,"snapshot","capture.png","image/png","Texto ficticio".getBytes(),teacherToken).statusCode()).isEqualTo(400);
        assertThat(host.sql().queryForObject("select status from whiteboard_sessions where id=?",String.class,host.board)).isEqualTo("ACTIVE");
        assertThat(host.sql().queryForObject("select final_snapshot_data from whiteboard_sessions where id=?",byte[].class,host.board)).isNull();
        assertThat(multipart(close,"snapshot","capture.png","image/png",FileFixtures.png(),foreignToken).statusCode()).isIn(400,403);
        assertThat(multipart(close,"snapshot","capture.png","image/png",FileFixtures.png(),teacherToken).statusCode()).isEqualTo(200);
        headers(get("/api/whiteboards/teacher/"+host.board+"/snapshot",teacherToken),"image/png",true);
        headers(get("/api/whiteboards/student/"+host.board+"/snapshot",studentToken),"image/png",true);
        assertThat(get("/api/whiteboards/student/"+host.board+"/snapshot",outsiderToken).statusCode()).isEqualTo(404);
        assertThat(get("/api/whiteboards/teacher/"+host.board+"/snapshot",adminToken).statusCode()).isEqualTo(403);
        host.sql().update("update whiteboard_sessions set final_snapshot_data=? where id=?","Texto histórico".getBytes(),host.board);
        assertThat(get("/api/whiteboards/student/"+host.board+"/snapshot",studentToken).statusCode()).isEqualTo(400);
    }
    @Test void inactiveRevokedAndTemporarySessionsCannotUploadDownloadOrReadCaptures() throws Exception {
        long id=material(upload(concept,"pdf","application/pdf",FileFixtures.pdf(),teacherToken));
        String[] routes={path(concept,id),"/api/whiteboards/teacher/"+host.board+"/snapshot"};
        host.sql().update("update user_accounts set active=false where id=?",host.teacher.getId());
        for(String route:routes)assertThat(get(route,teacherToken).statusCode()).isEqualTo(401);
        assertThat(upload(concept,"pdf","application/pdf",FileFixtures.pdf(),teacherToken).statusCode()).isEqualTo(401);
        host.sql().update("update user_accounts set active=true where id=?",host.teacher.getId());
        teacherToken=host.login(host.teacher);
        assertThat(host.request("POST","/api/auth/logout",teacherToken,"{}").statusCode()).isEqualTo(204);
        for(String route:routes)assertThat(get(route,teacherToken).statusCode()).isEqualTo(401);
        assertThat(upload(concept,"pdf","application/pdf",FileFixtures.pdf(),teacherToken).statusCode()).isEqualTo(401);
        host.sql().update("update user_accounts set temporary_password=true where id=?",host.teacher.getId());
        teacherToken=host.login(host.teacher);
        for(String route:routes)assertThat(get(route,teacherToken).statusCode()).isEqualTo(403);
        assertThat(upload(concept,"pdf","application/pdf",FileFixtures.pdf(),teacherToken).statusCode()).isEqualTo(403);
        assertThat(stored(id)).isNotEmpty();
    }
}
