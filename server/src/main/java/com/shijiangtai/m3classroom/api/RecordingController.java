package com.shijiangtai.m3classroom.api;

import com.shijiangtai.m3classroom.application.RecordingApplicationService;
import com.shijiangtai.m3classroom.application.view.MultipartUploadView;
import com.shijiangtai.m3classroom.application.view.PartUrlView;
import com.shijiangtai.m3classroom.application.view.RecordingStreamView;
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

/**
 * Thin REST adapter for recording multipart orchestration (02 §4.3 / 05B §4). Media bytes go
 * directly to MinIO through the presigned URLs returned here.
 */
@RestController
@RequestMapping("/api/v1")
public class RecordingController {

    private final RecordingApplicationService recordingApplicationService;
    private final RecordingRequestMapper requestMapper;
    private final CurrentUserProvider currentUserProvider;

    public RecordingController(
            RecordingApplicationService recordingApplicationService,
            RecordingRequestMapper requestMapper,
            CurrentUserProvider currentUserProvider) {
        this.recordingApplicationService = recordingApplicationService;
        this.requestMapper = requestMapper;
        this.currentUserProvider = currentUserProvider;
    }

    @PostMapping("/sessions/{sessionId}/recordings/{streamType}/multipart/start")
    public MultipartUploadView start(
            @PathVariable UUID sessionId, @PathVariable String streamType) {
        return recordingApplicationService.start(
                currentUserProvider.require(),
                requestMapper.toSessionId(sessionId),
                requestMapper.toStreamType(streamType));
    }

    @PostMapping("/sessions/{sessionId}/recordings/{streamType}/multipart/part-url")
    public PartUrlView partUrl(
            @PathVariable UUID sessionId,
            @PathVariable String streamType,
            @RequestBody RecordingRequestMapper.PartUrlRequest request) {
        return recordingApplicationService.partUrl(
                currentUserProvider.require(),
                requestMapper.toSessionId(sessionId),
                requestMapper.toStreamType(streamType),
                request.partNumber());
    }

    @PostMapping("/sessions/{sessionId}/recordings/{streamType}/multipart/complete")
    public RecordingStreamView complete(
            @PathVariable UUID sessionId,
            @PathVariable String streamType,
            @RequestBody RecordingRequestMapper.CompleteRequest request) {
        return recordingApplicationService.complete(
                currentUserProvider.require(),
                requestMapper.toSessionId(sessionId),
                requestMapper.toStreamType(streamType),
                requestMapper.toCompleteCommand(request));
    }

    @PostMapping("/sessions/{sessionId}/recordings/{streamType}/multipart/abort")
    public ResponseEntity<Void> abort(
            @PathVariable UUID sessionId, @PathVariable String streamType) {
        recordingApplicationService.abort(
                currentUserProvider.require(),
                requestMapper.toSessionId(sessionId),
                requestMapper.toStreamType(streamType));
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/sessions/{sessionId}/recordings")
    public List<RecordingStreamView> list(@PathVariable UUID sessionId) {
        return recordingApplicationService.list(
                currentUserProvider.require(),
                requestMapper.toSessionId(sessionId));
    }
}
