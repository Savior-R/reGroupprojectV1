package com.shijiangtai.m4eval.application.port;

import java.util.function.Supplier;

public interface AfterCommitPort {
    void afterCommit(Runnable action);

    <T> T afterCommit(Supplier<T> action);
}
