package com.shijiangtai.m3classroom.infrastructure.material;

import com.shijiangtai.m3classroom.application.port.MaterialExtractionError;
import com.shijiangtai.m3classroom.application.port.MaterialExtractionException;
import com.shijiangtai.m3classroom.application.port.MaterialTextExtractor;
import java.util.List;

public class CompositeMaterialTextExtractor implements MaterialTextExtractor {
    private final List<MaterialTextExtractor> extractors;

    public CompositeMaterialTextExtractor(List<MaterialTextExtractor> extractors) {
        this.extractors = List.copyOf(extractors);
    }

    @Override
    public boolean supports(String filename) {
        return extractors.stream().anyMatch(extractor -> extractor.supports(filename));
    }

    @Override
    public String extract(byte[] content, String filename, String mime) {
        for (MaterialTextExtractor extractor : extractors) {
            if (extractor.supports(filename)) {
                return extractor.extract(content, filename, mime);
            }
        }
        throw new MaterialExtractionException(
                MaterialExtractionError.UNSUPPORTED_TYPE, "不支持的材料格式");
    }
}
