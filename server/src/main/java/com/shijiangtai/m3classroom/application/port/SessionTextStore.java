package com.shijiangtai.m3classroom.application.port;

import com.shijiangtai.m3classroom.domain.session.SessionText;
import com.shijiangtai.shared.domain.SessionId;
import java.util.List;

/** Durable unified text stream (05A §7.5, 01 §"session_texts"). */
public interface SessionTextStore {

    void save(SessionText text);

    List<SessionText> bySession(SessionId id, String speakerType);
}
