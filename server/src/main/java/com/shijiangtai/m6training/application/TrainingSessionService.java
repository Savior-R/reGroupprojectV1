package com.shijiangtai.m6training.application;

import com.shijiangtai.m6training.application.command.CreateTrainingSessionCommand;
import com.shijiangtai.m6training.application.view.TrainingSessionView;
import com.shijiangtai.shared.application.CurrentUser;
import java.util.UUID;

public interface TrainingSessionService {

    TrainingSessionView create(CurrentUser user, CreateTrainingSessionCommand command);

    TrainingSessionView get(CurrentUser user, UUID sessionId);

    TrainingSessionView end(CurrentUser user, UUID sessionId);

    void cancel(CurrentUser user, UUID sessionId);
}
