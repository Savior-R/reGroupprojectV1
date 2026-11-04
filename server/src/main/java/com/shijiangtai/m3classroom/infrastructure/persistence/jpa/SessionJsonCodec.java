package com.shijiangtai.m3classroom.infrastructure.persistence.jpa;

import com.shijiangtai.m3classroom.domain.recording.StreamType;
import com.shijiangtai.shared.domain.DomainException;
import com.shijiangtai.shared.domain.ErrorCode;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * JSONB codec for session state and event payloads.
 *
 * <p>Uses the injected Jackson 3 {@link ObjectMapper}; the application mapper is configured with
 * the snake_case property naming strategy, so structured values keep the wire contract's
 * snake_case keys.
 */
@Component
public class SessionJsonCodec {
    private final ObjectMapper objectMapper;

    public SessionJsonCodec(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String writeStreamOffsets(Map<StreamType, Long> offsets) {
        LinkedHashMap<String, Long> raw = new LinkedHashMap<>();
        offsets.forEach((type, offset) -> raw.put(type.wireValue(), offset));
        return write(raw);
    }

    public Map<StreamType, Long> readStreamOffsets(String json) {
        Map<String, Object> raw = read(json);
        EnumMap<StreamType, Long> offsets = new EnumMap<>(StreamType.class);
        raw.forEach((key, value) -> offsets.put(
                StreamType.fromWire(key), ((Number) value).longValue()));
        return offsets;
    }

    public String writeEventPayload(Map<String, Object> payload) {
        return write(payload);
    }

    public Map<String, Object> readEventPayload(String json) {
        return read(json);
    }

    private String write(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw codecError(exception);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> read(String json) {
        try {
            Map<String, Object> raw = objectMapper.readValue(json, Map.class);
            return raw == null ? Map.of() : raw;
        } catch (Exception exception) {
            throw codecError(exception);
        }
    }

    private DomainException codecError(Exception exception) {
        return new DomainException(ErrorCode.INTERNAL_ERROR,
                "会话 JSON 编解码失败", Map.of("cause", exception.getClass().getSimpleName()));
    }
}
