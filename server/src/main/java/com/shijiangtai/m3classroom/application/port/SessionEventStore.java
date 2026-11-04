package com.shijiangtai.m3classroom.application.port;

import com.shijiangtai.m3classroom.domain.session.SessionEvent;
import com.shijiangtai.shared.domain.SessionId;
import java.util.List;

/** Durable append-only event log (05A §6.2, §9.2). */
public interface SessionEventStore {

    void append(SessionEvent event);

    List<SessionEvent> after(SessionId id, long seq, int limit);

    long maxSeq(SessionId id);
}
