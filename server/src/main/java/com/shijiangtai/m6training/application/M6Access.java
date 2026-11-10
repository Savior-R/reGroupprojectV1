package com.shijiangtai.m6training.application;

import com.shijiangtai.m6training.domain.TrainingError;
import com.shijiangtai.m6training.domain.TrainingException;
import com.shijiangtai.shared.application.AccountType;
import com.shijiangtai.shared.application.CurrentUser;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Role gate for M6: only students train, only teachers manage templates (08 §11). */
@Component
public class M6Access {

    public void requireStudent(CurrentUser user) {
        if (user.type() != AccountType.STUDENT) {
            throw new TrainingException(TrainingError.ROLE_NOT_ALLOWED,
                    "仅学生可使用训练功能", Map.of("required_role", "student"));
        }
    }

    public void requireTeacher(CurrentUser user) {
        if (user.type() != AccountType.TEACHER) {
            throw new TrainingException(TrainingError.ROLE_NOT_ALLOWED,
                    "仅教师可管理训练模板", Map.of("required_role", "teacher"));
        }
    }
}
