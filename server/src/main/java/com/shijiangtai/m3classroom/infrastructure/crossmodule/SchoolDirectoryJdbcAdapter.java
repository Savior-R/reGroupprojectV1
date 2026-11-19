package com.shijiangtai.m3classroom.infrastructure.crossmodule;

import com.shijiangtai.m3classroom.application.port.SchoolDirectoryPort;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Reads the owning student's school from M1. See {@link SchoolDirectoryPort}. */
@Component
public class SchoolDirectoryJdbcAdapter implements SchoolDirectoryPort {

    private final JdbcTemplate jdbcTemplate;

    public SchoolDirectoryJdbcAdapter(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Optional<UUID> findSchoolByAccount(UUID accountId) {
        List<UUID> rows = jdbcTemplate.query(
                "SELECT school_id FROM m1_auth.accounts WHERE id = ?",
                (rs, rowNum) -> rs.getObject("school_id", UUID.class),
                accountId);
        return rows.stream().filter(java.util.Objects::nonNull).findFirst();
    }
}
