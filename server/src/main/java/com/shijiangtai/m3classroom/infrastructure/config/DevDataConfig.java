package com.shijiangtai.m3classroom.infrastructure.config;

import com.shijiangtai.m3classroom.application.port.CourseQueryPort;
import com.shijiangtai.m3classroom.application.port.QuestionBankQueryPort;
import com.shijiangtai.m3classroom.domain.task.CourseInfo;
import com.shijiangtai.m3classroom.domain.task.CourseStudentInfo;
import com.shijiangtai.m3classroom.domain.task.PublishQuestion;
import com.shijiangtai.m3classroom.infrastructure.crossmodule.InMemoryCourseQueryAdapter;
import com.shijiangtai.m3classroom.infrastructure.crossmodule.InMemoryQuestionBankQueryAdapter;
import com.shijiangtai.shared.domain.CourseId;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration
@Profile({"dev", "test", "docker"})
public class DevDataConfig {
    public static final UUID SCHOOL_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000001");
    public static final UUID TEACHER_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000010");
    public static final UUID OTHER_TEACHER_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000011");
    public static final UUID STUDENT_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000020");
    public static final UUID STUDENT2_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000021");
    public static final UUID COURSE_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000100");
    public static final UUID CLASS_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000200");
    public static final UUID CATEGORY_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000400");
    public static final UUID QUESTION1_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000301");
    public static final UUID QUESTION2_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000302");

    @Bean
    public InMemoryCourseQueryAdapter courseQueryPort() {
        InMemoryCourseQueryAdapter adapter = new InMemoryCourseQueryAdapter();
        CourseId courseId = new CourseId(COURSE_ID);
        adapter.addCourse(new CourseInfo(
                COURSE_ID,
                SCHOOL_ID,
                "语文教学论",
                true,
                Instant.parse("2027-06-30T00:00:00Z"),
                List.of(TEACHER_ID)));
        adapter.addStudent(courseId, new CourseStudentInfo(STUDENT_ID, CLASS_ID));
        adapter.addStudent(courseId, new CourseStudentInfo(STUDENT2_ID, CLASS_ID));
        return adapter;
    }

    @Bean
    public InMemoryQuestionBankQueryAdapter questionBankQueryPort() {
        InMemoryQuestionBankQueryAdapter adapter = new InMemoryQuestionBankQueryAdapter();
        adapter.addQuestion(new PublishQuestion(
                QUESTION1_ID, "请谈谈以学生为中心的理解。", "以学生发展为本，尊重个体差异。",
                CATEGORY_ID, "教育理念", SCHOOL_ID, true));
        adapter.addQuestion(new PublishQuestion(
                QUESTION2_ID, "如何落实核心素养？", "围绕真实问题设计任务，促进迁移。",
                CATEGORY_ID, "教育理念", SCHOOL_ID, true));
        return adapter;
    }
}
