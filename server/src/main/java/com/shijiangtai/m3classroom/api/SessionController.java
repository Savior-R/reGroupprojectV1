package com.shijiangtai.m3classroom.api;

import com.shijiangtai.m3classroom.application.SessionApplicationService;
import com.shijiangtai.m3classroom.application.view.SessionView;
import com.shijiangtai.shared.application.CurrentUserProvider;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Thin REST adapter for the session lifecycle (02 §4.3). The WebSocket control channel is the
 * primary start/end path; these endpoints are the idempotent REST fallback.
 */
@RestController
@RequestMapping("/api/v1")
public class SessionController {

    private final SessionApplicationService sessionApplicationService;
    private final SessionRequestMapper requestMapper;
    private final CurrentUserProvider currentUserProvider;

    public SessionController(
            SessionApplicationService sessionApplicationService,
            SessionRequestMapper requestMapper,
            CurrentUserProvider currentUserProvider) {
        this.sessionApplicationService = sessionApplicationService;
        this.requestMapper = requestMapper;
        this.currentUserProvider = currentUserProvider;
    }

    @PostMapping("/tasks/{taskId}/sessions")
    public SessionView create(@PathVariable UUID taskId) {
        return sessionApplicationService.createSession(
                currentUserProvider.require(), requestMapper.toTaskId(taskId));
    }

    @GetMapping("/sessions/{sessionId}")
    public SessionView get(@PathVariable UUID sessionId) {
        return sessionApplicationService.get(
                currentUserProvider.require(), requestMapper.toSessionId(sessionId));
    }

    @PostMapping("/sessions/{sessionId}/start")
    public SessionView start(@PathVariable UUID sessionId) {
        return sessionApplicationService.start(
                currentUserProvider.require(), requestMapper.toSessionId(sessionId));
    }

    @PostMapping("/sessions/{sessionId}/end")
    public SessionView end(@PathVariable UUID sessionId) {
        return sessionApplicationService.end(
                currentUserProvider.require(), requestMapper.toSessionId(sessionId));
    }

    @PostMapping("/sessions/{sessionId}/cancel")
    public ResponseEntity<Void> cancel(@PathVariable UUID sessionId) {
        sessionApplicationService.cancel(
                currentUserProvider.require(), requestMapper.toSessionId(sessionId));
        return ResponseEntity.noContent().build();
    }
}
