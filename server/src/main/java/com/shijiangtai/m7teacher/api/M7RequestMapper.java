package com.shijiangtai.m7teacher.api;

import com.shijiangtai.m7teacher.api.dto.BatchSaveRequest;
import com.shijiangtai.m7teacher.api.dto.SaveTeacherScoreRequest;
import com.shijiangtai.m7teacher.api.dto.UpdateGradingConfigRequest;
import com.shijiangtai.m7teacher.application.command.SaveTeacherScoreCommand;
import com.shijiangtai.m7teacher.application.command.UpdateGradingConfigCommand;
import com.shijiangtai.m7teacher.domain.M7ErrorCode;
import com.shijiangtai.m7teacher.domain.M7Exception;
import com.shijiangtai.m7teacher.domain.ScoringMode;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Maps HTTP request payloads to application commands. */
@Component
public class M7RequestMapper {

    public UpdateGradingConfigCommand toCommand(UpdateGradingConfigRequest request) {
        if (request == null || request.aiWeight() == null
                || request.teacherWeight() == null || request.configVersion() == null) {
            throw new M7Exception(M7ErrorCode.VALIDATION_ERROR, "缺少必填字段");
        }
        return new UpdateGradingConfigCommand(
                request.aiWeight(), request.teacherWeight(),
                ScoringMode.fromWire(request.scoringMode()), request.configVersion());
    }

    public SaveTeacherScoreCommand toCommand(
            UUID submissionId, SaveTeacherScoreRequest request) {
        if (request == null) {
            throw new M7Exception(M7ErrorCode.TEACHER_SCORE_INVALID, "评分内容不能为空");
        }
        return new SaveTeacherScoreCommand(
                submissionId,
                teacherMode(request.scoringMode()),
                request.dimensionScores() == null ? Map.of() : request.dimensionScores(),
                request.teacherTotal(),
                request.comment(),
                request.version() == null ? 0 : request.version());
    }

    public List<SaveTeacherScoreCommand> toCommands(BatchSaveRequest request) {
        if (request == null || request.items() == null || request.items().isEmpty()) {
            throw new M7Exception(M7ErrorCode.VALIDATION_ERROR, "批量保存内容不能为空");
        }
        return request.items().stream()
                .map(item -> {
                    ScoringMode mode = null;
                    if (item != null) {
                        try {
                            mode = teacherMode(item.scoringMode());
                        } catch (M7Exception ignored) {
                            // Keep the row; the batch service records it as an invalid row
                            // instead of failing the whole request (07 §5.5).
                        }
                    }
                    return new SaveTeacherScoreCommand(
                            item == null ? null : item.submissionId(),
                            mode,
                            item == null || item.dimensionScores() == null
                                    ? Map.of() : item.dimensionScores(),
                            item == null ? null : item.teacherTotal(),
                            item == null ? null : item.comment(),
                            item == null || item.version() == null ? 0 : item.version());
                })
                .toList();
    }

    private static ScoringMode teacherMode(String value) {
        try {
            return ScoringMode.fromWire(value);
        } catch (M7Exception exception) {
            throw new M7Exception(M7ErrorCode.TEACHER_SCORE_INVALID,
                    "打分模式必须为 dimension 或 total", Map.of("field", "scoring_mode"));
        }
    }
}
