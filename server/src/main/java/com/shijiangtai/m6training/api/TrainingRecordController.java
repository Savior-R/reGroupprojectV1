package com.shijiangtai.m6training.api;

import com.shijiangtai.m6training.api.dto.SelectTrainingRecordRequest;
import com.shijiangtai.m6training.application.TrainingRecordService;
import com.shijiangtai.m6training.application.command.SelectTrainingRecordCommand;
import com.shijiangtai.m6training.application.view.TrainingRecordSelectionView;
import com.shijiangtai.m6training.application.view.TrainingRecordView;
import com.shijiangtai.m6training.application.view.TrainingReportView;
import com.shijiangtai.shared.application.CurrentUserProvider;
import com.shijiangtai.shared.domain.PageResult;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Retained training records and the self-only report (08 §10.5–§10.6). */
@RestController
@RequestMapping("/api/v1/training/records")
public class TrainingRecordController {
    private final TrainingRecordService service;
    private final CurrentUserProvider currentUserProvider;

    public TrainingRecordController(
            TrainingRecordService service, CurrentUserProvider currentUserProvider) {
        this.service = service;
        this.currentUserProvider = currentUserProvider;
    }

    @PostMapping
    public TrainingRecordSelectionView select(
            @RequestBody SelectTrainingRecordRequest request) {
        return service.select(
                currentUserProvider.require(),
                new SelectTrainingRecordCommand(request.sessionId()));
    }

    @GetMapping
    public PageResult<TrainingRecordView> list(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return service.list(currentUserProvider.require(), status, page, size);
    }

    @GetMapping("/{recordId}")
    public TrainingRecordView get(@PathVariable UUID recordId) {
        return service.get(currentUserProvider.require(), recordId);
    }

    @DeleteMapping("/{recordId}")
    public ResponseEntity<Void> delete(@PathVariable UUID recordId) {
        service.delete(currentUserProvider.require(), recordId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{recordId}/evaluation")
    public TrainingReportView evaluation(@PathVariable UUID recordId) {
        return service.getEvaluation(currentUserProvider.require(), recordId);
    }
}
