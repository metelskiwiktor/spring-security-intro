package pl.wiktor.springsecurityintro.exception;

import java.time.LocalDateTime;

public record ErrorResponse(
        int status,
        String message,
        LocalDateTime timestamp,
        String path
) {
    public ErrorResponse(int status, String message, String path) {
        this(status, message, LocalDateTime.now(), path);
    }
}
