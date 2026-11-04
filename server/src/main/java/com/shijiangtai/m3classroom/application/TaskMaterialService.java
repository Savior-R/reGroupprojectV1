package com.shijiangtai.m3classroom.application;

import com.shijiangtai.m3classroom.application.view.MaterialView;
import com.shijiangtai.shared.application.CurrentUser;
import com.shijiangtai.shared.domain.TaskId;

public interface TaskMaterialService {
    MaterialView upload(
            CurrentUser teacher, TaskId taskId, String filename, String mime, byte[] content);

    MaterialView get(CurrentUser teacher, TaskId taskId);

    void delete(CurrentUser teacher, TaskId taskId);
}
