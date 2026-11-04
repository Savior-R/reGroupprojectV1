package com.shijiangtai.m3classroom.domain.task;

import com.shijiangtai.shared.domain.DomainException;
import com.shijiangtai.shared.domain.ErrorCode;
import java.util.Map;

public record ScenarioConfig(String role, String situation) {

    public ScenarioConfig {
        role = normalize(role, 200, "scenario.role");
        situation = normalize(situation, 1000, "scenario.situation");
    }

    private static String normalize(String value, int max, String field) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        if (trimmed.length() > max) {
            throw new DomainException(ErrorCode.TASK_CONFIG_INVALID,
                    "情景配置非法", Map.of("field", field));
        }
        return trimmed;
    }
}
