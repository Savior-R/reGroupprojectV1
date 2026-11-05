package com.shijiangtai.m3classroom.api;

import com.shijiangtai.m3classroom.api.dto.PerceptionScreenResponse;
import com.shijiangtai.m3classroom.application.PerceptionApplicationService;
import com.shijiangtai.m3classroom.application.view.PerceptionFrameView;
import com.shijiangtai.shared.application.CurrentUserProvider;
import com.shijiangtai.shared.domain.DomainException;
import com.shijiangtai.shared.domain.ErrorCode;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** Thin REST adapter for periodic screen screenshots (02 §4.3, 05A §6.2). */
@RestController
@RequestMapping("/api/v1/sessions/{sessionId}/perception")
public class PerceptionScreenController {

    private final PerceptionApplicationService perceptionApplicationService;
    private final SessionRequestMapper requestMapper;
    private final CurrentUserProvider currentUserProvider;

    public PerceptionScreenController(
            PerceptionApplicationService perceptionApplicationService,
            SessionRequestMapper requestMapper,
            CurrentUserProvider currentUserProvider) {
        this.perceptionApplicationService = perceptionApplicationService;
        this.requestMapper = requestMapper;
        this.currentUserProvider = currentUserProvider;
    }

    @PostMapping(value = "/screen", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<PerceptionScreenResponse> screen(
            @PathVariable UUID sessionId,
            @RequestPart("file") MultipartFile file,
            @RequestParam("at_ms") long atMs,
            @RequestParam(value = "change_score", required = false) BigDecimal changeScore) {
        byte[] content;
        try {
            content = file.getBytes();
        } catch (IOException exception) {
            throw new DomainException(ErrorCode.VALIDATION_ERROR, "截图读取失败");
        }
        PerceptionFrameView frame = perceptionApplicationService.ingestScreen(
                currentUserProvider.require(),
                requestMapper.toSessionId(sessionId),
                content,
                file.getContentType(),
                atMs,
                changeScore);
        return ResponseEntity.accepted()
                .body(new PerceptionScreenResponse(frame.frameId(), frame.status()));
    }
}
