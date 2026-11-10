package com.shijiangtai.m6training.api;

import com.shijiangtai.m6training.application.TrainingMaterialService;
import com.shijiangtai.m6training.application.view.TrainingMaterialView;
import com.shijiangtai.shared.application.CurrentUserProvider;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** Free-training reference material (08 §10.3). */
@RestController
@RequestMapping("/api/v1/training/materials")
public class TrainingMaterialController {
    private final TrainingMaterialService service;
    private final CurrentUserProvider currentUserProvider;

    public TrainingMaterialController(
            TrainingMaterialService service, CurrentUserProvider currentUserProvider) {
        this.service = service;
        this.currentUserProvider = currentUserProvider;
    }

    @PostMapping
    public TrainingMaterialView upload(@RequestParam("file") MultipartFile file) {
        return service.upload(currentUserProvider.require(), file);
    }

    @DeleteMapping("/{materialId}")
    public ResponseEntity<Void> delete(@PathVariable UUID materialId) {
        service.delete(currentUserProvider.require(), materialId);
        return ResponseEntity.noContent().build();
    }
}
