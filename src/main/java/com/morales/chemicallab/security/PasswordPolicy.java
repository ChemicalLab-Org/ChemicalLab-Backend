package com.morales.chemicallab.security;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.*;

/** One policy for all new secrets. Never used to reject an existing login password. */
public final class PasswordPolicy {
    public static final int MIN = 15, MAX = 128, MAX_BYTES = 512;
    public static final String MESSAGE = "Usa entre 15 y 128 caracteres Unicode (máximo 512 bytes UTF-8). Se permiten espacios; evita contraseñas comunes o repetidas.";
    private static final Set<String> COMMON = load();
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789";
    private PasswordPolicy() { }

    public static boolean valid(String value) {
        if (value == null || !wellFormed(value) || value.isBlank()) return false;
        int length = value.codePointCount(0, value.length());
        if (length < MIN || length > MAX || value.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) return false;
        String comparison = value.strip().toLowerCase(Locale.ROOT);
        if (COMMON.contains(comparison)) return false;
        // Short repeating patterns and padded/case variants are common guesses too.
        int[] points = comparison.codePoints().toArray();
        for (int period = 1; period <= 4 && period < points.length; period++) {
            boolean repeated = true;
            for (int i = period; i < points.length; i++) repeated &= points[i] == points[i % period];
            if (repeated) return false;
        }
        return true;
    }

    public static void require(String value) {
        if (!valid(value)) throw new IllegalArgumentException(MESSAGE);
    }

    public static boolean wellFormed(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (++i == value.length() || !Character.isLowSurrogate(value.charAt(i))) return false;
            } else if (Character.isLowSurrogate(c)) return false;
        }
        return true;
    }

    public static String temporary() {
        String value;
        do {
            var result = new StringBuilder(20);
            for (int i = 0; i < 20; i++) result.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
            value = result.toString();
        } while (!valid(value));
        return value;
    }

    private static Set<String> load() {
        try (var input = PasswordPolicy.class.getResourceAsStream("/security/common-passwords.txt")) {
            if (input == null) throw new IllegalStateException("Missing local password blocklist");
            var result = new HashSet<String>();
            new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8)).lines()
                    .filter(line -> !line.isBlank() && !line.startsWith("#"))
                    .forEach(line -> result.add(line.strip().toLowerCase(Locale.ROOT)));
            return Set.copyOf(result);
        } catch (IOException ex) { throw new IllegalStateException("Cannot load local password blocklist"); }
    }
}
