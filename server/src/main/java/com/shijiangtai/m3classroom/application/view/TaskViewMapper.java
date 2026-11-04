package com.shijiangtai.m3classroom.application.view;

import com.shijiangtai.m3classroom.domain.task.CourseInfo;
import com.shijiangtai.m3classroom.domain.task.QuestionConfig;
import com.shijiangtai.m3classroom.domain.task.ReferenceMaterial;
import com.shijiangtai.m3classroom.domain.task.Task;
import com.shijiangtai.m3classroom.domain.task.TaskAssignment;
import com.shijiangtai.m3classroom.domain.task.TaskDraft;
import com.shijiangtai.m3classroom.domain.task.TaskMode;
import com.shijiangtai.m3classroom.domain.task.TaskSnapshot;
import java.util.List;
import java.util.UUID;

public final class TaskViewMapper {

    private TaskViewMapper() {
    }

    public static TaskView toView(Task task) {
        TaskDraft draft = task.draft();
        return new TaskView(
                task.id().value(),
                task.courseId().value(),
                draft.mode().wireValue(),
                draft.topic(),
                draft.textbookVersion(),
                draft.durationRecommendedSeconds(),
                draft.durationMaxSeconds(),
                draft.rubricWeights().asMap(),
                toReferenceMaterialView(task.referenceMaterial()),
                draft.scenario(),
                toVirtualStudentViews(draft),
                toQuestionConfigView(draft),
                publishedQuestions(task),
                task.status().wireValue(),
                task.configVersion(),
                task.publishedAt(),
                task.closedAt(),
                task.createdAt(),
                task.updatedAt());
    }

    public static TaskCardView toCard(Task task, long assignedCount, long submittedCount) {
        TaskDraft draft = task.draft();
        return new TaskCardView(
                task.id().value(),
                task.courseId().value(),
                draft.mode().wireValue(),
                draft.topic(),
                durationSeconds(task),
                questionCount(task),
                draft.virtualStudents().size(),
                task.status().wireValue(),
                assignedCount,
                submittedCount,
                task.publishedAt(),
                task.updatedAt());
    }

    public static StudentTaskView toStudentView(
            Task task, CourseInfo course, TaskAssignment assignment) {
        TaskDraft draft = task.draft();
        return new StudentTaskView(
                task.id().value(),
                task.courseId().value(),
                course.name(),
                draft.mode().wireValue(),
                draft.topic(),
                durationSeconds(task),
                questionCount(task),
                toStudentVirtualStudentViews(draft),
                course.teacherAccountIds(),
                task.status().wireValue(),
                assignment.status().wireValue());
    }

    public static StudentTaskDetailView toStudentDetail(
            Task task, CourseInfo course, TaskAssignment assignment) {
        TaskDraft draft = task.draft();
        return new StudentTaskDetailView(
                task.id().value(),
                task.courseId().value(),
                course.name(),
                draft.mode().wireValue(),
                draft.topic(),
                durationSeconds(task),
                questionCount(task),
                toStudentVirtualStudentViews(draft),
                course.teacherAccountIds(),
                task.status().wireValue(),
                assignment.status().wireValue());
    }

    public static TaskAssignmentView toAssignment(TaskAssignment assignment) {
        return new TaskAssignmentView(
                assignment.studentAccountId().value(),
                assignment.classIdSnapshot() == null ? null : assignment.classIdSnapshot().value(),
                assignment.status().wireValue(),
                assignment.assignedAt());
    }

    public static MaterialView toMaterialView(
            ReferenceMaterial material, boolean includeParsedText) {
        return new MaterialView(
                material.materialId(),
                material.filename(),
                material.size(),
                material.mime(),
                material.charCount(),
                "parsed",
                material.preview(),
                includeParsedText ? material.parsedText() : null);
    }

    public static int durationSeconds(Task task) {
        TaskDraft draft = task.draft();
        if (draft.mode() != TaskMode.INTERVIEW) {
            return draft.durationMaxSeconds();
        }
        if (draft.questionConfig() != null) {
            return draft.questionConfig().totalDurationSeconds();
        }
        TaskSnapshot snapshot = task.snapshot();
        return snapshot != null && snapshot.interview() != null
                ? snapshot.interview().totalDurationSeconds() : 0;
    }

    public static int questionCount(Task task) {
        TaskDraft draft = task.draft();
        if (draft.mode() != TaskMode.INTERVIEW) {
            return 0;
        }
        if (draft.questionConfig() != null) {
            return draft.questionConfig().questionIds().size();
        }
        TaskSnapshot snapshot = task.snapshot();
        return snapshot != null && snapshot.interview() != null
                ? snapshot.interview().questionCount() : 0;
    }

    private static ReferenceMaterialView toReferenceMaterialView(ReferenceMaterial material) {
        if (material == null) {
            return null;
        }
        return new ReferenceMaterialView(
                material.materialId(),
                material.filename(),
                material.size(),
                material.mime(),
                material.charCount(),
                "parsed",
                material.preview());
    }

    private static List<VirtualStudentView> toVirtualStudentViews(TaskDraft draft) {
        return draft.virtualStudents().stream()
                .map(student -> new VirtualStudentView(
                        student.id(),
                        student.name(),
                        student.avatar(),
                        student.personalityText(),
                        student.activity(),
                        student.questionRate(),
                        student.ability(),
                        student.attentionBaseline(),
                        student.disciplineProbability(),
                        student.seatNo(),
                        student.sortOrder()))
                .toList();
    }

    private static List<StudentVirtualStudentView> toStudentVirtualStudentViews(TaskDraft draft) {
        if (draft.mode() == TaskMode.INTERVIEW) {
            return List.of();
        }
        return draft.virtualStudents().stream()
                .map(student -> new StudentVirtualStudentView(
                        student.id(), student.name(), student.avatar(), student.seatNo()))
                .toList();
    }

    private static QuestionConfigView toQuestionConfigView(TaskDraft draft) {
        QuestionConfig questionConfig = draft.questionConfig();
        if (questionConfig == null) {
            return null;
        }
        List<UUID> questionIds = questionConfig.questionIds().stream()
                .map(questionId -> questionId.value())
                .toList();
        return new QuestionConfigView(questionIds, questionConfig.totalDurationSeconds());
    }

    private static List<PublishedQuestionView> publishedQuestions(Task task) {
        TaskSnapshot snapshot = task.snapshot();
        if (snapshot == null || snapshot.interview() == null) {
            return List.of();
        }
        return snapshot.interview().questions().stream()
                .map(question -> new PublishedQuestionView(
                        question.questionId(),
                        question.order(),
                        question.stem(),
                        question.answerPoints(),
                        question.categoryId(),
                        question.categoryName()))
                .toList();
    }
}
