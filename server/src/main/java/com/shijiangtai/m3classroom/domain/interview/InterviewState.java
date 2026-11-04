package com.shijiangtai.m3classroom.domain.interview;

/**
 * Internal state of a structured-interview session (05C §6.1).
 *
 * <p>The outer session state ({@code preparing / in_progress / ended}) is owned by the session
 * layer; this enum only tracks whether the interview has not started, is presenting a question
 * with an open answer window, or has ended.
 */
public enum InterviewState {
    NOT_STARTED("not_started"),
    PRESENTING("presenting"),
    ENDED("ended");

    private final String wireValue;

    InterviewState(String wireValue) {
        this.wireValue = wireValue;
    }

    public String wireValue() {
        return wireValue;
    }
}
