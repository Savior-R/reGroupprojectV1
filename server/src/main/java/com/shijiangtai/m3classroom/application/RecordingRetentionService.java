package com.shijiangtai.m3classroom.application;

import com.shijiangtai.m3classroom.application.port.RecordingLifecyclePort;
import com.shijiangtai.m3classroom.domain.session.Session;
import com.shijiangtai.m3classroom.domain.session.SessionRepository;
import com.shijiangtai.m3classroom.domain.submission.Submission;
import com.shijiangtai.m3classroom.domain.submission.SubmissionRepository;
import java.time.Instant;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Orchestrates the two retention cleanup passes (05B §6): temporary task sessions expire 24 hours
 * after {@code max(session end, task close)}, and submitted sessions expire one year after the
 * course end.
 *
 * <p>Candidates come from the repository queries; {@link RecordingLifecyclePort} re-checks each
 * candidate under its row lock and owns object-first deletion. A failure is isolated per
 * candidate: it is logged and counted, and the run continues so one broken object cannot block the
 * rest of the sweep. Failed candidates keep their rows and are retried by the next run.
 */
@Service
public class RecordingRetentionService {

    private static final Logger log = LoggerFactory.getLogger(RecordingRetentionService.class);

    private final SessionRepository sessionRepository;
    private final SubmissionRepository submissionRepository;
    private final RecordingLifecyclePort recordingLifecycle;

    public RecordingRetentionService(
            SessionRepository sessionRepository,
            SubmissionRepository submissionRepository,
            RecordingLifecyclePort recordingLifecycle) {
        this.sessionRepository = Objects.requireNonNull(sessionRepository, "sessionRepository");
        this.submissionRepository = Objects.requireNonNull(
                submissionRepository, "submissionRepository");
        this.recordingLifecycle = Objects.requireNonNull(
                recordingLifecycle, "recordingLifecycle");
    }

    /**
     * Deletes every expired temporary session and submitted session.
     *
     * <p>The deletion counts reflect lifecycle calls that actually deleted something; a candidate
     * that is skipped under the lock (for example because it was submitted concurrently) is
     * neither counted as deleted nor as failed.
     */
    public RetentionRun cleanup(Instant now) {
        Objects.requireNonNull(now, "now");
        int temporaryDeleted = 0;
        int submittedDeleted = 0;
        int failed = 0;

        for (Session session : sessionRepository.findExpiredTemporary(now)) {
            try {
                if (recordingLifecycle.deleteExpiredTemporary(session.id())) {
                    temporaryDeleted++;
                }
            } catch (RuntimeException exception) {
                failed++;
                log.warn("Temporary recording retention cleanup failed: sessionId={}",
                        session.id().value(), exception);
            }
        }

        for (Submission submission : submissionRepository.findExpiredForRetention(now)) {
            try {
                if (recordingLifecycle.deleteSubmittedSession(submission.sessionId())) {
                    submittedDeleted++;
                }
            } catch (RuntimeException exception) {
                failed++;
                log.warn("Submitted recording retention cleanup failed: submissionId={}, "
                                + "sessionId={}",
                        submission.id(), submission.sessionId().value(), exception);
            }
        }

        log.info("Recording retention cleanup finished: temporaryDeleted={}, submittedDeleted={}, "
                        + "failed={}",
                temporaryDeleted, submittedDeleted, failed);
        return new RetentionRun(temporaryDeleted, submittedDeleted, failed);
    }

    /** Counts from one cleanup run. */
    public record RetentionRun(int temporaryDeleted, int submittedDeleted, int failed) {
    }
}