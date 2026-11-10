package com.shijiangtai.m5question.infrastructure.seed;

import com.shijiangtai.m5question.domain.SeedCatalog;
import com.shijiangtai.shared.domain.DomainException;
import com.shijiangtai.shared.domain.ErrorCode;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** Loads {@code classpath:seed/question-bank.json} (04 §4.1). */
@Component
public class SeedCatalogLoader {
    private static final Logger log = LoggerFactory.getLogger(SeedCatalogLoader.class);
    private static final String SEED_PATH = "seed/question-bank.json";

    private final ObjectMapper objectMapper;
    private volatile SeedCatalog cached;

    public SeedCatalogLoader(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public SeedCatalog load() {
        SeedCatalog result = cached;
        if (result != null) {
            return result;
        }
        synchronized (this) {
            if (cached != null) {
                return cached;
            }
            cached = read();
            return cached;
        }
    }

    private SeedCatalog read() {
        ClassPathResource resource = new ClassPathResource(SEED_PATH);
        if (!resource.exists()) {
            log.error("Seed question bank missing: {}", SEED_PATH);
            throw new DomainException(ErrorCode.SERVICE_UNAVAILABLE, "种子题库不可用");
        }
        try (InputStream input = resource.getInputStream()) {
            SeedCatalog catalog = objectMapper.readValue(input, SeedCatalog.class);
            if (catalog.seedVersion() == null || catalog.seedVersion().isBlank()) {
                throw new DomainException(ErrorCode.SERVICE_UNAVAILABLE, "种子题库版本缺失");
            }
            log.info("Loaded seed question bank version {} ({} categories, {} questions)",
                    catalog.seedVersion(), catalog.categories().size(), catalog.questions().size());
            return catalog;
        } catch (DomainException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            log.error("Failed to load seed question bank", exception);
            throw new DomainException(ErrorCode.SERVICE_UNAVAILABLE,
                    "种子题库不可用", Map.of("cause", exception.getClass().getSimpleName()));
        }
    }
}
