package com.shijiangtai.m3classroom.ws;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.shijiangtai.m3classroom.domain.recording.StreamType;
import com.shijiangtai.m3classroom.domain.session.SessionCommand;
import com.shijiangtai.m3classroom.domain.session.SessionEvent;
import com.shijiangtai.m3classroom.infrastructure.ws.ClientCommandEnvelope;
import com.shijiangtai.m3classroom.infrastructure.ws.ServerEventEnvelope;
import com.shijiangtai.m3classroom.infrastructure.ws.WsCommandParser;
import com.shijiangtai.shared.domain.DomainException;
import com.shijiangtai.shared.domain.ErrorCode;
import com.shijiangtai.shared.domain.SessionId;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Wire contract for the control WebSocket envelopes (02 §6.2).
 *
 * <p>The server envelope is exactly {@code {v,type,event_id,seq,at_ms,session_id,payload}} and the
 * client envelope is exactly {@code {v,type,command_id,payload}}; command ids are mandatory and
 * only protocol version 1 is accepted.
 */
class WsEnvelopeTest {

    private static final SessionId SESSION_ID = new SessionId(
            UUID.fromString("00000000-0000-0000-0000-000000000600"));
    private static final UUID EVENT_ID =
            UUID.fromString("00000000-0000-0000-0000-0000000006aa");
    private static final UUID STUDENT_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000401");

    private final JsonMapper mapper = JsonMapper.builder().build();
    private final WsCommandParser parser = new WsCommandParser(mapper);

    @Test
    void serverEnvelopeUsesSnakeCaseAndRequiredFields() {
        SessionEvent event = new SessionEvent(
                EVENT_ID,
                SESSION_ID,
                42L,
                "student.hand_raised",
                12_345L,
                Map.of("student_id", STUDENT_ID.toString(), "hand_raise_id", UUID.randomUUID()),
                Instant.parse("2026-11-04T00:00:12Z"));

        JsonNode json = mapper.readTree(
                mapper.writeValueAsString(ServerEventEnvelope.of(event)));

        assertThat(json.propertyNames()).containsExactly(
                "v", "type", "event_id", "seq", "at_ms", "session_id", "payload");
        assertThat(json.get("v").asInt()).isEqualTo(1);
        assertThat(json.get("type").asString()).isEqualTo("student.hand_raised");
        assertThat(json.get("event_id").asString()).isEqualTo(EVENT_ID.toString());
        assertThat(json.get("seq").asLong()).isEqualTo(42L);
        assertThat(json.get("at_ms").asLong()).isEqualTo(12_345L);
        assertThat(json.get("session_id").asString()).isEqualTo(SESSION_ID.value().toString());
        assertThat(json.get("payload").get("student_id").asString())
                .isEqualTo(STUDENT_ID.toString());
        assertThat(json.has("eventId")).isFalse();
        assertThat(json.has("atMs")).isFalse();
        assertThat(json.has("sessionId")).isFalse();
    }

    @Test
    void clientEnvelopeParsesCommandIdAndPayload() {
        ClientCommandEnvelope envelope = parser.parse("""
                {"v":1,"type":"teacher.call_on","command_id":"cmd-1",
                 "payload":{"student_id":"00000000-0000-0000-0000-000000000401",
                            "hand_raise_id":"00000000-0000-0000-0000-000000000402"}}
                """);

        assertThat(envelope.v()).isEqualTo(1);
        assertThat(envelope.type()).isEqualTo("teacher.call_on");
        assertThat(envelope.commandId()).isEqualTo("cmd-1");
        assertThat(envelope.payload().get("student_id"))
                .isEqualTo("00000000-0000-0000-0000-000000000401");

        SessionCommand command = parser.toCommand(envelope);
        assertThat(command).isInstanceOf(SessionCommand.CallOn.class);
        SessionCommand.CallOn callOn = (SessionCommand.CallOn) command;
        assertThat(callOn.commandId()).isEqualTo("cmd-1");
        assertThat(callOn.studentId()).isEqualTo(STUDENT_ID);
        assertThat(callOn.handRaiseId())
                .isEqualTo(UUID.fromString("00000000-0000-0000-0000-000000000402"));
    }

