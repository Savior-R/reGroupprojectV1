package com.shijiangtai.m3classroom.application;

import com.shijiangtai.m3classroom.application.command.CompletePartCommand;
import com.shijiangtai.m3classroom.application.view.MultipartUploadView;
import com.shijiangtai.m3classroom.application.view.PartUrlView;
import com.shijiangtai.m3classroom.application.view.RecordingStreamView;
import com.shijiangtai.m3classroom.domain.recording.StreamType;
import com.shijiangtai.shared.application.CurrentUser;
import com.shijiangtai.shared.domain.SessionId;
import java.util.List;

/** Multipart orchestration for the sessions' camera/screen/audio streams (05B §4). */
public interface RecordingApplicationService {

    MultipartUploadView start(CurrentUser student, SessionId sessionId, StreamType type);

    PartUrlView partUrl(
            CurrentUser student, SessionId sessionId, StreamType type, int partNumber);

    RecordingStreamView complete(
            CurrentUser student, SessionId sessionId, StreamType type,
            CompletePartCommand command);

    void abort(CurrentUser student, SessionId sessionId, StreamType type);

    List<RecordingStreamView> list(CurrentUser user, SessionId sessionId);
}
