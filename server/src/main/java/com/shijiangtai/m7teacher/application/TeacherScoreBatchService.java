package com.shijiangtai.m7teacher.application;

import com.shijiangtai.m7teacher.application.command.SaveTeacherScoreCommand;
import com.shijiangtai.m7teacher.application.port.UpstreamQueryPort;
import com.shijiangtai.m7teacher.application.view.BatchRowView;
import com.shijiangtai.m7teacher.application.view.BatchSaveView;
import com.shijiangtai.m7teacher.application.view.TeacherScoreSaveView;
import com.shijiangtai.m7teacher.domain.M7ErrorCode;
import com.shijiangtai.m7teacher.domain.M7Exception;
import com.shijiangtai.shared.application.CurrentUser;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Batch score save with per-row results and partial success (07 §5.5).
 *
 * <p>Deliberately not transactional: each row goes through {@link TeacherScoreService#save} in
 * its own transaction, so one failed row never rolls back the successful rows.
 */
@Service
public class TeacherScoreBatchService {

    private static final int MAX_ROWS = 100;

    private final TeacherScoreService teacherScoreService;
    private final M7AccessPolicy accessPolicy;
    private final UpstreamQueryPort upstream;

    public TeacherScoreBatchService(
            TeacherScoreService teacherScoreService,
            M7AccessPolicy accessPolicy,
            UpstreamQueryPort upstream) {
        this.teacherScoreService = teacherScoreService;
        this.accessPolicy = accessPolicy;
        this.upstream = upstream;
    }

    public BatchSaveView save(
            CurrentUser teacher, UUID taskId, List<SaveTeacherScoreCommand> commands) {
        TaskContext context = accessPolicy.requireTeacherTask(teacher, taskId);
        accessPolicy.requireNotArchived(context);
        accessPolicy.requirePublished(context);
        if (commands == null || commands.isEmpty()) {
            throw new M7Exception(M7ErrorCode.VALIDATION_ERROR, "批量保存内容不能为空");
        }
        if (commands.size() > MAX_ROWS) {
            throw new M7Exception(M7ErrorCode.TEACHER_SCORE_INVALID,
                    "批量保存最多 100 行", Map.of("max", MAX_ROWS));
        }

        List<BatchRowView> results = new ArrayList<>(commands.size());
        int success = 0;
        for (SaveTeacherScoreCommand command : commands) {
            UUID submissionId = command == null ? null : command.submissionId();
            if (submissionId == null) {
                results.add(new BatchRowView(null, false, null, null,
                        M7ErrorCode.SUBMISSION_NOT_SUBMITTED.name(), "提交件不存在"));
                continue;
            }
            UpstreamQueryPort.Submission submission = upstream.findSubmission(submissionId)
                    .orElse(null);
            if (submission == null || !taskId.equals(submission.taskId())) {
                results.add(new BatchRowView(submissionId, false, null, null,
                        M7ErrorCode.SUBMISSION_NOT_SUBMITTED.name(), "提交件不属于该任务"));
                continue;
            }
            try {
                TeacherScoreSaveView saved = teacherScoreService.save(teacher, submissionId, command);
                results.add(new BatchRowView(submissionId, true,
                        saved.teacherScore(), saved.finalScore(), null, null));
                success++;
            } catch (M7Exception exception) {
                results.add(new BatchRowView(submissionId, false, null, null,
                        exception.code().name(), exception.getMessage()));
            } catch (RuntimeException exception) {
                results.add(new BatchRowView(submissionId, false, null, null,
                        M7ErrorCode.INTERNAL_ERROR.name(), "保存失败，请重试"));
            }
        }
        return new BatchSaveView(success, results.size() - success, results);
    }
}
