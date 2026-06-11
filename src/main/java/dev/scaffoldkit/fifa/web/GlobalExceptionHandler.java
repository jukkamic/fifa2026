package dev.scaffoldkit.fifa.web;

import dev.scaffoldkit.fifa.service.AppEventService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Global exception handler that catches unhandled exceptions from REST
 * controllers, records them as user-visible events via
 * {@link AppEventService}, and returns a clean JSON error response
 * (no stack traces).
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private final AppEventService appEvents;

    public GlobalExceptionHandler(AppEventService appEvents) {
        this.appEvents = appEvents;
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleException(Exception e) {
        log.error("Unhandled exception in REST controller", e);

        String shortMessage = shortenMessage(e.getMessage());

        appEvents.emitError("System",
                "Unexpected error: " + shortMessage);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", false);
        body.put("error", shortMessage);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleBadRequest(IllegalArgumentException e) {
        log.warn("Bad request: {}", e.getMessage());

        String shortMessage = shortenMessage(e.getMessage());

        appEvents.emitWarning("System", "Invalid request: " + shortMessage);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", false);
        body.put("error", shortMessage);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    private static String shortenMessage(String msg) {
        if (msg == null) return "Unknown error";
        int newline = msg.indexOf('\n');
        String firstLine = newline > 0 ? msg.substring(0, newline).trim() : msg;
        if (firstLine.length() > 150) {
            return firstLine.substring(0, 147) + "...";
        }
        return firstLine;
    }
}