package com.shijiangtai.m3classroom.domain.task;

import com.shijiangtai.shared.domain.DomainException;
import com.shijiangtai.shared.domain.ErrorCode;
import java.util.Map;
import java.util.UUID;

public record ReferenceMaterial(
        UUID materialId,
        String filename,
        String objectKey,
        String mime,
        long size,
        String parsedText,
        int charCount) {

    public ReferenceMaterial {
        if (materialId == null) {
            throw invalid("reference_material.material_id");
        }
        filename = requireText(filename, "reference_material.filename");
        objectKey = requireText(objectKey, "reference_material.object_key");
        mime = requireText(mime, "reference_material.mime");
        if (size <= 0) {
            throw invalid("reference_material.size");
        }
        if (parsedText == null) {
            throw invalid("reference_material.parsed_text");
        }
        if (charCount < 0) {
            throw invalid("reference_material.char_count");
        }
    }

    public String preview() {
        return parsedText.substring(0, Math.min(500, parsedText.length()));
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw invalid(field);
        }
        return value.trim();
    }

    private static DomainException invalid(String field) {
        return new DomainException(ErrorCode.TASK_CONFIG_INVALID,
                "参考材料配置非法", Map.of("field", field));
    }
}
