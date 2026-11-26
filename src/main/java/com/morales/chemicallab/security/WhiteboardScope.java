package com.morales.chemicallab.security;

import org.springframework.security.access.AccessDeniedException;
import java.util.regex.Pattern;

/** A requested view becomes immutable once CONNECT has been authorized. */
public record WhiteboardScope(long institution, long clazz, boolean teacher) {
    private static final Pattern CONNECT = Pattern.compile("([1-9][0-9]{0,18})/([1-9][0-9]{0,18})/([1-9][0-9]{0,18})/(teacher|student)");

    public record Target(WhiteboardScope scope, long board) {
        public String topic() { return "/topic" + destination(); }
        public String draw() { return "/app" + destination() + "/draw"; }
        public String ready() { return "/app" + destination() + "/ready"; }
        private String destination() {
            return "/institutions/" + scope.institution() + "/classes/" + scope.clazz() + "/whiteboards/v1/" + board;
        }
    }

    static Target parse(String value) {
        var match = CONNECT.matcher(value == null ? "" : value);
        if (!match.matches()) throw new AccessDeniedException("PROTOCOL_REJECTED");
        try {
            return new Target(new WhiteboardScope(Long.parseLong(match.group(1)), Long.parseLong(match.group(2)),
                "teacher".equals(match.group(4))), Long.parseLong(match.group(3)));
        } catch (NumberFormatException ex) {
            throw new AccessDeniedException("PROTOCOL_REJECTED");
        }
    }
}
