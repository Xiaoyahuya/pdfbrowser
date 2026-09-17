package dev.pdfbrowser.model;

import java.time.Instant;

public record EmailVerificationCode(
    long id,
    String email,
    String purpose,
    String codeHash,
    Instant expiresAt,
    Instant consumedAt,
    int attemptCount,
    Instant createAt
) {
    
}
