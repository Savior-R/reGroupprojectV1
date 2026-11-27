package com.shijiangtai.m6training.application;

import com.shijiangtai.m6training.application.view.TrainingMaterialView;
import com.shijiangtai.shared.application.CurrentUser;
import java.util.UUID;
import org.springframework.web.multipart.MultipartFile;

/** Reference-material upload for free sessions and personal templates (08 §10.4). */
public interface TrainingMaterialService {

    TrainingMaterialView uploadFree(CurrentUser user, MultipartFile file);

    TrainingMaterialView uploadTemplate(CurrentUser user, MultipartFile file);

    void delete(CurrentUser user, UUID materialId);
}
