package com.shijiangtai.m3classroom.domain.session;

import java.math.BigDecimal;

public record OcrBox(
        String text,
        double x1,
        double y1,
        double x2,
        double y2,
        BigDecimal confidence) {
}
