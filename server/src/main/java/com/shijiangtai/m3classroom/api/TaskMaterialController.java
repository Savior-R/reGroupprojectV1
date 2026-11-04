package com.shijiangtai.m3classroom.api;

import com.shijiangtai.m3classroom.application.TaskMaterialService;
import com.shijiangtai.m3classroom.application.view.MaterialView;
import com.shijiangtai.shared.application.CurrentUserProvider;
import com.shijiangtai.shared.domain.DomainException;
import com.shijiangtai.shared.domain.ErrorCode;
import com.shijiangtai.shared.domain.TaskId;
import java.io.IOException;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1")
public class TaskMaterialController {
    private final TaskMaterialService materialService;
    private final CurrentUserProvider currentUserProvider;

    public TaskMaterialController(
            TaskMaterialService materialService, CurrentUserProvider currentUserProvider) {
        this.materialService = materialService;
        this.currentUserProvider = currentUserProvider;
    }

    @PostMapping(value = "/tasks/{taskId}/material",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public MaterialView upload(
            @PathVariable UUID taskId, @RequestPart("file") MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new DomainException(ErrorCode.VALIDATION_ERROR, "参考材料文件为空");
        }
        byte[] content;
        try {
            content = file.getBytes();
        } catch (IOException exception) {
            throw new DomainException(ErrorCode.INTERNAL_ERROR, "读取参考材料失败");
        }
        return materialService.upload(
                currentUserProvider.require(),
                new TaskId(taskId),
                file.getOriginalFilename(),
                file.getContentType(),
                content);
    }

    @GetMapping("/tasks/{taskId}/material")
    public MaterialView get(@PathVariable UUID taskId) {
        return materialService.get(currentUserProvider.require(), new TaskId(taskId));
    }

    @DeleteMapping("/tasks/{taskId}/material")
    public ResponseEntity<Void> delete(@PathVariable UUID taskId) {
        materialService.delete(currentUserProvider.require(), new TaskId(taskId));
        return ResponseEntity.noContent().build();
    }
}
