package com.shijiangtai.m3classroom.infrastructure.ws;

import com.shijiangtai.shared.domain.DomainException;
import com.shijiangtai.shared.domain.ErrorCode;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Parses and validates the perception-WebSocket client protocol (05A §6.2).
 *
 * <p>Protocol version 1 is a raw message without the control-channel envelope. The only accepted
 * messages are {@code audio.start} with exact PCM parameters and {@code audio.stop}; everything
 * else (malformed JSON, missing/unknown type, non-PCM16 parameters) surfaces as a
 * {@link ErrorCode#VALIDATION_ERROR} {@link DomainException} so the handler can send the protocol
 * error and close 4003 before any session state is touched.
 */
public final class PerceptionProtocolParser {

    public static final int SAMPLE_RATE = 16_000;
    public static final String FORMAT = "pcm16";
    public static final int CHANNELS = 1;

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private PerceptionProtocolParser() {
    }

    /** One client message. */
    public sealed interface Message permits AudioStart, AudioStop {
    }

    /** Starts one streaming-ASR segment with exact PCM parameters. */
    public record AudioStart(int sampleRate, String format, int channels) implements Message {
    }

    /** Ends the current audio segment and drains the final ASR chunk. */
    public record AudioStop() implements Message {
    }

    /**
     * Parses one client text frame.
     *
     * @throws DomainException with {@link ErrorCode#VALIDATION_ERROR} for any invalid or unknown
     *     message
     */
    public static Message parse(String json) {
        if (json == null || json.isBlank()) {
            throw validation("消息不能为空");
        }
        JsonNode root;
        try {
            root = MAPPER.readTree(json);
        } catch (RuntimeException exception) {
            throw validation("消息格式非法");
        }
        if (root == null || !root.isObject()) {
            throw validation("消息格式非法");
        }

        JsonNode type = root.get("type");
        if (type == null || !type.isString() || type.asString().isBlank()) {
            throw validation("缺少消息类型");
        }
        return switch (type.asString()) {
            case "audio.start" -> parseAudioStart(root);
            case "audio.stop" -> new AudioStop();
            default -> throw validation("未知消息类型");
        };
    }

    private static AudioStart parseAudioStart(JsonNode root) {
        JsonNode format = root.get("format");
        if (format == null || !format.isString() || !FORMAT.equals(format.asString())) {
            throw validation("音频格式必须为 pcm16");
        }
        JsonNode sampleRate = root.get("sample_rate");
        if (sampleRate == null || !sampleRate.isNumber()
                || sampleRate.asInt() != SAMPLE_RATE) {
            throw validation("采样率必须为 16000");
        }
        JsonNode channels = root.get("channels");
        if (channels == null || !channels.isNumber() || channels.asInt() != CHANNELS) {
            throw validation("声道数必须为 1");
        }
        return new AudioStart(SAMPLE_RATE, FORMAT, CHANNELS);
    }

    private static DomainException validation(String message) {
        return new DomainException(ErrorCode.VALIDATION_ERROR, message);
    }
}
