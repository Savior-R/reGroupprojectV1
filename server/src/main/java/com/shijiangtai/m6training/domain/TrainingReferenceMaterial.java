package com.shijiangtai.m6training.domain;

/** Full reference material (including parsed text) inside snapshots (08 §3.2). */
public record TrainingReferenceMaterial(
        String filename,
        String objectKey,
        String mime,
        long size,
        String parsedText,
        int charCount) {

    public TrainingReferenceMaterial {
        if (filename == null || filename.isBlank()) {
            throw new TrainingException(TrainingError.VALIDATION_ERROR,
                    "参考材料文件名缺失", java.util.Map.of("field", "reference_material"));
        }
        if (objectKey == null || objectKey.isBlank()) {
            throw new TrainingException(TrainingError.VALIDATION_ERROR,
                    "参考材料对象键缺失", java.util.Map.of("field", "reference_material"));
        }
        if (size <= 0) {
            throw new TrainingException(TrainingError.VALIDATION_ERROR,
                    "参考材料大小非法", java.util.Map.of("field", "reference_material"));
        }
        if (parsedText == null || parsedText.isBlank()) {
            throw new TrainingException(TrainingError.TRAINING_SOURCE_INVALID,
                    "参考材料解析文本缺失", java.util.Map.of("field", "reference_material"));
        }
        filename = filename.trim();
        objectKey = objectKey.trim();
        charCount = parsedText.length();
    }
}
