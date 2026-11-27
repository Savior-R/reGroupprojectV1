package com.shijiangtai.m6training.application;

import com.shijiangtai.m6training.application.command.CopyTrainingTemplateCommand;
import com.shijiangtai.m6training.application.command.CreateTrainingTemplateCommand;
import com.shijiangtai.m6training.application.command.UpdateTrainingTemplateCommand;
import com.shijiangtai.m6training.application.view.TaskTemplateConfigView;
import com.shijiangtai.m6training.application.view.TrainingTemplateDetailView;
import com.shijiangtai.m6training.application.view.TrainingTemplateSummaryView;
import com.shijiangtai.shared.application.CurrentUser;
import com.shijiangtai.shared.domain.PageResult;
import java.util.UUID;

/** Personal template library for both teachers and students (08 §4). */
public interface TrainingTemplateService {

    TrainingTemplateDetailView create(
            CurrentUser user, CreateTrainingTemplateCommand command);

    TrainingTemplateDetailView copy(
            CurrentUser user, CopyTrainingTemplateCommand command);

    PageResult<TrainingTemplateSummaryView> list(
            CurrentUser user, String role, String level, String status, int page, int size);

    TrainingTemplateDetailView get(CurrentUser user, UUID templateId);

    TrainingTemplateDetailView update(
            CurrentUser user, UUID templateId, UpdateTrainingTemplateCommand command);

    void delete(CurrentUser user, UUID templateId);

    TaskTemplateConfigView taskConfig(CurrentUser user, UUID templateId);
}
