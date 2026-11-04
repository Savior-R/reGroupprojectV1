package com.shijiangtai.m3classroom.support;

import com.shijiangtai.m3classroom.application.port.SessionTextStore;
import com.shijiangtai.m3classroom.domain.session.SessionText;
import com.shijiangtai.shared.domain.SessionId;
import java.util.ArrayList;
import java.util.List;

/** In-memory unified text store used by unit tests. */
public final class InMemorySessionTextStore implements SessionTextStore {

    private final List<SessionText> texts = new ArrayList<>();

    @Override
    public synchronized void save(SessionText text) {
        texts.add(text);
    }

    @Override
    public synchronized List<SessionText> bySession(SessionId id, String speakerType) {
        return texts.stream()
                .filter(text -> text.sessionId().equals(id))
                .filter(text -> speakerType == null || speakerType.equals(text.speakerType()))
                .toList();
    }

    public synchronized List<SessionText> all() {
        return List.copyOf(texts);
    }

    public synchronized int size() {
        return texts.size();
    }
}
