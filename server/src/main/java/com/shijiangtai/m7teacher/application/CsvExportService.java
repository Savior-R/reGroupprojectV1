package com.shijiangtai.m7teacher.application;

import com.shijiangtai.m7teacher.application.GradingSubmissionQueryService.ExportRows;
import com.shijiangtai.m7teacher.application.port.UpstreamQueryPort;
import com.shijiangtai.m7teacher.application.view.AiScoreView;
import com.shijiangtai.m7teacher.application.view.FinalScoreRow;
import com.shijiangtai.m7teacher.application.view.GradingSubmissionRow;
import com.shijiangtai.m7teacher.application.view.TeacherScoreRow;
import com.shijiangtai.m7teacher.domain.M7ErrorCode;
import com.shijiangtai.m7teacher.domain.M7Exception;
import com.shijiangtai.shared.application.CurrentUser;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Streaming CSV export (FR-M7-06 / 07 §8).
 *
 * <p>UTF-8 BOM, CRLF, RFC 4180 escaping, a 10,000-row ceiling, and formula-injection
 * protection for text cells beginning with {@code = + - @}.
 */
@Service
public class CsvExportService {

    public static final int MAX_ROWS = 10_000;
    private static final byte[] BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
    private static final DateTimeFormatter TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneOffset.UTC);
    private static final Map<String, String> DIMENSION_LABELS = Map.of(
            "content", "教学内容",
            "expression", "教学表达",
            "interaction", "课堂互动与控场",
            "board", "板书/PPT",
            "time", "时间管理");
    private static final Map<String, String> STATUS_LABELS = Map.of(
            "completed", "已完成",
            "pending_ai", "待 AI",
            "pending_teacher", "待教师",
            "ai_partial", "AI 评测不完整",
            "ai_failed", "AI 评测失败",
            "not_submitted", "未提交");
    private static final Map<String, String> STUDENT_STATUS_LABELS = Map.of(
            "active", "在读",
            "left", "已退课",
            "removed", "已移出");

    private final GradingSubmissionQueryService submissionQueryService;
    private final UpstreamQueryPort upstream;

    public CsvExportService(
            GradingSubmissionQueryService submissionQueryService,
            UpstreamQueryPort upstream) {
        this.submissionQueryService = submissionQueryService;
        this.upstream = upstream;
    }

    public byte[] export(
            CurrentUser user, UUID taskId, UUID classId, String status, String keyword) {
        ExportRows export = submissionQueryService.exportRows(
                user, taskId, classId, status, keyword);
        if (export.rows().size() > MAX_ROWS) {
            throw new M7Exception(M7ErrorCode.EXPORT_TOO_LARGE,
                    "导出超过 10000 行，请缩小筛选范围",
                    Map.of("max_rows", MAX_ROWS, "rows", export.rows().size()));
        }

        Set<UUID> graderIds = new HashSet<>();
        export.rows().forEach(row -> {
            if (row.teacherScore() != null && row.teacherScore().gradedBy() != null) {
                graderIds.add(row.teacherScore().gradedBy());
            }
        });
        Map<UUID, UpstreamQueryPort.Account> graders = upstream.findAccounts(graderIds);

        List<String> header = new ArrayList<>(List.of(
                "任务", "课程", "班级", "学生姓名", "账号ID", "学生状态", "提交时间",
                "AI总分", "教师总分", "最终总分"));
        for (String dimension : export.config().dimensions()) {
            header.add(DIMENSION_LABELS.getOrDefault(dimension, dimension));
        }
        header.addAll(List.of("教师评语", "成绩状态", "评分教师", "评分时间"));

        StringBuilder csv = new StringBuilder();
        appendRow(csv, header);
        for (GradingSubmissionRow row : export.rows()) {
            List<String> cells = new ArrayList<>();
            cells.add(nonNull(export.context().task().topic()));
            cells.add(nonNull(export.context().course().name()));
            cells.add(nonNull(row.className()));
            cells.add(nonNull(row.studentName()));
            cells.add(row.studentAccountId().toString());
            cells.add(STUDENT_STATUS_LABELS.getOrDefault(row.studentStatus(), row.studentStatus()));
            cells.add(row.submittedAt() == null ? "" : TIME_FORMAT.format(row.submittedAt()));
            AiScoreView ai = row.ai();
            TeacherScoreRow teacher = row.teacherScore();
            FinalScoreRow finalScore = row.finalScore();
            cells.add(ai == null ? "" : score(ai.totalScore()));
            cells.add(teacher == null ? "" : score(teacher.teacherTotal()));
            cells.add(finalScore == null || finalScore.finalTotal() == null
                    ? "" : score(finalScore.finalTotal()));
            Map<String, BigDecimal> dimensions = finalScore == null
                    ? Map.of() : finalScore.finalDimensions();
            for (String dimension : export.config().dimensions()) {
                cells.add(score(dimensions.get(dimension)));
            }
            cells.add(teacher == null || teacher.comment() == null ? "" : teacher.comment());
            cells.add(finalScore == null ? STATUS_LABELS.get("not_submitted")
                    : STATUS_LABELS.getOrDefault(finalScore.status(), finalScore.status()));
            UpstreamQueryPort.Account grader = teacher == null
                    ? null : graders.get(teacher.gradedBy());
            cells.add(grader == null ? "" : nonNull(grader.username()));
            cells.add(teacher == null || teacher.gradedAt() == null
                    ? "" : TIME_FORMAT.format(teacher.gradedAt()));
            appendRow(csv, cells);
        }

        byte[] body = csv.toString().getBytes(StandardCharsets.UTF_8);
        byte[] result = new byte[BOM.length + body.length];
        System.arraycopy(BOM, 0, result, 0, BOM.length);
        System.arraycopy(body, 0, result, BOM.length, body.length);
        return result;
    }

    private static void appendRow(StringBuilder csv, List<String> cells) {
        for (int index = 0; index < cells.size(); index++) {
            if (index > 0) {
                csv.append(',');
            }
            csv.append(escape(cells.get(index)));
        }
        csv.append("\r\n");
    }

    static String escape(String raw) {
        String value = raw == null ? "" : raw;
        if (!value.isEmpty()) {
            char first = value.charAt(0);
            if (first == '=' || first == '+' || first == '-' || first == '@'
                    || first == '\t' || first == '\r') {
                value = "'" + value;
            }
        }
        if (value.indexOf(',') >= 0 || value.indexOf('"') >= 0
                || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0) {
            return '"' + value.replace("\"", "\"\"") + '"';
        }
        return value;
    }

    public static String fileName(UUID taskId, Instant now) {
        return "task-" + taskId + "-scores-"
                + DateTimeFormatter.ofPattern("yyyyMMddHHmm").withZone(ZoneOffset.UTC)
                        .format(now)
                + ".csv";
    }

    private static String score(BigDecimal value) {
        return value == null ? "" : value.setScale(1, java.math.RoundingMode.HALF_UP)
                .toPlainString();
    }

    private static String nonNull(String value) {
        return value == null ? "" : value;
    }
}
