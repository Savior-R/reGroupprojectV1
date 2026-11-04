package com.shijiangtai.m3classroom.infrastructure.ws;

import com.shijiangtai.m3classroom.domain.classroom.Correctness;
import com.shijiangtai.m3classroom.domain.recording.StreamType;
import com.shijiangtai.m3classroom.domain.session.RecordingState;
import com.shijiangtai.m3classroom.domain.session.SessionCommand;
import com.shijiangtai.shared.domain.DomainException;
import com.shijiangtai.shared.domain.ErrorCode;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Parses and validates client command envelopes (02 §6.2—§6.3).
 *
 * <p>Protocol version 1 and a non-blank {@code command_id} are mandatory. Invalid JSON,
 * unsupported versions, unknown command types and malformed payloads all surface as
 * {@link ErrorCode#VALIDATION_ERROR}; no command reaches the actor in those cases. The 64 KB
 * frame limit is enforced before command dispatch.
 */
@Component
public final class WsCommandParser {

    public static final int MAX_MESSAGE_BYTES = 64 * 1024;
    public static final int PROTOCOL_VERSION = 1;

    private final ObjectMapper objectMapper;

    public WsCommandParser(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    /** {@code true} when the UTF-8 encoded frame exceeds the 64 KB protocol limit. */
    public boolean exceedsSizeLimit(String text) {
        return text != null
                && text.getBytes(StandardCharsets.UTF_8).length > MAX_MESSAGE_BYTES;
    }

    public ClientCommandEnvelope parse(String text) {
        if (text == null || text.isBlank()) {
            throw validation("消息不能为空", Map.of());
        }
        if (exceedsSizeLimit(text)) {
            throw validation("消息超过 64 KB", Map.of("max_bytes", MAX_MESSAGE_BYTES));
        }
        ClientCommandEnvelope envelope;
        try {
            envelope = objectMapper.readValue(text, ClientCommandEnvelope.class);
        } catch (RuntimeException exception) {
            throw validation("消息格式非法", Map.of());
        }
        if (envelope == null) {
            throw validation("消息格式非法", Map.of());
        }
        if (envelope.v() != PROTOCOL_VERSION) {
            throw validation("不支持的信封版本", Map.of("v", envelope.v()));
        }
        if (envelope.type() == null || envelope.type().isBlank()) {
            throw validation("缺少命令类型", Map.of("field", "type"));
        }
        if (envelope.commandId() == null || envelope.commandId().isBlank()) {
            throw validation("缺少 command_id", Map.of("field", "command_id"));
        }
        return envelope;
    }

    /**
     * Maps a validated envelope to the domain command. Payload field names and wire enum values
     * follow 02 §6.3 verbatim.
     */
    public SessionCommand toCommand(ClientCommandEnvelope envelope) {
        Objects.requireNonNull(envelope, "envelope");
        String commandId = envelope.commandId();
        Map<String, Object> payload = envelope.payload();
        return switch (envelope.type()) {
            case "recording.ready" ->
                    new SessionCommand.RecordingReady(commandId, streams(payload));
            case "session.start" -> new SessionCommand.StartSession(commandId);
            case "session.resume" -> new SessionCommand.ResumeSession(
                    commandId,
                    requireLong(payload, "last_seq"),
                    RecordingState.fromWire(requireString(payload, "recording_state")));
            case "teacher.ask_class" -> new SessionCommand.AskClass(commandId);
            case "teacher.ask_student_question" ->
                    new SessionCommand.AskStudentQuestion(commandId);
            case "teacher.call_on" -> new SessionCommand.CallOn(
                    commandId,
                    requireUuid(payload, "student_id"),
                    optionalUuid(payload, "hand_raise_id"));
            case "teacher.mark_answer" -> new SessionCommand.MarkAnswer(
                    commandId,
                    requireUuid(payload, "answer_id"),
                    Correctness.fromWire(requireString(payload, "correctness")));
            case "teacher.manage_student" -> new SessionCommand.ManageStudent(
                    commandId,
                    requireUuid(payload, "student_id"),
                    requireString(payload, "action"));
            case "teacher.end_dialogue" ->
                    new SessionCommand.EndDialogue(commandId, optionalUuid(payload, "dialogue_id"));
            case "teacher.end_qa" -> new SessionCommand.EndQa(commandId);
            case "teacher.end_session" -> new SessionCommand.EndSession(commandId);
            case "interview.next_question" -> new SessionCommand.NextQuestion(commandId);
            case "interview.end" -> new SessionCommand.EndInterview(commandId);
            case "time.sync" -> new SessionCommand.TimeSync(
                    commandId, optionalLong(payload, "client_time_ms", 0L));
            case "pong" -> new SessionCommand.Pong(
                    commandId, optionalLong(payload, "client_time_ms", 0L));
            default -> throw validation("未知命令类型", Map.of("type", envelope.type()));
        };
    }

    private static List<SessionCommand.RecordingReady.StreamStatus> streams(
            Map<String, Object> payload) {
        Object raw = payload.get("streams");
        if (raw == null) {
            return List.of();
        }
        if (!(raw instanceof List<?> list)) {
            throw validation("streams 必须是数组", Map.of("field", "streams"));
        }
        List<SessionCommand.RecordingReady.StreamStatus> statuses = new ArrayList<>(list.size());
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> stream)) {
                throw validation("streams 元素非法", Map.of("field", "streams"));
            }
            Object streamType = stream.get("stream_type");
            Object status = stream.get("status");
            if (!(streamType instanceof String type) || !(status instanceof String state)) {
                throw validation("录制流字段缺失",
                        Map.of("field", "streams", "required", List.of("stream_type", "status")));
            }
            statuses.add(new SessionCommand.RecordingReady.StreamStatus(
                    StreamType.fromWire(type), state));
        }
        return List.copyOf(statuses);
    }

    private static String requireString(Map<String, Object> payload, String field) {
        Object value = payload.get(field);
        if (!(value instanceof String text) || text.isBlank()) {
            throw validation("字段缺失或非法", Map.of("field", field));
        }
        return text;
    }

    private static long requireLong(Map<String, Object> payload, String field) {
        Object value = payload.get(field);
        if (value instanceof Number number) {
            return number.longValue();
        }
        throw validation("字段缺失或非法", Map.of("field", field));
    }

    private static long optionalLong(
            Map<String, Object> payload, String field, long defaultValue) {
        Object value = payload.get(field);
        return value instanceof Number number ? number.longValue() : defaultValue;
    }

    private static UUID requireUuid(Map<String, Object> payload, String field) {
        String value = requireString(payload, field);
        return parseUuid(field, value);
    }

    private static UUID optionalUuid(Map<String, Object> payload, String field) {
        Object value = payload.get(field);
        if (value == null) {
            return null;
        }
        if (!(value instanceof String text) || text.isBlank()) {
            throw validation("字段非法", Map.of("field", field));
        }
        return parseUuid(field, text);
    }

    private static UUID parseUuid(String field, String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException exception) {
            throw validation("UUID 非法", Map.of("field", field));
        }
    }

    private static DomainException validation(String message, Map<String, Object> details) {
        return new DomainException(ErrorCode.VALIDATION_ERROR, message, details);
    }
}