    @Test
    void mapsRecordingReadyAndResumePayloads() {
        SessionCommand command = parser.toCommand(parser.parse("""
                {"v":1,"type":"recording.ready","command_id":"ready-1",
                 "payload":{"streams":[
                   {"stream_type":"camera","status":"ready"},
                   {"stream_type":"screen","status":"ready"},
                   {"stream_type":"audio","status":"ready"}]}}
                """));

        assertThat(command).isInstanceOf(SessionCommand.RecordingReady.class);
        SessionCommand.RecordingReady ready = (SessionCommand.RecordingReady) command;
        assertThat(ready.streams()).extracting(
                        SessionCommand.RecordingReady.StreamStatus::streamType)
                .containsExactly(StreamType.CAMERA, StreamType.SCREEN, StreamType.AUDIO);
        assertThat(ready.streams()).allMatch(status -> "ready".equals(status.status()));

        SessionCommand resume = parser.toCommand(parser.parse("""
                {"v":1,"type":"session.resume","command_id":"resume-1",
                 "payload":{"last_seq":7,"recording_state":"active"}}
                """));
        assertThat(resume).isInstanceOf(SessionCommand.ResumeSession.class);
        SessionCommand.ResumeSession resumeSession = (SessionCommand.ResumeSession) resume;
        assertThat(resumeSession.lastSeq()).isEqualTo(7L);
        assertThat(resumeSession.recordingState().wireValue()).isEqualTo("active");
    }

    @Test
    void rejectsMissingCommandIdAndUnsupportedVersion() {
        assertValidationError(() -> parser.parse(
                "{\"v\":1,\"type\":\"session.start\",\"payload\":{}}"));
        assertValidationError(() -> parser.parse(
                "{\"v\":2,\"type\":\"session.start\",\"command_id\":\"cmd-1\",\"payload\":{}}"));
        assertValidationError(() -> parser.parse(
                "{\"v\":1,\"type\":\"session.start\",\"command_id\":\"  \",\"payload\":{}}"));
        assertValidationError(() -> parser.parse(
                "{\"v\":1,\"type\":\"\",\"command_id\":\"cmd-1\",\"payload\":{}}"));
        assertValidationError(() -> parser.parse("not-json"));
        assertValidationError(() -> parser.toCommand(parser.parse(
                "{\"v\":1,\"type\":\"unknown.command\",\"command_id\":\"cmd-1\",\"payload\":{}}")));
    }

    @Test
    void rejectsMessagesLargerThan64Kilobytes() {
        String oversized = "{\"v\":1,\"type\":\"time.sync\",\"command_id\":\"cmd-1\","
                + "\"payload\":{\"client_time_ms\":0,\"padding\":\""
                + "a".repeat(WsCommandParser.MAX_MESSAGE_BYTES) + "\"}}";

        assertValidationError(() -> parser.parse(oversized));
    }

    @Test
    void mapsEveryDocumentedNoPayloadCommand() {
        List<String> types = List.of(
                "session.start", "teacher.ask_class", "teacher.ask_student_question",
                "teacher.end_qa", "teacher.end_session", "interview.next_question",
                "interview.end");

        for (String type : types) {
            ClientCommandEnvelope envelope = parser.parse(
                    "{\"v\":1,\"type\":\"" + type + "\",\"command_id\":\"id-" + type
                            + "\",\"payload\":{}}");
            assertThat(parser.toCommand(envelope)).isNotNull();
        }
    }

    private static void assertValidationError(Runnable action) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(DomainException.class, exception ->
                        assertThat(exception.code()).isEqualTo(ErrorCode.VALIDATION_ERROR));
    }
}
