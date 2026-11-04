package com.shijiangtai.m3classroom.infrastructure.config;

import com.shijiangtai.m3classroom.application.port.AfterCommitPort;
import com.shijiangtai.m3classroom.domain.task.TaskPublishPolicy;
import com.shijiangtai.m3classroom.domain.task.TaskSnapshotFactory;
import com.shijiangtai.m3classroom.infrastructure.events.SpringAfterCommitHook;
import com.shijiangtai.m3classroom.infrastructure.events.SpringEventPublisher;
import com.shijiangtai.m3classroom.infrastructure.time.SystemClockProvider;
import com.shijiangtai.m3classroom.infrastructure.time.UuidIdGenerator;
import com.shijiangtai.shared.application.ClockProvider;
import com.shijiangtai.shared.application.EventPublisher;
import com.shijiangtai.shared.application.IdGenerator;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class M3Config {

    @Bean
    public TaskSnapshotFactory taskSnapshotFactory() {
        return new TaskSnapshotFactory();
    }

    @Bean
    public TaskPublishPolicy taskPublishPolicy() {
        return new TaskPublishPolicy();
    }

    @Bean
    public IdGenerator idGenerator() {
        return new UuidIdGenerator();
    }

    @Bean
    public ClockProvider clockProvider() {
        return new SystemClockProvider();
    }

    @Bean
    public EventPublisher eventPublisher(ApplicationEventPublisher publisher) {
        return new SpringEventPublisher(publisher);
    }

    @Bean
    public AfterCommitPort afterCommitPort() {
        return new SpringAfterCommitHook();
    }
}
