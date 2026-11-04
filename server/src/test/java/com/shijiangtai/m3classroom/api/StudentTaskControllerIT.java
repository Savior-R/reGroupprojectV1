package com.shijiangtai.m3classroom.api;

import static com.shijiangtai.m3classroom.infrastructure.config.DevDataConfig.COURSE_ID;
import static com.shijiangtai.m3classroom.infrastructure.config.DevDataConfig.QUESTION1_ID;
import static com.shijiangtai.m3classroom.infrastructure.config.DevDataConfig.SCHOOL_ID;
import static com.shijiangtai.m3classroom.infrastructure.config.DevDataConfig.STUDENT_ID;
import static com.shijiangtai.m3classroom.infrastructure.config.DevDataConfig.TEACHER_ID;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.shijiangtai.m3classroom.application.TaskApplicationService;
import com.shijiangtai.m3classroom.application.TaskPublishService;
import com.shijiangtai.m3classroom.application.command.CreateTaskCommand;
import com.shijiangtai.m3classroom.application.view.TaskView;
import com.shijiangtai.m3classroom.domain.task.QuestionConfig;
import com.shijiangtai.m3classroom.domain.task.TaskMode;
import com.shijiangtai.m3classroom.persistence.PostgresIntegrationTest;
import com.shijiangtai.shared.application.AccountType;
import com.shijiangtai.shared.application.CurrentUser;
import com.shijiangtai.shared.domain.AccountId;
import com.shijiangtai.shared.domain.CourseId;
import com.shijiangtai.shared.domain.QuestionId;
import com.shijiangtai.shared.domain.SchoolId;
import com.shijiangtai.shared.domain.TaskId;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@AutoConfigureMockMvc
class StudentTaskControllerIT extends PostgresIntegrationTest {
    private static final CurrentUser TEACHER = new CurrentUser(
            new AccountId(TEACHER_ID), new SchoolId(SCHOOL_ID), AccountType.TEACHER);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TaskApplicationService taskApplicationService;

    @Autowired
    private TaskPublishService taskPublishService;

    @Test
    void studentListContainsAssignedTask() throws Exception {
        TaskId taskId = publishedInterviewTask();

        mockMvc.perform(asStudent(get("/api/v1/student/tasks")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].task_id").value(taskId.value().toString()))
                .andExpect(jsonPath("$.items[0].course_name").value("语文教学论"))
                .andExpect(jsonPath("$.items[0].rubric_weights").doesNotExist());
    }

    @Test
    void studentDetailReturnsPublicView() throws Exception {
        TaskId taskId = publishedInterviewTask();

        mockMvc.perform(asStudent(get("/api/v1/student/tasks/{id}", taskId.value())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.task_id").value(taskId.value().toString()))
                .andExpect(jsonPath("$.question_count").value(1))
                .andExpect(jsonPath("$.assignment_status").value("assigned"))
                .andExpect(jsonPath("$.rubric_weights").doesNotExist())
                .andExpect(jsonPath("$.published_questions").doesNotExist());
    }

    @Test
    void unassignedStudentGets403() throws Exception {
        TaskId taskId = publishedInterviewTask();

        mockMvc.perform(get("/api/v1/student/tasks/{id}", taskId.value())
                        .header("X-Account-Id", UUID.randomUUID().toString())
                        .header("X-School-Id", SCHOOL_ID.toString())
                        .header("X-Account-Type", "STUDENT"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    private TaskId publishedInterviewTask() {
        QuestionId questionId = new QuestionId(QUESTION1_ID);
        CreateTaskCommand command = new CreateTaskCommand(
                new CourseId(COURSE_ID), TaskMode.INTERVIEW, "结构化面试", null,
                null, null, null, null, List.of(),
                new QuestionConfig(List.of(questionId), 300));
        TaskView view = taskApplicationService.createDraft(TEACHER, command);
        TaskId taskId = new TaskId(view.taskId());
        taskPublishService.publish(TEACHER, taskId);
        return taskId;
    }

    private MockHttpServletRequestBuilder asStudent(MockHttpServletRequestBuilder builder) {
        return builder.header("X-Account-Id", STUDENT_ID.toString())
                .header("X-School-Id", SCHOOL_ID.toString())
                .header("X-Account-Type", "STUDENT");
    }
}
