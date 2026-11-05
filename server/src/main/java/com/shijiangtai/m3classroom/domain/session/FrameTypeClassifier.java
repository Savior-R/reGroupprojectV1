package com.shijiangtai.m3classroom.domain.session;

import java.math.BigDecimal;

public final class FrameTypeClassifier {
    public static final BigDecimal LOW_CONFIDENCE_THRESHOLD = new BigDecimal("0.60");
    public static final BigDecimal SLIDE_CHANGE_THRESHOLD = new BigDecimal("0.125");

    public FrameType classify(BigDecimal confidence, BigDecimal changeScore) {
        if (confidence != null && confidence.compareTo(LOW_CONFIDENCE_THRESHOLD) < 0) {
            return FrameType.LOW_CONFIDENCE;
        }
        if (changeScore != null && changeScore.compareTo(SLIDE_CHANGE_THRESHOLD) >= 0) {
            return FrameType.SLIDE_CHANGE;
        }
        return FrameType.NORMAL;
    }
}
