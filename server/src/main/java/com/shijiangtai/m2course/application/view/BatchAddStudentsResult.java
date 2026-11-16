package com.shijiangtai.m2course.application.view;

import java.util.List;

/** 批量加人结果：允许部分成功，与 M1 组织导入的整批回滚不同。 */
public record BatchAddStudentsResult(int total, int success, int skipped, List<Failure> errors) {

    public BatchAddStudentsResult {
        errors = List.copyOf(errors);
    }

    public record Failure(String accountId, String reason) {
    }
}
