package com.shijiangtai.m6training.application;

import com.shijiangtai.m6training.application.command.PublishTrainingTemplateCommand;
import com.shijiangtai.m6training.application.view.StudentTemplateCardView;
import com.shijiangtai.m6training.application.view.StudentTemplateDetailView;
import com.shijiangtai.m6training.application.view.TrainingPublicationView;
import com.shijiangtai.shared.application.CurrentUser;
import com.shijiangtai.shared.domain.PageResult;
import java.util.UUID;

/** Publishing teacher templates to courses and student visibility (08 §4.4–§4.6). */
public interface TrainingTemplatePublicationService {

    TrainingPublicationView publish(
            CurrentUser user, UUID templateId, PublishTrainingTemplateCommand command);

    void unpublish(CurrentUser user, UUID publicationId);

    PageResult<TrainingPublicationView> listMine(
            CurrentUser user, UUID templateId, UUID courseId, String status, int page, int size);

    PageResult<StudentTemplateCardView> listForStudent(
            CurrentUser user, String level, UUID courseId, int page, int size);

    StudentTemplateDetailView getForStudent(CurrentUser user, UUID publicationId);
}
