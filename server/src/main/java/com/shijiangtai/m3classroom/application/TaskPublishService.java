package com.shijiangtai.m3classroom.application;

import com.shijiangtai.m3classroom.application.command.CreateTaskCommand;
import com.shijiangtai.m3classroom.application.command.MaterialUpload;
import com.shijiangtai.m3classroom.application.command.UpdateTaskCommand;
import com.shijiangtai.m3classroom.application.view.TaskView;
import com.shijiangtai.shared.application.CurrentUser;
import com.shijiangtai.shared.domain.TaskId;

public interface TaskPublishService {
    TaskView createAndPublish(CurrentUser teacher, CreateTaskCommand command, MaterialUpload material);

    TaskView updateAndPublish(
            CurrentUser teacher, TaskId taskId, UpdateTaskCommand command, MaterialUpload material);

    TaskView publish(CurrentUser teacher, TaskId taskId);

    /** Test/legacy preparation hook; not exposed by TaskController. */
    TaskView createPrepared(CurrentUser teacher, CreateTaskCommand command);

    void close(CurrentUser teacher, TaskId taskId);
}
