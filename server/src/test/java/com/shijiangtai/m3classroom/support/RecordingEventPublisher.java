package com.shijiangtai.m3classroom.support;

import com.shijiangtai.shared.application.EventPublisher;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public class RecordingEventPublisher implements EventPublisher {
    private final List<Object> events = new CopyOnWriteArrayList<>();

    @Override
    public void publish(Object event) {
        events.add(event);
    }

    public List<Object> events() {
        return List.copyOf(events);
    }

    public void clear() {
        events.clear();
    }
}
