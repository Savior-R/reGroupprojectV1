package com.shijiangtai.m2course.api;

import com.shijiangtai.m2course.application.CourseApplicationService;
import com.shijiangtai.m2course.application.command.CourseQuery;
import com.shijiangtai.m2course.application.command.CreateCourseCommand;
import com.shijiangtai.m2course.application.command.Patch;
import com.shijiangtai.m2course.application.command.UpdateCourseCommand;
import com.shijiangtai.m2course.application.view.CourseCardView;
import com.shijiangtai.m2course.application.view.CourseDetailView;
import com.shijiangtai.m2course.api.dto.CreateCourseRequest;
import com.shijiangtai.m2course.api.dto.PageResponse;
import com.shijiangtai.m2course.api.dto.UpdateCourseRequest;
import com.shijiangtai.m2course.domain.MajorId;
import com.shijiangtai.shared.application.CurrentUser;
import com.shijiangtai.shared.application.CurrentUserProvider;
import com.shijiangtai.shared.domain.CourseId;
import com.shijiangtai.shared.domain.PageResult;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * M2 课程接口（docs/design/02 §4.2）。
 *
 * <p>URL 级权限由网关 / AuthorizationManager 处理，资源级权限在 application service。
 */
@RestController
@RequestMapping("/api/v1/courses")
public class CourseController {
    private final CourseApplicationService courseApplicationService;
    private final CurrentUserProvider currentUserProvider;

    public CourseController(
            CourseApplicationService courseApplicationService,
            CurrentUserProvider currentUserProvider) {
        this.courseApplicationService = courseApplicationService;
        this.currentUserProvider = currentUserProvider;
    }

    @GetMapping
    public PageResponse<CourseCardView> list(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String scope,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        CurrentUser user = currentUserProvider.require();
        PageResult<CourseCardView> result =
                courseApplicationService.list(user, new CourseQuery(keyword, scope, status, page, size));
        return PageResponse.of(result);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CourseDetailView create(@RequestBody @Valid CreateCourseRequest request) {
        CurrentUser user = currentUserProvider.require();
        List<MajorId> majorIds = request.majorIds() == null
                ? List.of()
                : request.majorIds().stream().map(MajorId::of).toList();
        return courseApplicationService.create(
                user, new CreateCourseCommand(request.name(), majorIds, request.endAt()));
    }

    @GetMapping("/{courseId}")
    public CourseDetailView get(@PathVariable UUID courseId) {
        return courseApplicationService.get(currentUserProvider.require(), new CourseId(courseId));
    }

    @PatchMapping("/{courseId}")
    public CourseDetailView update(
            @PathVariable UUID courseId, @RequestBody @Valid UpdateCourseRequest request) {
        Patch<String> name = request.name() == null ? Patch.absent() : Patch.of(request.name());
        Patch<java.time.Instant> endAt =
                request.endAt() == null ? Patch.absent() : Patch.of(request.endAt());
        return courseApplicationService.update(
                currentUserProvider.require(), new CourseId(courseId), new UpdateCourseCommand(name, endAt));
    }

    @PostMapping("/{courseId}/archive")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void archive(@PathVariable UUID courseId) {
        courseApplicationService.archive(currentUserProvider.require(), new CourseId(courseId));
    }

    @PostMapping("/{courseId}/restore")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void restore(@PathVariable UUID courseId) {
        courseApplicationService.restore(currentUserProvider.require(), new CourseId(courseId));
    }
}
