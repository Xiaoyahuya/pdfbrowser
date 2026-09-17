package dev.pdfbrowser.model;

import java.time.Instant;
public record AppUser(
    long id,
    String username,
    String email,
    String passwordHash,
    String status,
    Instant lastLoginAt
) {}
