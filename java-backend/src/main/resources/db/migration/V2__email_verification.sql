CREATE TABLE
    email_verification_code (
        id BIGSERIAL PRIMARY KEY,
        email VARCHAR(320) NOT NULL,
        purpose VARCHAR(32) NOT NULL,
        code_hash VARCHAR(100) NOT NULL,
        expires_at TIMESTAMPTZ NOT NULL,
        consumed_at TIMESTAMPTZ,
        attempt_count INTEGER NOT NULL DEFAULT 0,
        created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
        CONSTRAINT ck_email_verification_attempt_count CHECK (attempt_count >= 0)
    );

CREATE INDEX idx_evc_email_purpose ON email_verification_code (email, purpose);

CREATE INDEX idx_evc_active ON email_verification_code (email, purpose, expires_at)
WHERE
    consumed_at IS NULL;

CREATE INDEX idx_evc_email_created_at ON email_verification_code (email, created_at);