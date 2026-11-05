package com.shijiangtai.m3classroom.application.port;

import com.shijiangtai.m3classroom.domain.session.OcrBox;
import java.math.BigDecimal;
import java.util.List;

public record OcrResult(String text, List<OcrBox> boxes, BigDecimal confidence, String modelVersion) {
}
