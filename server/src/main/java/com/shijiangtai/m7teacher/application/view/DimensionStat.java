package com.shijiangtai.m7teacher.application.view;

import java.math.BigDecimal;

public record DimensionStat(
        String dimension, BigDecimal average, int sampleCount, boolean weak) {
}
