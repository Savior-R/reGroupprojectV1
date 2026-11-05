package com.shijiangtai.m3classroom.api;

import com.shijiangtai.m3classroom.application.ReplayQueryService;
import com.shijiangtai.m3classroom.application.view.ReplayView;
import com.shijiangtai.m3classroom.application.view.SessionEventView;
import com.shijiangtai.m3classroom.application.view.SessionTextView;
import com.shijiangtai.shared.application.CurrentUserProvider;
import com.shijiangtai.shared.domain.PageResult;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Thin REST adapter for replay and context queries (05B §5). Media is served by presigned MinIO
 * URLs; this controller never streams bytes.
 */
@RestController
@RequestMapping("/api/v1")
public class ReplayController {

    private final ReplayQueryService replayQueryService;
    private final ReplayRequestMapper requestMapper;
    private final CurrentUserProvider currentUserProvider;

    public ReplayController(
            ReplayQueryService replayQueryService,
            ReplayRequestMapper requestMapper,
            CurrentUserProvider currentUserProvider) {
        this.replayQueryService = replayQueryService;
        this.requestMapper = requestMapper;
        this.currentUserProvider = currentUserProvider;
    }

    @GetMapping("/sessions/{sessionId}/replay")
    public ReplayView replay(@PathVariable UUID sessionId) {
        return replayQueryService.replay(
                currentUserProvider.require(), requestMapper.toSessionId(sessionId));
    }

    @GetMapping("/sessions/{sessionId}/events")
    public PageResult<SessionEventView> events(
            @PathVariable UUID sessionId,
            @RequestParam(name = "after_seq", defaultValue = "0") long afterSeq,
            @RequestParam(name = "limit", defaultValue = "100") int limit) {
        return replayQueryService.events(
                currentUserProvider.require(), requestMapper.toSessionId(sessionId),
                afterSeq, limit);
    }

    @GetMapping("/sessions/{sessionId}/texts")
    public List<SessionTextView> texts(
            @PathVariable UUID sessionId,
            @RequestParam(name = "speaker_type", required = false) String speakerType) {
        return replayQueryService.texts(
                currentUserProvider.require(), requestMapper.toSessionId(sessionId),
                speakerType);
    }
}
