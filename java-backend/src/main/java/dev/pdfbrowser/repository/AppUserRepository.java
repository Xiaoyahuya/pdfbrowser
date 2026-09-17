package dev.pdfbrowser.repository;

import dev.pdfbrowser.model.AppUser;
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
public class AppUserRepository {

    private static final RowMapper<AppUser> ROW_MAPPER = 
        AppUserRepository::mapUser;
        
    private final JdbcTemplate jdbcTemplate;

    public AppUserRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public boolean existsByUsernameOrEmail(String username, String email) {
        String sql = """
                SELECT EXISTS (SELECT 1
                                FROM app_user
                                WHERE username = ? OR email = ?
                                )
                """;
        return Boolean.TRUE.equals(
                jdbcTemplate.queryForObject(
                        sql,
                        Boolean.class,
                        username,
                        email));
    }

    public boolean existsByEmail(String email) {
        String sql = """
                SELECT EXISTS(
                SELECT 1
                FROM app_user
                WHERE lower(email) = lower(?)
                )
                """;
        Boolean result = jdbcTemplate.queryForObject(sql, Boolean.class, email);

        return Boolean.TRUE.equals(result);
    }

    public Optional<AppUser> findByUsernameOrEmail(String login) {
        String sql = """
                SELECT id,username, email, password_hash, status, last_login_at
                FROM app_user
                WHERE username = ?
                    OR lower(email) = lower(?)
                LIMIT 1
                """;
        
        return jdbcTemplate.query(sql, ROW_MAPPER, login, login).stream().findFirst();
    }

    public Optional<AppUser> findByUsername(String username) {
        String sql = """
                SELECT id, username, email, password_hash,
                    status, last_login_at
                FROM app_user
                WHERE username = ?
                LIMIT 1
                """;

        return jdbcTemplate.query(sql, ROW_MAPPER, username)
                .stream()
                .findFirst();
    }

    public long insert(
            String username,
            String email,
            String passwordHash
    ) {
        String sql = """
                INSERT INTO app_user (
                    username,
                    email,
                    password_hash,
                    status
                )
                VALUES (?, ?, ?, 'ACTIVE')
                RETURNING id
                """;
    
        Long id = jdbcTemplate.queryForObject(
                sql,
                Long.class,
                username,
                email,
                passwordHash
        );
    
        return Objects.requireNonNull(id);
    }

    public int updateLastLoginAt(
        long userId,
        Instant time
    ) {
        String sql = """
                UPDATE app_user
                SET last_login_at = ?,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = ?
                """;
        return jdbcTemplate.update(
            sql,
            Timestamp.from(time),
            userId
        );
    }

    public int updatePasswordHash(
        long userId,
        String passwordHash
    ) {
        String sql = """
                UPDATE app_user
                SET password_hash = ?,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = ? 
                    AND status = 'ACTIVE'
                """;

        return jdbcTemplate.update(
            sql,
            passwordHash,
            userId
        );
    }

    private static AppUser mapUser(
        ResultSet resultSet,
        int rowNumber
    ) throws SQLException {
        return new AppUser(
            resultSet.getLong("id"),
            resultSet.getString("username"),
            resultSet.getString("email"),
            resultSet.getString("password_hash"),
            resultSet.getString("status"),
            readInstant(resultSet, "last_login_at")
        );
    }

    private static Instant readInstant(
        ResultSet resultSet,
        String column
    ) throws SQLException {
        Timestamp timestamp = resultSet.getTimestamp(column);

        return timestamp == null ? null : timestamp.toInstant();
    }
}
