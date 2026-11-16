package com.shijiangtai.m7teacher.api;

import com.shijiangtai.m7teacher.api.dto.ClearScoresRequest;
import com.shijiangtai.m7teacher.api.dto.UpdateGradingConfigRequest;
import com.shijiangtai.m7teacher.application.GradingConfigService;
import com.shijiangtai.m7teacher.application.view.ClearScoresView;
import com.shijiangtai.m7teacher.application.view.GradingConfigView;
import com.shijiangtai.shared.application.CurrentUserProvider;
import java.util.UUID;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class GradingConfigController {

    private final GradingConfigService configService;
    private final M7RequestMapper requestMapper;
    private final CurrentUserProvider currentUserProvider;

    public GradingConfigController(
            GradingConfigService configService,
            M7RequestMapper requestMapper,
            CurrentUserProvider currentUserProvider) {
        this.configService = configService;
        this.requestMapper = requestMapper;
        this.currentUserProvider = currentUserProvider;
    }

    @GetMapping("/tasks/{taskId}/grading-config")
    public GradingConfigView get(@PathVariable UUID taskId) {
        return configService.get(currentUserProvider.require(), taskId);
    }

    @PutMapping("/tasks/{taskId}/grading-config")
    public GradingConfigView update(
            @PathVariable UUID taskId,
            @RequestBody UpdateGradingConfigRequest request) {
        return configService.update(
                currentUserProvider.require(), taskId, requestMapper.toCommand(request));
    }

    @DeleteMapping("/tasks/{taskId}/teacher-scores")
    public ClearScoresView clear(
            @PathVariable UUID taskId,
            @RequestBody(required = false) ClearScoresRequest request) {
        boolean confirm = request != null && Boolean.TRUE.equals(request.confirm());
        return configService.clearScores(currentUserProvider.require(), taskId, confirm);
    }
}
