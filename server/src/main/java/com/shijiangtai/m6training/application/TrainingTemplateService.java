package com.shijiangtai.m6training.application;

import com.shijiangtai.m6training.application.command.PublishTrainingTemplateCommand;
import com.shijiangtai.m6training.application.view.StudentTemplateCardView;
import com.shijiangtai.m6training.application.view.StudentTemplateDetailView;
import com.shijiangtai.m6training.application.view.TrainingTemplateView;
import com.shijiangtai.shared.application.CurrentUser;
import com.shijiangtai.shared.domain.PageResult;
import java.util.UUID;

public interface TrainingTemplateService {

    TrainingTemplateView publish(CurrentUser user, PublishTrainingTemplateCommand command);

    PageResult<TrainingTemplateView> listMine(
            CurrentUser user, String level, UUID courseId, int page, int size);

    void unpublish(CurrentUser user, UUID templateId);

    PageResult<StudentTemplateCardView> listForStudent(
            CurrentUser user, String level, UUID courseId, int page, int size);

    StudentTemplateDetailView getForStudent(CurrentUser user, UUID templateId);
}
