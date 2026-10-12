package com.morales.chemicallab.security.files;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.server.ResponseStatusException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

/** Exercises a stuck child and admission saturation without invoking an untrusted exploit. */
class FileWorkerLimitsTest {
    public static class SleepingWorker {
        public static void main(String[] args) throws Exception { Thread.sleep(60_000); }
    }
    @Test void deadlineKillsStuckWorkerAndReleasesItsSlot() throws Exception {
        FileValidationService blocked = new FileValidationService() {
            @Override List<String> workerCommand() {
                return new ArrayList<>(List.of(Path.of(System.getProperty("java.home"),"bin","java").toString(),"-cp",
                        System.getProperty("surefire.test.class.path",System.getProperty("java.class.path")),SleepingWorker.class.getName()));
            }
        };
        long started=System.nanoTime();
        assertThatThrownBy(() -> blocked.material(file())).isInstanceOf(IllegalArgumentException.class);
        assertThat(TimeUnit.NANOSECONDS.toSeconds(System.nanoTime()-started)).isBetween(14L,19L);
        assertThat(new FileValidationService().material(file()).contentType()).isEqualTo("image/png");
    }
    @Test void twoBusyWorkersRejectThirdImmediatelyAndInterruptionReleasesSlots() throws Exception {
        var admitted=new CountDownLatch(2); var wait=new CountDownLatch(1);
        FileValidationService blocked = new FileValidationService() {
            @Override List<String> workerCommand() {
                admitted.countDown();
                try { wait.await(); } catch(InterruptedException ex) { Thread.currentThread().interrupt(); }
                return new ArrayList<>(List.of(Path.of(System.getProperty("java.home"),"bin","java").toString(),"-cp",
                        System.getProperty("surefire.test.class.path",System.getProperty("java.class.path")),SleepingWorker.class.getName()));
            }
        };
        var executor=Executors.newFixedThreadPool(2);
        var a=executor.submit(() -> blocked.material(file())); var b=executor.submit(() -> blocked.material(file()));
        try {
            assertThat(admitted.await(3,TimeUnit.SECONDS)).isTrue();
            long start=System.nanoTime();
            assertThatThrownBy(() -> new FileValidationService().material(file())).isInstanceOf(ResponseStatusException.class)
                    .satisfies(e -> assertThat(((ResponseStatusException)e).getStatusCode().value()).isEqualTo(503));
            assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start)).isLessThan(500);
        } finally {
            a.cancel(true); b.cancel(true); wait.countDown(); executor.shutdownNow();
            assertThat(executor.awaitTermination(5,TimeUnit.SECONDS)).isTrue();
        }
        assertThat(new FileValidationService().material(file()).contentType()).isEqualTo("image/png");
    }
    MockMultipartFile file() { return new MockMultipartFile("file","valid.png","image/png",FileFixtures.png()); }
}
