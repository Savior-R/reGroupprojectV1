package com.shijiangtai.m6training.application;

import com.shijiangtai.m6training.application.command.SelectTrainingRecordCommand;
import com.shijiangtai.m6training.application.view.TrainingRecordSelectionView;
import com.shijiangtai.m6training.application.view.TrainingRecordView;
import com.shijiangtai.m6training.application.view.TrainingReportView;
import com.shijiangtai.shared.application.CurrentUser;
import com.shijiangtai.shared.domain.PageResult;
import java.util.UUID;

public interface TrainingRecordService {

    TrainingRecordSelectionView select(
            CurrentUser user, SelectTrainingRecordCommand command);

    PageResult<TrainingRecordView> list(
            CurrentUser user, String status, int page, int size);

    TrainingRecordView get(CurrentUser user, UUID recordId);

    void delete(CurrentUser user, UUID recordId);

    TrainingReportView getEvaluation(CurrentUser user, UUID recordId);
}
