package com.shijiangtai.m2course.infrastructure.events;

import com.shijiangtai.m2course.application.port.CourseEventPublisher;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 事务提交后发布课程集成事件。
 *
 * <p>事件只带 ID，不带聚合；发布失败不影响已提交事务（仅记录日志由监听方负责）。
 */
@Component
public class SpringCourseEventPublisher implements CourseEventPublisher {
    private final ApplicationEventPublisher publisher;

    public SpringCourseEventPublisher(ApplicationEventPublisher publisher) {
        this.publisher = publisher;
    }

    @Override
    public void publishAfterCommit(Object event) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            publisher.publishEvent(event);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                publisher.publishEvent(event);
            }
        });
    }
}
