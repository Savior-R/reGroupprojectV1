package com.shijiangtai.m3classroom.persistence;

import com.shijiangtai.m3classroom.domain.task.TaskPublishedEvent;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.event.EventListener;

@TestConfiguration
public class PublishedEventRecorder {
    private final List<TaskPublishedEvent> events = new CopyOnWriteArrayList<>();

    @EventListener
    public void onTaskPublished(TaskPublishedEvent event) {
        events.add(event);
    }

    public List<TaskPublishedEvent> events() {
        return List.copyOf(events);
    }

    public void clear() {
        events.clear();
    }
}
