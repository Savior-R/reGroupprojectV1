package com.shijiangtai.m3classroom.domain.session;

public enum FrameStatus {
    PENDING("pending"),
    PROCESSED("processed"),
    FAILED("failed");

    private final String wireValue;

    FrameStatus(String wireValue) {
        this.wireValue = wireValue;
    }

    public String wireValue() {
        return wireValue;
    }
}
