package com.shijiangtai.m3classroom.persistence;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;

@SpringBootTest
@ActiveProfiles("test")
@Sql(scripts = {"/db/test-upstream.sql", "/db/test-clean.sql"},
        executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
public abstract class PostgresIntegrationTest {
}
