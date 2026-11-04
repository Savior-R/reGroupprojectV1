package com.shijiangtai.m3classroom.infrastructure.material;

import com.shijiangtai.m3classroom.application.port.MaterialExtractionError;
import com.shijiangtai.m3classroom.application.port.MaterialExtractionException;
import com.shijiangtai.m3classroom.application.port.MaterialTextExtractor;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

public class PlainTextMaterialTextExtractor implements MaterialTextExtractor {
    private static final Charset GB18030 = Charset.forName("GB18030");

    @Override
    public boolean supports(String filename) {
        if (filename == null) {
            return false;
        }
        String lower = filename.toLowerCase();
        return lower.endsWith(".txt") || lower.endsWith(".md");
    }

    @Override
    public String extract(byte[] content, String filename, String mime) {
        String text;
        try {
            text = decode(content, StandardCharsets.UTF_8);
        } catch (CharacterCodingException utf8Failure) {
            try {
                text = decode(content, GB18030);
            } catch (CharacterCodingException fallbackFailure) {
                throw new MaterialExtractionException(
                        MaterialExtractionError.CORRUPT, "文本编码无法识别", fallbackFailure);
            }
        }
        if (text.startsWith("﻿")) {
            text = text.substring(1);
        }
        if (text.isBlank()) {
            throw new MaterialExtractionException(
                    MaterialExtractionError.NO_TEXT, "文本材料为空");
        }
        return text;
    }

    private String decode(byte[] content, Charset charset) throws CharacterCodingException {
        return charset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(content))
                .toString();
    }
}
