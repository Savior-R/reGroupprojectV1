package com.shijiangtai.m7teacher.api;

import com.shijiangtai.m7teacher.application.CsvExportService;
import com.shijiangtai.shared.application.ClockProvider;
import com.shijiangtai.shared.application.CurrentUserProvider;
import java.util.UUID;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class ExportController {

    private final CsvExportService csvExportService;
    private final CurrentUserProvider currentUserProvider;
    private final ClockProvider clockProvider;

    public ExportController(
            CsvExportService csvExportService,
            CurrentUserProvider currentUserProvider,
            ClockProvider clockProvider) {
        this.csvExportService = csvExportService;
        this.currentUserProvider = currentUserProvider;
        this.clockProvider = clockProvider;
    }

    @GetMapping("/tasks/{taskId}/export")
    public ResponseEntity<byte[]> export(
            @PathVariable UUID taskId,
            @RequestParam(name = "class_id", required = false) UUID classId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String keyword) {
        byte[] body = csvExportService.export(
                currentUserProvider.require(), taskId, classId, status, keyword);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(new MediaType("text", "csv", java.nio.charset.StandardCharsets.UTF_8));
        headers.setContentDisposition(ContentDisposition.attachment()
                .filename(CsvExportService.fileName(taskId, clockProvider.now()))
                .build());
        headers.setContentLength(body.length);
        return ResponseEntity.ok().headers(headers).body(body);
    }
}
