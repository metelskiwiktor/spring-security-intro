package pl.wiktor.springsecurityintro.controller.dto;

public record RegisterRequest(
        String username,
        String password,
        String email
) {
}
