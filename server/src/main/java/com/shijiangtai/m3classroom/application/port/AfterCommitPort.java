package com.shijiangtai.m3classroom.application.port;

public interface AfterCommitPort {
    void afterCommit(Runnable action);
}
