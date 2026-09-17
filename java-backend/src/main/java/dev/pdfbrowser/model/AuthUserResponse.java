package dev.pdfbrowser.model;

public record AuthUserResponse(
    long id,
    String username,
    String email,
    String status
) {
}
