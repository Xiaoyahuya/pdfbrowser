package dev.pdfbrowser.repository;

import dev.pdfbrowser.model.EmailVerificationCode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

@Repository
public class EmailVerificationCodeRepository {

    private static final RowMapper<EmailVerificationCode> ROW_MAPPER =
            EmailVerificationCodeRepository::mapCode;

    private final JdbcTemplate jdbcTemplate;

    public EmailVerificationCodeRepository(
            JdbcTemplate jdbcTemplate
    ) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public int invalidateActive(
            String email,
            String purpose,
            Instant now
    ) {
        String sql = """
                UPDATE email_verification_code
                SET consumed_at = ?
                WHERE email = ?
                  AND purpose = ?
                  AND consumed_at IS NULL
                """;

        return jdbcTemplate.update(
                sql,
                Timestamp.from(now),
                email,
                purpose
        );
    }

    public Optional<Instant> findLatestCreatedAt(
            String email,
            String purpose
    ) {
        String sql = """
                SELECT created_at
                FROM email_verification_code
                WHERE email = ?
                  AND purpose = ?
                ORDER BY created_at DESC
                LIMIT 1
                """;

        return jdbcTemplate.query(
                        sql,
                        (resultSet, rowNumber) ->
                                resultSet
                                        .getTimestamp("created_at")
                                        .toInstant(),
                        email,
                        purpose
                )
                .stream()
                .findFirst();
    }

    public long countCreatedSince(
            String email,
            String purpose,
            Instant since
    ) {
        String sql = """
                SELECT COUNT(*)
                FROM email_verification_code
                WHERE email = ?
                  AND purpose = ?
                  AND created_at >= ?
                """;

        Long count = jdbcTemplate.queryForObject(
                sql,
                Long.class,
                email,
                purpose,
                Timestamp.from(since)
        );

        return count == null ? 0 : count;
    }

    public long insert(
            String email,
            String purpose,
            String codeHash,
            Instant expiresAt,
            Instant createdAt
    ) {
        String sql = """
                INSERT INTO email_verification_code
                    (email, purpose, code_hash, expires_at, created_at)
                VALUES (?, ?, ?, ?, ?)
                RETURNING id
                """;

        Long id = jdbcTemplate.queryForObject(
                sql,
                Long.class,
                email,
                purpose,
                codeHash,
                Timestamp.from(expiresAt),
                Timestamp.from(createdAt)
        );

        return Objects.requireNonNull(id);
    }

    public Optional<EmailVerificationCode> findActiveForUpdate(
            String email,
            String purpose,
            Instant now,
            int maxAttempts
    ) {
        String sql = """
                SELECT id,
                       email,
                       purpose,
                       code_hash,
                       expires_at,
                       consumed_at,
                       attempt_count,
                       created_at
                FROM email_verification_code
                WHERE email = ?
                  AND purpose = ?
                  AND consumed_at IS NULL
                  AND expires_at > ?
                  AND attempt_count < ?
                ORDER BY created_at DESC
                LIMIT 1
                FOR UPDATE
                """;

        return jdbcTemplate.query(
                        sql,
                        ROW_MAPPER,
                        email,
                        purpose,
                        Timestamp.from(now),
                        maxAttempts
                )
                .stream()
                .findFirst();
    }

    public int incrementAttempt(
            long id,
            Instant now,
            int maxAttempts
    ) {
        String sql = """
                UPDATE email_verification_code
                SET attempt_count = attempt_count + 1
                WHERE id = ?
                  AND consumed_at IS NULL
                  AND expires_at > ?
                  AND attempt_count < ?
                """;

        return jdbcTemplate.update(
                sql,
                id,
                Timestamp.from(now),
                maxAttempts
        );
    }

    public int consume(
            long id,
            Instant now
    ) {
        String sql = """
                UPDATE email_verification_code
                SET consumed_at = ?
                WHERE id = ?
                  AND consumed_at IS NULL
                  AND expires_at > ?
                """;

        return jdbcTemplate.update(
                sql,
                Timestamp.from(now),
                id,
                Timestamp.from(now)
        );
    }

    private static EmailVerificationCode mapCode(
            ResultSet resultSet,
            int rowNumber
    ) throws SQLException {
        return new EmailVerificationCode(
                resultSet.getLong("id"),
                resultSet.getString("email"),
                resultSet.getString("purpose"),
                resultSet.getString("code_hash"),
                resultSet.getTimestamp("expires_at").toInstant(),
                readInstant(resultSet, "consumed_at"),
                resultSet.getInt("attempt_count"),
                resultSet.getTimestamp("created_at").toInstant()
        );
    }

    private static Instant readInstant(
            ResultSet resultSet,
            String column
    ) throws SQLException {
        Timestamp timestamp = resultSet.getTimestamp(column);

        return timestamp == null
                ? null
                : timestamp.toInstant();
    }
}
