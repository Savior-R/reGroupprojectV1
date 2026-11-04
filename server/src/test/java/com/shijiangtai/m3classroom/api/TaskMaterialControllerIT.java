package com.shijiangtai.m3classroom.api;

import static com.shijiangtai.m3classroom.infrastructure.config.DevDataConfig.COURSE_ID;
import static com.shijiangtai.m3classroom.infrastructure.config.DevDataConfig.QUESTION1_ID;
import static com.shijiangtai.m3classroom.infrastructure.config.DevDataConfig.SCHOOL_ID;
import static com.shijiangtai.m3classroom.infrastructure.config.DevDataConfig.STUDENT_ID;
import static com.shijiangtai.m3classroom.infrastructure.config.DevDataConfig.TEACHER_ID;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.shijiangtai.m3classroom.application.TaskApplicationService;
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
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

@AutoConfigureMockMvc
class TaskMaterialControllerIT extends PostgresIntegrationTest {
    private static final CurrentUser TEACHER = new CurrentUser(
            new AccountId(TEACHER_ID), new SchoolId(SCHOOL_ID), AccountType.TEACHER);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TaskApplicationService applicationService;

    @Test
    void uploadTxtThenGetThenDelete() throws Exception {
        TaskId taskId = createFreeTask();
        MockMultipartFile file = new MockMultipartFile(
                "file", "教案.txt", "text/plain",
                "这是一份足够长的参考材料文本内容，用于测试材料接口。"
                        .getBytes(StandardCharsets.UTF_8));

        mockMvc.perform(multipart("/api/v1/tasks/{id}/material", taskId.value())
                        .file(file).with(teacherHeaders()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.parse_status").value("parsed"))
                .andExpect(jsonPath("$.preview").isString());

        mockMvc.perform(get("/api/v1/tasks/{id}/material", taskId.value()).with(teacherHeaders()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.parsed_text").isString());

        mockMvc.perform(delete("/api/v1/tasks/{id}/material", taskId.value()).with(teacherHeaders()))
                .andExpect(status().isNoContent());
    }

    @Test
    void interviewMaterialUploadReturns422() throws Exception {
        TaskId taskId = createInterviewTask();
        MockMultipartFile file = new MockMultipartFile(
                "file", "教案.txt", "text/plain",
                "这是一份足够长的参考材料文本内容，用于测试材料接口。"
                        .getBytes(StandardCharsets.UTF_8));

        mockMvc.perform(multipart("/api/v1/tasks/{id}/material", taskId.value())
                        .file(file).with(teacherHeaders()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("MATERIAL_NOT_APPLICABLE"));
    }

    @Test
    void studentCannotReadMaterial() throws Exception {
        TaskId taskId = createFreeTask();

        mockMvc.perform(get("/api/v1/tasks/{id}/material", taskId.value()).with(studentHeaders()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void deleteIsIdempotent() throws Exception {
        TaskId taskId = createFreeTask();
        mockMvc.perform(delete("/api/v1/tasks/{id}/material", taskId.value()).with(teacherHeaders()))
                .andExpect(status().isNoContent());
    }

    private TaskId createFreeTask() {
        TaskView view = applicationService.createDraft(
                TEACHER, new CreateTaskCommand(
                        new CourseId(COURSE_ID), TaskMode.FREE, "荷塘月色", "部编版",
                        null, null, null, null, List.of(), null));
        return new TaskId(view.taskId());
    }

    private TaskId createInterviewTask() {
        TaskView view = applicationService.createDraft(
                TEACHER, new CreateTaskCommand(
                        new CourseId(COURSE_ID), TaskMode.INTERVIEW, "结构化面试", null,
                        null, null, null, null, List.of(),
                        new QuestionConfig(List.of(new QuestionId(QUESTION1_ID)), 300)));
        return new TaskId(view.taskId());
    }

    private RequestPostProcessor teacherHeaders() {
        return request -> {
            request.addHeader("X-Account-Id", TEACHER_ID.toString());
            request.addHeader("X-School-Id", SCHOOL_ID.toString());
            request.addHeader("X-Account-Type", "TEACHER");
            return request;
        };
    }

    private RequestPostProcessor studentHeaders() {
        return request -> {
            request.addHeader("X-Account-Id", STUDENT_ID.toString());
            request.addHeader("X-School-Id", SCHOOL_ID.toString());
            request.addHeader("X-Account-Type", "STUDENT");
            return request;
        };
    }
}
