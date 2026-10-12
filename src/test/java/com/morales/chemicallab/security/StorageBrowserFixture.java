package com.morales.chemicallab.security;
import com.morales.chemicallab.entity.*;
import com.morales.chemicallab.repository.*;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;
import java.nio.file.*;
import java.time.*;
import java.util.Map;

/** Opt-in Selenium fixture. The normal *Test suite never starts this waiting process. */
class StorageBrowserFixture {
    @Test void serveDisposableBrowserFixture() throws Exception {
        Path info=Path.of(System.getProperty("t07b.fixture-file")),stop=Path.of(System.getProperty("t07b.stop-file"));
        var f=new WhiteboardSecurityTransportDbTest(){
            @Override ConfigurableApplicationContext startApp(String... extra){return super.startApp("--server.port=18087");}
        };
        try {
            f.start();f.fixture();var admin=f.account(Role.ADMINISTRADOR,null,"3","A");
            var teacher=f.app.getBean(TeacherProfileRepository.class).findByUser(f.teacher).orElseThrow();
            f.sql().update("insert into user_accounts(username,password,role,active,temporary_password,credentials_version,created_at) select 'browser'||g,'synthetic','ESTUDIANTE',true,false,0,'2026-07-01' from generate_series(1,55) g");
            f.sql().update("insert into student_profiles(user_id,teacher_id,student_code,names,last_names,grade,section) select id,?,username,'Synthetic','Browser','3','A' from user_accounts where username like 'browser%'",teacher.getId());
            f.sql().update("insert into usage_events(user_id,username,user_role,module,event_type,occurred_at) select id,username,role,'COMPOUNDS','MODULE_ACCESS','2026-07-01' from user_accounts where username like 'browser%'");
            for(int i=0;i<55;i++)f.app.getBean(WhiteboardSessionRepository.class).saveAndFlush(WhiteboardSession.builder().name("Synthetic browser "+i).teacher(teacher).grade("3").section("A").build());
            f.sql().update("update storage_limits set maximum=56 where resource='BOARD_COUNT'");
            f.sql().update("update storage_limits set maximum=1 where resource='OWNERSHIP_COUNT'");
            Files.writeString(info,f.json.writeValueAsString(Map.of("boardId",f.board,"admin",admin.getUsername(),"teacher",f.teacher.getUsername(),"student",f.student.getUsername(),"password",WhiteboardSecurityTransportDbTest.PASSWORD)));
            Instant deadline=Instant.now().plus(Duration.ofMinutes(30));
            while(!Files.exists(stop)&&Instant.now().isBefore(deadline))Thread.sleep(250);
        }finally{f.cleanup();}
    }
}
