package com.morales.chemicallab.security.files;

import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/** Untrusted parsers run outside the API heap, with no waiting queue and a killable deadline. */
@Service
public class FileValidationService {
    public static final int MATERIAL_BYTES = 10 * 1024 * 1024;
    public static final int SNAPSHOT_BYTES = 5 * 1024 * 1024;
    private static final Semaphore SLOTS = new Semaphore(2);
    private static final Map<String, String> TYPES = Map.of(
            "pdf", "application/pdf", "ppt", "application/vnd.ms-powerpoint",
            "pptx", "application/vnd.openxmlformats-officedocument.presentationml.presentation",
            "png", "image/png", "jpg", "image/jpeg", "jpeg", "image/jpeg");

    public ValidatedFile material(MultipartFile file) { return upload(file, MATERIAL_BYTES, false); }
    public ValidatedFile snapshot(MultipartFile file) { return upload(file, SNAPSHOT_BYTES, true); }

    private ValidatedFile upload(MultipartFile file, int limit, boolean imageOnly) {
        if (file == null || file.isEmpty()) throw new IllegalArgumentException(imageOnly
                ? "Debes adjuntar la captura final de la pizarra (PNG o JPG)." : "Debes adjuntar un archivo.");
        checkLength(file.getSize(), limit);
        String name = safeName(file.getOriginalFilename());
        String type = TYPES.get(extension(name));
        String declared = file.getContentType() == null ? "" : file.getContentType().trim().toLowerCase(Locale.ROOT);
        if (!TYPES.containsValue(declared) || (imageOnly && !declared.startsWith("image/")))
            throw new IllegalArgumentException(imageOnly ? "La captura final debe ser PNG o JPG."
                    : "Tipo de archivo no permitido. Usa PDF, PPT/PPTX o PNG/JPG.");
        if (type == null || !type.equals(declared))
            throw new IllegalArgumentException("La extensión del archivo no coincide con el tipo declarado.");
        try (InputStream input = file.getInputStream()) {
            // Actual bounded read; MultipartFile's client/provider length is not authority.
            byte[] bytes = input.readNBytes(limit + 1);
            checkLength(bytes.length, limit);
            return validate(bytes, type, name, limit);
        } catch (IOException ex) { throw invalid(); }
    }

    /** Historical metadata is only a candidate. Every download revalidates the actual bytes. */
    public ValidatedFile historical(byte[] bytes, String type, String name, boolean imageOnly) {
        int limit = imageOnly ? SNAPSHOT_BYTES : MATERIAL_BYTES;
        if (bytes == null) throw invalid();
        checkLength(bytes.length, limit);
        // Infer the candidate from bytes in the worker, independently of stale metadata/name.
        return validate(bytes, imageOnly ? "image" : "auto", safeName(name), limit);
    }

    private ValidatedFile validate(byte[] bytes, String candidate, String name, int limit) {
        if (!SLOTS.tryAcquire()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "La validación de archivos está ocupada. Intenta nuevamente.");
        Path directory = null;
        Process child = null;
        try {
            directory = Files.createTempDirectory("chemicallab-validation-");
            Path input = directory.resolve("input");
            Path output = directory.resolve("output");
            Files.write(input, bytes);
            List<String> command = workerCommand();
            command.addAll(List.of(input.toString(), output.toString(), candidate, Integer.toString(limit)));
            ProcessBuilder builder = new ProcessBuilder(command)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD);
            // Do not pass credentials, application configuration or Java option injection to the worker.
            builder.environment().keySet().removeIf(key -> !Set.of("SYSTEMROOT", "WINDIR", "TEMP", "TMP", "PATH")
                    .contains(key.toUpperCase(Locale.ROOT)));
            child = builder.start();
            if (!child.waitFor(15, TimeUnit.SECONDS) || child.exitValue() != 0) throw invalid();
            long size = Files.size(output);
            if (size > limit + 256L || size < 5) throw invalid();
            try (DataInputStream result = new DataInputStream(Files.newInputStream(output))) {
                String detected = result.readUTF();
                if (!TYPES.containsValue(detected)) throw invalid();
                byte[] normalized = result.readNBytes(limit + 1);
                checkLength(normalized.length, limit);
                String ext = switch (detected) {
                    case "image/png" -> "png";
                    case "image/jpeg" -> "jpg";
                    case "application/pdf" -> "pdf";
                    case "application/vnd.ms-powerpoint" -> "ppt";
                    default -> "pptx";
                };
                int dot = name.lastIndexOf('.');
                String stem = dot > 0 ? name.substring(0, dot) : name;
                return new ValidatedFile(normalized, detected, stem + "." + ext);
            }
        } catch (IOException ex) { throw invalid(); }
        catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw invalid(); }
        finally {
            if (child != null && child.isAlive()) {
                child.destroyForcibly();
                try { child.waitFor(2, TimeUnit.SECONDS); } catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
            }
            if (directory != null) {
                // Only our three exact generated paths; archive contents are never extracted.
                for (Path path : List.of(directory.resolve("input"), directory.resolve("output"), directory)) {
                    try { Files.deleteIfExists(path); } catch (IOException ex) { path.toFile().deleteOnExit(); }
                }
            }
            SLOTS.release();
        }
    }

    List<String> workerCommand() throws IOException {
        String javaExecutable = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString();
        var command = new ArrayList<>(List.of(javaExecutable, "-Xmx192m", "-XX:MaxMetaspaceSize=96m", "-Xss512k", "-Djava.awt.headless=true"));
        File source = new org.springframework.boot.system.ApplicationHome(FileValidationService.class).getSource();
        if (source != null && source.isFile() && source.getName().endsWith(".jar")) {
            command.addAll(List.of("-jar", source.getAbsolutePath(), "--file-validation-worker"));
        } else {
            command.addAll(List.of("-cp", System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
                    FileValidationWorker.class.getName()));
        }
        return command;
    }

    static void checkLength(long length, int limit) {
        if (length <= 0) throw invalid();
        if (length > limit) throw new IllegalArgumentException("El archivo supera el tamaño máximo permitido ("
                + limit / (1024 * 1024) + " MB).");
    }
    public static String safeName(String raw) {
        if (raw == null || raw.isBlank()) return "archivo";
        String name = raw.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1).replace("..", "")
                .replaceAll("[^\\p{L}\\p{N} ._()-]", "_").trim();
        if (name.isBlank()) return "archivo";
        return name.length() > 180 ? name.substring(name.length() - 180) : name;
    }
    private static String extension(String name) { return name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT); }
    static IllegalArgumentException invalid() {
        return new IllegalArgumentException("Archivo inválido o fuera de la política de seguridad. Usa PDF/PPT/PPTX sin cifrado, contenido activo ni objetos incrustados, o PNG/JPG válidos dentro de los límites.");
    }
}
