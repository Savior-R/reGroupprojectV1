package com.shijiangtai.m7teacher.api;

import com.shijiangtai.m7teacher.api.dto.BatchSaveRequest;
import com.shijiangtai.m7teacher.api.dto.BatchSaveResponse;
import com.shijiangtai.m7teacher.api.dto.SaveTeacherScoreRequest;
import com.shijiangtai.m7teacher.api.dto.TeacherScoreSaveResponse;
import com.shijiangtai.m7teacher.application.TeacherScoreBatchService;
import com.shijiangtai.m7teacher.application.TeacherScoreService;
import com.shijiangtai.shared.application.CurrentUserProvider;
import java.util.UUID;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class TeacherScoreController {

    private final TeacherScoreService teacherScoreService;
    private final TeacherScoreBatchService batchService;
    private final M7RequestMapper requestMapper;
    private final CurrentUserProvider currentUserProvider;

    public TeacherScoreController(
            TeacherScoreService teacherScoreService,
            TeacherScoreBatchService batchService,
            M7RequestMapper requestMapper,
            CurrentUserProvider currentUserProvider) {
        this.teacherScoreService = teacherScoreService;
        this.batchService = batchService;
        this.requestMapper = requestMapper;
        this.currentUserProvider = currentUserProvider;
    }

    @PutMapping("/submissions/{submissionId}/teacher-score")
    public TeacherScoreSaveResponse save(
            @PathVariable UUID submissionId,
            @RequestBody SaveTeacherScoreRequest request) {
        return TeacherScoreSaveResponse.from(teacherScoreService.save(
                currentUserProvider.require(), submissionId,
                requestMapper.toCommand(submissionId, request)));
    }

    @PostMapping("/tasks/{taskId}/teacher-scores/batch")
    public BatchSaveResponse batch(
            @PathVariable UUID taskId, @RequestBody BatchSaveRequest request) {
        return BatchSaveResponse.from(batchService.save(
                currentUserProvider.require(), taskId, requestMapper.toCommands(request)));
    }
}
