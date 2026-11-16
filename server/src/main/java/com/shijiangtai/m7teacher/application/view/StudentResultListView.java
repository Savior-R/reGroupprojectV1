package com.shijiangtai.m7teacher.application.view;

import java.util.List;

public record StudentResultListView(
        List<StudentResultRow> items, long total, int page, int size) {
}
