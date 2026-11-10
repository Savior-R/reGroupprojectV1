package com.shijiangtai.m6training.api;

import com.shijiangtai.m6training.api.dto.CreateTrainingSessionRequest;
import com.shijiangtai.m6training.api.dto.VirtualStudentRequest;
import com.shijiangtai.m6training.application.TrainingSessionService;
import com.shijiangtai.m6training.application.command.CreateTrainingSessionCommand;
import com.shijiangtai.m6training.application.command.VirtualStudentCommand;
import com.shijiangtai.m6training.application.view.TrainingSessionView;
import com.shijiangtai.shared.application.CurrentUserProvider;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Training session lifecycle (08 §10.4). */
@RestController
@RequestMapping("/api/v1/training/sessions")
public class TrainingSessionController {
    private final TrainingSessionService service;
    private final CurrentUserProvider currentUserProvider;

    public TrainingSessionController(
            TrainingSessionService service, CurrentUserProvider currentUserProvider) {
        this.service = service;
        this.currentUserProvider = currentUserProvider;
    }

    @PostMapping
    public TrainingSessionView create(@RequestBody CreateTrainingSessionRequest request) {
        return service.create(
                currentUserProvider.require(), toCommand(request));
    }

    @GetMapping("/{sessionId}")
    public TrainingSessionView get(@PathVariable UUID sessionId) {
        return service.get(currentUserProvider.require(), sessionId);
    }

    @PostMapping("/{sessionId}/end")
    public TrainingSessionView end(@PathVariable UUID sessionId) {
        return service.end(currentUserProvider.require(), sessionId);
    }

    @PostMapping("/{sessionId}/cancel")
    public ResponseEntity<Void> cancel(@PathVariable UUID sessionId) {
        service.cancel(currentUserProvider.require(), sessionId);
        return ResponseEntity.noContent().build();
    }

    private static CreateTrainingSessionCommand toCommand(CreateTrainingSessionRequest request) {
        List<VirtualStudentCommand> students = request.virtualStudents() == null
                ? List.of()
                : request.virtualStudents().stream()
                        .map(TrainingSessionController::toCommand)
                        .toList();
        return new CreateTrainingSessionCommand(
                request.templateId(),
                request.trainingLevel(),
                request.courseId(),
                request.topic(),
                students,
                request.materialId());
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
