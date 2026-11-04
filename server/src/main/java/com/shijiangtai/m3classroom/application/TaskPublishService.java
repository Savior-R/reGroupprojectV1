package com.shijiangtai.m3classroom.application;

import com.shijiangtai.m3classroom.application.view.PublishResult;
import com.shijiangtai.shared.application.CurrentUser;
import com.shijiangtai.shared.domain.TaskId;

public interface TaskPublishService {
    PublishResult publish(CurrentUser teacher, TaskId taskId);

    void close(CurrentUser teacher, TaskId taskId);
}
