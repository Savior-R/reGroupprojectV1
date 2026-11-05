package com.shijiangtai.m3classroom.application.port;

import java.math.BigDecimal;

public record ClassifyResult(
        String label, BigDecimal confidence, String modelVersion, String promptVersion) {
}
