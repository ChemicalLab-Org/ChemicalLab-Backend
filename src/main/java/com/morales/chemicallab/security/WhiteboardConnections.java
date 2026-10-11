package com.morales.chemicallab.security;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.*;
import org.springframework.web.socket.handler.WebSocketHandlerDecorator;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Transport registry only: authorization data is never cached here as a permission decision. */
@Component
@RequiredArgsConstructor
public class WhiteboardConnections {
    public static final long VALIDATION_DEADLINE_MS = 5000;
    private final AccountSessionService sessions;
    private final WhiteboardRealtimeAccess access;
    private final Map<String, Connection> connections = new ConcurrentHashMap<>();
    private final ScheduledExecutorService watchdog = Executors.newSingleThreadScheduledExecutor(r -> daemon(r, "whiteboard-watchdog"));
    private final ThreadPoolExecutor validators = new ThreadPoolExecutor(4, 4, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(256), r -> daemon(r, "whiteboard-validation"));

    private static Thread daemon(Runnable task, String name) { var t = new Thread(task, name); t.setDaemon(true); return t; }

    static final class Connection {
        final WebSocketSession socket;
        final Map<String, Long> subscriptions = new ConcurrentHashMap<>(); // 0 = own error queue
        final Set<Long> targets = ConcurrentHashMap.newKeySet();
        final AtomicBoolean validating = new AtomicBoolean();
        volatile SessionPrincipal principal;
        volatile long validatedAt = System.nanoTime();
        volatile long attemptedAt;
        volatile Future<?> validation;
        Connection(WebSocketSession socket) { this.socket = socket; }
    }

    @PostConstruct void start() { watchdog.scheduleAtFixedRate(this::sweep, 250, 250, TimeUnit.MILLISECONDS); }
    @PreDestroy void stop() {
        watchdog.shutdownNow();
        List.copyOf(connections.keySet()).forEach(id -> close(id, 4503, "VALIDATION_UNAVAILABLE"));
        validators.shutdownNow();
    }

    public WebSocketHandler decorate(WebSocketHandler delegate) {
        return new WebSocketHandlerDecorator(delegate) {
            @Override public void afterConnectionEstablished(WebSocketSession socket) throws Exception {
                connections.put(socket.getId(), new Connection(socket));
                try { super.afterConnectionEstablished(socket); }
                catch (Exception ex) { remove(socket.getId()); throw ex; }
            }
            @Override public void afterConnectionClosed(WebSocketSession socket, CloseStatus status) throws Exception {
                remove(socket.getId());
                super.afterConnectionClosed(socket, status);
            }
        };
    }

    public void authenticated(String id, SessionPrincipal principal) {
        Connection connection = require(id);
        if (connection.principal != null) throw new AccessDeniedException("PROTOCOL_REJECTED");
        connection.principal = principal;
        connection.validatedAt = System.nanoTime();
    }

    public SessionPrincipal validate(String id) {
        Connection connection = require(id);
        if (connection.principal == null) throw new AccessDeniedException("CONNECT_REQUIRED");
        sessions.revalidate(connection.principal);
        return connection.principal;
    }

    public void observe(String id, long board) {
        var principal = validate(id);
        access.observe(principal, board);
        require(id).targets.add(board);
    }

    public void subscribe(String id, String subscription, long board) {
        if (subscription == null || subscription.isBlank() || require(id).subscriptions.putIfAbsent(subscription, board) != null)
            throw new AccessDeniedException("PROTOCOL_REJECTED");
    }
    public void unsubscribe(String id, String subscription) {
        if (subscription == null || require(id).subscriptions.remove(subscription) == null)
            throw new AccessDeniedException("PROTOCOL_REJECTED");
    }
    public void delivery(String id, String subscription, String destination) {
        SessionPrincipal principal = validate(id);
        Long board = subscription == null ? null : require(id).subscriptions.get(subscription);
        if (board == null) throw new AccessDeniedException("PROTOCOL_REJECTED");
        if (board > 0) {
            if (!("/topic/whiteboards/" + board).equals(destination)) throw new AccessDeniedException("PROTOCOL_REJECTED");
            access.observe(principal, board);
        } else if (destination == null || !destination.startsWith("/queue/whiteboard-errors-user")) {
            throw new AccessDeniedException("PROTOCOL_REJECTED");
        }
        // A watchdog close may have happened while a database read was waiting.
        require(id);
    }

    public void reject(String id, Exception error) {
        if (error instanceof AuthenticationException) close(id, 4001, "SESSION_INVALID");
        else if (error instanceof AccessDeniedException) close(id, 4003,
                "BOARD_ACCESS_LOST".equals(error.getMessage()) ? "BOARD_ACCESS_LOST" : "PROTOCOL_REJECTED");
        else close(id, 4503, "VALIDATION_UNAVAILABLE");
    }
    public void close(String id, int code, String reason) {
        if (id == null) return;
        Connection connection = connections.remove(id); // blocks traffic before transport close
        if (connection == null) return;
        cancel(connection);
        try { connection.socket.close(new CloseStatus(code, reason)); }
        catch (Exception ignored) { /* registry is already closed; never restore access */ }
    }
    public void remove(String id) {
        if (id == null) return;
        Connection connection = connections.remove(id);
        if (connection != null) cancel(connection);
    }
    private void cancel(Connection connection) {
        connection.subscriptions.clear(); connection.targets.clear();
        // A validator may close its own connection. Interrupting it can abort the SockJS close frame.
        if (connection.validation != null) connection.validation.cancel(false);
        validators.purge();
    }
    private Connection require(String id) {
        Connection connection = id == null ? null : connections.get(id);
        if (connection == null) throw new AccessDeniedException("CONNECT_REQUIRED");
        if (System.nanoTime() - connection.validatedAt > TimeUnit.MILLISECONDS.toNanos(VALIDATION_DEADLINE_MS)) {
            close(id, 4503, "VALIDATION_UNAVAILABLE");
            throw new AccessDeniedException("VALIDATION_UNAVAILABLE");
        }
        return connection;
    }
    private void sweep() {
        long now = System.nanoTime();
        connections.forEach((id, connection) -> {
            if (now - connection.validatedAt > TimeUnit.MILLISECONDS.toNanos(VALIDATION_DEADLINE_MS)) {
                close(id, 4503, "VALIDATION_UNAVAILABLE"); return;
            }
            if (connection.principal == null || now - connection.attemptedAt < TimeUnit.SECONDS.toNanos(1)
                    || !connection.validating.compareAndSet(false, true)) return;
            connection.attemptedAt = now;
            try {
                connection.validation = validators.submit(() -> {
                    long started = System.nanoTime();
                    try {
                        sessions.revalidate(connection.principal);
                        for (long board : connection.targets) access.observe(connection.principal, board);
                        connection.validatedAt = started;
                    } catch (Exception ex) { reject(id, ex); }
                    finally { connection.validating.set(false); }
                });
            } catch (RejectedExecutionException ex) { close(id, 4503, "VALIDATION_UNAVAILABLE"); }
        });
    }
    public int size() { return connections.size(); }
}
