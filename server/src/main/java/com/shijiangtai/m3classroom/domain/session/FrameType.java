package com.shijiangtai.m3classroom.domain.session;

public enum FrameType {
    NORMAL("normal"),
    SLIDE_CHANGE("slide_change"),
    LOW_CONFIDENCE("low_confidence"),
    FINAL("final"),
    CHART("chart");

    private final String wireValue;

    FrameType(String wireValue) {
        this.wireValue = wireValue;
    }

    public String wireValue() {
        return wireValue;
    }
}
