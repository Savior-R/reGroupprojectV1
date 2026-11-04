package com.shijiangtai.m3classroom.application.command;

import com.shijiangtai.m3classroom.domain.task.TaskMode;
import com.shijiangtai.m3classroom.domain.task.TaskStatus;
import com.shijiangtai.shared.domain.DomainException;
import com.shijiangtai.shared.domain.ErrorCode;
import java.util.Map;

public record TaskQuery(int page, int size, TaskStatus status, TaskMode mode) {
    public TaskQuery {
        if (page < 1 || size < 1 || size > 100) {
            throw new DomainException(ErrorCode.VALIDATION_ERROR,
                    "分页参数非法", Map.of("page", page, "size", size));
        }
    }
}
