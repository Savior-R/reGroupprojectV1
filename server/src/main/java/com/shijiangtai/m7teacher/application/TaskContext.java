package com.shijiangtai.m7teacher.application;

import com.shijiangtai.m7teacher.application.port.UpstreamQueryPort;
import com.shijiangtai.m7teacher.domain.Rubric;

/** Task, course and effective rubric validated for one M7 operation. */
public record TaskContext(
        UpstreamQueryPort.Course course,
        UpstreamQueryPort.Task task,
        Rubric rubric) {
}
