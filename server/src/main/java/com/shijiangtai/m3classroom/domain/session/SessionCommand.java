package com.shijiangtai.m3classroom.domain.session;

import com.shijiangtai.m3classroom.domain.classroom.Correctness;
import com.shijiangtai.m3classroom.domain.recording.StreamType;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Client commands accepted by a {@link com.shijiangtai.m3classroom.application.SessionActor}
 * (05A §6.1). Every command carries an idempotency {@code command_id}.
 *
 * <p>The sealed hierarchy lives in one compilation unit; the permitted subtypes are the nested
 * records below.
 */
public sealed interface SessionCommand {

    String commandId();

    /** {@code recording.ready}: reports the client recorder status for each stream. */
    record RecordingReady(String commandId, List<StreamStatus> streams) implements SessionCommand {
        public RecordingReady {
            requireCommandId(commandId);
            streams = streams == null ? List.of() : List.copyOf(streams);
        }

        /** One client recorder report; {@code status} is {@code ready} when the recorder works. */
        public record StreamStatus(StreamType streamType, String status) {
            public StreamStatus {
                Objects.requireNonNull(streamType, "streamType");
                Objects.requireNonNull(status, "status");
            }
        }
    }

    /** {@code session.start}: moves {@code preparing -> in_progress} after all streams are ready. */
    record StartSession(String commandId) implements SessionCommand {
        public StartSession {
            requireCommandId(commandId);
        }
    }

    /** {@code session.resume}: reconnect inside the 30 s grace window. */
    record ResumeSession(String commandId, long lastSeq, RecordingState recordingState)
            implements SessionCommand {
        public ResumeSession {
            requireCommandId(commandId);
            Objects.requireNonNull(recordingState, "recordingState");
            if (lastSeq < 0) {
                throw new IllegalArgumentException("lastSeq must not be negative");
            }
        }
    }

    /** {@code teacher.ask_class}: opens a hand window with intent {@code answer}. */
    record AskClass(String commandId) implements SessionCommand {
        public AskClass {
            requireCommandId(commandId);
        }
    }

    /** {@code teacher.ask_student_question}: opens a hand window with intent {@code student_question}. */
    record AskStudentQuestion(String commandId) implements SessionCommand {
        public AskStudentQuestion {
            requireCommandId(commandId);
        }
    }

    /** {@code teacher.call_on}: calls on a student from the hand window. */
    record CallOn(String commandId, UUID studentId, UUID handRaiseId) implements SessionCommand {
        public CallOn {
            requireCommandId(commandId);
            Objects.requireNonNull(studentId, "studentId");
        }
    }

    /** {@code teacher.mark_answer}: marks the current answer correct / partial / wrong. */
    record MarkAnswer(String commandId, UUID answerId, Correctness correctness)
            implements SessionCommand {
        public MarkAnswer {
            requireCommandId(commandId);
            Objects.requireNonNull(answerId, "answerId");
            Objects.requireNonNull(correctness, "correctness");
        }
    }

    /** {@code teacher.manage_student}: reminds or disciplines a student. */
    record ManageStudent(String commandId, UUID studentId, String action)
            implements SessionCommand {
        public ManageStudent {
            requireCommandId(commandId);
            Objects.requireNonNull(studentId, "studentId");
            Objects.requireNonNull(action, "action");
        }
    }

    /** {@code teacher.end_dialogue}: ends the current dialogue and returns to waiting hands. */
    record EndDialogue(String commandId, UUID dialogueId) implements SessionCommand {
        public EndDialogue {
            requireCommandId(commandId);
        }
    }

    /** {@code teacher.end_qa}: ends the whole QA block and returns to lecturing. */
    record EndQa(String commandId) implements SessionCommand {
        public EndQa {
            requireCommandId(commandId);
        }
    }

    /** {@code teacher.end_session}: ends a classroom session. */
    record EndSession(String commandId) implements SessionCommand {
        public EndSession {
            requireCommandId(commandId);
        }
    }

    /** {@code interview.next_question}: closes the current answer and shows the next question. */
    record NextQuestion(String commandId) implements SessionCommand {
        public NextQuestion {
            requireCommandId(commandId);
        }
    }

    /** {@code interview.end}: ends the structured interview. */
    record EndInterview(String commandId) implements SessionCommand {
        public EndInterview {
            requireCommandId(commandId);
        }
    }

    /** {@code time.sync}: clock synchronisation request. */
    record TimeSync(String commandId, long clientTimeMs) implements SessionCommand {
        public TimeSync {
            requireCommandId(commandId);
        }
    }

    /** {@code pong}: heartbeat response. */
    record Pong(String commandId, long clientTimeMs) implements SessionCommand {
        public Pong {
            requireCommandId(commandId);
        }
    }

    private static void requireCommandId(String commandId) {
        if (commandId == null || commandId.isBlank()) {
            throw new IllegalArgumentException("commandId must not be blank");
        }
    }
}
