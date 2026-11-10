package com.shijiangtai.m7teacher.application.view;

import java.util.List;

public record BatchSaveView(int successCount, int failureCount, List<BatchRowView> results) {
}
