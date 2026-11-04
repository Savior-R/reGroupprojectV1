package com.shijiangtai.m3classroom.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class FlywayAutoConfigurationIT {

    @Autowired
    private Flyway flyway;

    @Test
    void flywayIsAutoConfiguredAndMigrationsAreApplied() {
        assertThat(flyway.info().applied()).isNotEmpty();
        assertThat(flyway.info().pending()).isEmpty();
    }
}
