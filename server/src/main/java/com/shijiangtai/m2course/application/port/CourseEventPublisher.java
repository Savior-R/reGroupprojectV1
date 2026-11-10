package com.shijiangtai.m2course.application.port;

/**
 * 课程集成事件发布端口。
 *
 * <p>M2 不自建 shared {@code EventPublisher} 的 Bean（那是 M3 已注册的共享能力），
 * 通过本模块端口在事务提交后发布 {@code CourseArchivedEvent} / {@code CourseTeacherRemovedEvent}。
 */
public interface CourseEventPublisher {

    void publishAfterCommit(Object event);
}
