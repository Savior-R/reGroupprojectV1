package com.shijiangtai.m3classroom.application;

import com.shijiangtai.m3classroom.application.view.SessionView;
import com.shijiangtai.m3classroom.application.view.SubmissionView;
import com.shijiangtai.shared.application.CurrentUser;
import com.shijiangtai.shared.domain.SessionId;
import com.shijiangtai.shared.domain.TaskId;

/** Session lifecycle use cases (02 §4.3). */
public interface SessionApplicationService {

    SessionView createSession(CurrentUser student, TaskId taskId);

    SessionView get(CurrentUser user, SessionId sessionId);

    SessionView start(CurrentUser student, SessionId sessionId);

    SessionView end(CurrentUser student, SessionId sessionId);

    void cancel(CurrentUser student, SessionId sessionId);

    SubmissionView submit(CurrentUser student, SessionId sessionId);
}
