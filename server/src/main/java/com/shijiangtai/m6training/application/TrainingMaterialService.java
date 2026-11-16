package com.shijiangtai.m6training.application;

import com.shijiangtai.m6training.application.view.TrainingMaterialView;
import com.shijiangtai.shared.application.CurrentUser;
import java.util.UUID;
import org.springframework.web.multipart.MultipartFile;

public interface TrainingMaterialService {

    TrainingMaterialView upload(CurrentUser user, MultipartFile file);

    void delete(CurrentUser user, UUID materialId);
}
