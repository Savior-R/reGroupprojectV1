package com.shijiangtai.m3classroom.application;

import com.shijiangtai.m3classroom.application.view.ReplayView;
import com.shijiangtai.m3classroom.application.view.SessionEventView;
import com.shijiangtai.m3classroom.application.view.SessionTextView;
import com.shijiangtai.shared.application.CurrentUser;
import com.shijiangtai.shared.domain.PageResult;
import com.shijiangtai.shared.domain.SessionId;
import java.util.List;

/** Read-only replay and context queries for one session (05B §5). */
public interface ReplayQueryService {

    /** Presigned replay manifest; {@code RECORDING_INCOMPLETE} until all required streams exist. */
    ReplayView replay(CurrentUser user, SessionId sessionId);

    /** Event log page after {@code afterSeq}; {@code limit} must be between 1 and 500. */
    PageResult<SessionEventView> events(
            CurrentUser user, SessionId sessionId, long afterSeq, int limit);

    /** Unified text stream, optionally filtered by speaker type. */
    List<SessionTextView> texts(CurrentUser user, SessionId sessionId, String speakerType);
}
