package com.shijiangtai.m6training.api;

import com.shijiangtai.m6training.api.dto.CopyTrainingTemplateRequest;
import com.shijiangtai.m6training.api.dto.CreateTrainingTemplateRequest;
import com.shijiangtai.m6training.api.dto.DurationRequest;
import com.shijiangtai.m6training.api.dto.UpdateTrainingTemplateRequest;
import com.shijiangtai.m6training.api.dto.VirtualStudentRequest;
import com.shijiangtai.m6training.application.TrainingTemplateService;
import com.shijiangtai.m6training.application.command.CopyTrainingTemplateCommand;
import com.shijiangtai.m6training.application.command.CreateTrainingTemplateCommand;
import com.shijiangtai.m6training.application.command.UpdateTrainingTemplateCommand;
import com.shijiangtai.m6training.application.command.VirtualStudentCommand;
import com.shijiangtai.m6training.application.view.TaskTemplateConfigView;
import com.shijiangtai.m6training.application.view.TrainingTemplateDetailView;
import com.shijiangtai.m6training.application.view.TrainingTemplateSummaryView;
import com.shijiangtai.shared.application.CurrentUserProvider;
import com.shijiangtai.shared.domain.PageResult;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Personal template library (08 §10.1). */
@RestController
@RequestMapping("/api/v1/training/my-templates")
public class PersonalTrainingTemplateController {
    private final TrainingTemplateService service;
    private final CurrentUserProvider currentUserProvider;

    public PersonalTrainingTemplateController(
            TrainingTemplateService service, CurrentUserProvider currentUserProvider) {
        this.service = service;
        this.currentUserProvider = currentUserProvider;
    }

    @GetMapping
    public PageResult<TrainingTemplateSummaryView> list(
            @RequestParam(required = false) String role,
            @RequestParam(required = false) String level,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return service.list(
                currentUserProvider.require(), role, level, status, page, size);
    }

    @PostMapping
    public TrainingTemplateDetailView create(
            @RequestBody CreateTrainingTemplateRequest request) {
        return service.create(currentUserProvider.require(), toCommand(request));
    }

    @GetMapping("/{templateId}")
    public TrainingTemplateDetailView get(@PathVariable UUID templateId) {
        return service.get(currentUserProvider.require(), templateId);
    }

    @PatchMapping("/{templateId}")
    public TrainingTemplateDetailView update(
            @PathVariable UUID templateId,
            @RequestBody UpdateTrainingTemplateRequest request) {
        return service.update(
                currentUserProvider.require(), templateId, toCommand(request));
    }

    @DeleteMapping("/{templateId}")
    public ResponseEntity<Void> delete(@PathVariable UUID templateId) {
        service.delete(currentUserProvider.require(), templateId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/from-publication")
    public TrainingTemplateDetailView copy(
            @RequestBody CopyTrainingTemplateRequest request) {
        return service.copy(
                currentUserProvider.require(),
                new CopyTrainingTemplateCommand(request.publicationId(), request.name()));
    }

    @GetMapping("/{templateId}/task-config")
    public TaskTemplateConfigView taskConfig(@PathVariable UUID templateId) {
        return service.taskConfig(currentUserProvider.require(), templateId);
    }

    private static CreateTrainingTemplateCommand toCommand(
            CreateTrainingTemplateRequest request) {
        return new CreateTrainingTemplateCommand(
                request.ownerRole(),
                request.name(),
                request.trainingLevel(),
                request.topic(),
                request.textbookVersion(),
                seconds(request.duration(), true),
                seconds(request.duration(), false),
                request.weights(),
                toCommands(request.virtualStudents()),
                request.materialId());
    }

    private static UpdateTrainingTemplateCommand toCommand(
            UpdateTrainingTemplateRequest request) {
        return new UpdateTrainingTemplateCommand(
                request.name(),
                request.trainingLevel(),
                request.topic(),
                request.textbookVersion(),
                seconds(request.duration(), true),
                seconds(request.duration(), false),
                request.weights(),
                toCommands(request.virtualStudents()),
                request.materialId(),
                request.removeMaterial());
    }

    private static Integer seconds(DurationRequest duration, boolean recommended) {
        if (duration == null) {
            return null;
        }
        return recommended ? duration.recommendedSeconds() : duration.maxSeconds();
    }

    private static List<VirtualStudentCommand> toCommands(
            List<VirtualStudentRequest> students) {
        return students == null
                ? null
                : students.stream()
                        .map(PersonalTrainingTemplateController::toCommand)
                        .toList();
    }

    private static VirtualStudentCommand toCommand(VirtualStudentRequest student) {
        return new VirtualStudentCommand(
                student.name(),
                student.avatar(),
                student.personalityText(),
                student.activity(),
                student.questionRate(),
                student.ability(),
                student.attentionBaseline(),
                student.disciplineProbability(),
                student.seatNo(),
                student.sortOrder());
    }
}
