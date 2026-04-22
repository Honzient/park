package com.parking.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration
public class ParkingRecordStatusConstraintConfig {

    private static final Logger log = LoggerFactory.getLogger(ParkingRecordStatusConstraintConfig.class);
    private static final String CLEANUP_SQL = """
            DELETE FROM parking_record
            WHERE status IS NULL
               OR TRIM(status) = ''
               OR status NOT IN (
                    CONVERT(0xE69CAAE587BAE59CBA USING utf8mb4),
                    CONVERT(0xE5B7B2E587BAE59CBA USING utf8mb4)
               )
            """;
    private static final String ADD_CHECK_SQL = """
            ALTER TABLE parking_record
            ADD CONSTRAINT chk_parking_record_status
            CHECK (status IN (
                CONVERT(0xE69CAAE587BAE59CBA USING utf8mb4),
                CONVERT(0xE5B7B2E587BAE59CBA USING utf8mb4)
            ))
            """;

    @Bean
    public ApplicationRunner parkingRecordStatusGuard(JdbcTemplate jdbcTemplate) {
        return args -> {
            cleanupInvalidStatusRecords(jdbcTemplate);
            enforceStatusConstraint(jdbcTemplate);
        };
    }

    private void cleanupInvalidStatusRecords(JdbcTemplate jdbcTemplate) {
        try {
            int deleted = jdbcTemplate.update(CLEANUP_SQL);
            if (deleted > 0) {
                log.info("Deleted {} parking_record rows with invalid vehicle status", deleted);
            }
        } catch (DataAccessException exception) {
            log.warn("Failed to cleanup invalid parking_record status rows: {}", exception.getMessage());
        }
    }

    private void enforceStatusConstraint(JdbcTemplate jdbcTemplate) {
        try {
            jdbcTemplate.execute(ADD_CHECK_SQL);
            log.info("Applied parking_record status check constraint");
        } catch (DataAccessException exception) {
            if (constraintAlreadyExists(exception)) {
                return;
            }
            log.warn("Failed to apply parking_record status constraint: {}", exception.getMessage());
        }
    }

    private boolean constraintAlreadyExists(DataAccessException exception) {
        Throwable cause = exception.getCause();
        String message = cause == null ? exception.getMessage() : cause.getMessage();
        if (message == null) {
            return false;
        }
        String normalized = message.toLowerCase();
        return normalized.contains("already exists")
                || normalized.contains("duplicate")
                || normalized.contains("chk_parking_record_status");
    }
}