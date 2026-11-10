package com.shijiangtai.m7teacher.application;

import com.shijiangtai.m7teacher.application.port.UpstreamQueryPort;

/** A submission plus its task context and the viewer's role for that submission. */
public record SubmissionAccess(
        UpstreamQueryPort.Submission submission,
        TaskContext taskContext,
        boolean teacherViewer) {
}
