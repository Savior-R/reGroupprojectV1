package com.shijiangtai.m3classroom.domain.task;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class TaskSnapshotFactory {

    public TaskSnapshot create(
            Task task, CourseInfo course, List<PublishQuestion> questions, java.time.Instant publishedAt) {
        Objects.requireNonNull(task, "task");
        Objects.requireNonNull(course, "course");
        Objects.requireNonNull(questions, "questions");
        Objects.requireNonNull(publishedAt, "publishedAt");

        TaskDraft draft = task.draft();
        TaskMode mode = draft.mode();

        Map<String, Integer> weights = new LinkedHashMap<>();
        for (String dimension : mode.dimensions()) {
            weights.put(dimension, draft.rubricWeights().of(dimension));
        }

        TaskSnapshot.MaterialSnapshot material = null;
        if (mode != TaskMode.INTERVIEW && task.referenceMaterial() != null) {
            ReferenceMaterial reference = task.referenceMaterial();
            material = new TaskSnapshot.MaterialSnapshot(
                    reference.materialId(),
                    reference.filename(),
                    reference.objectKey(),
                    reference.mime(),
                    reference.size(),
                    reference.parsedText(),
                    reference.charCount());
        }

        TaskSnapshot.ScenarioSnapshot scenario = null;
        if (mode == TaskMode.SCENARIO && draft.scenario() != null) {
            scenario = new TaskSnapshot.ScenarioSnapshot(
                    draft.scenario().role(), draft.scenario().situation());
        }

        List<TaskSnapshot.VirtualStudentSnapshot> virtualStudents = List.of();
        if (mode != TaskMode.INTERVIEW) {
            virtualStudents = draft.virtualStudents().stream()
                    .map(student -> new TaskSnapshot.VirtualStudentSnapshot(
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

        TaskSnapshot.InterviewSnapshot interview = null;
        if (mode == TaskMode.INTERVIEW) {
            List<TaskSnapshot.QuestionSnapshot> questionSnapshots = new ArrayList<>();
            for (int index = 0; index < questions.size(); index++) {
                PublishQuestion question = questions.get(index);
                questionSnapshots.add(new TaskSnapshot.QuestionSnapshot(
                        question.questionId(),
                        index + 1,
                        question.stem(),
                        question.answerPoints(),
                        question.categoryId(),
                        question.categoryName()));
            }
            interview = new TaskSnapshot.InterviewSnapshot(
                    draft.questionConfig().totalDurationSeconds(),
                    questionSnapshots.size(),
                    questionSnapshots);
        }

        return new TaskSnapshot(
                1,
                task.id().value(),
                task.courseId().value(),
                course.schoolId(),
                mode.wireValue(),
                draft.topic(),
                draft.textbookVersion(),
                new TaskSnapshot.DurationSnapshot(
                        draft.durationRecommendedSeconds(), draft.durationMaxSeconds()),
                weights,
                material,
                scenario,
                virtualStudents,
                interview,
                publishedAt,
                task.createdBy().value());
    }
}
