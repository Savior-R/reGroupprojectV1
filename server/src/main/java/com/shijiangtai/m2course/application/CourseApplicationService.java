package com.shijiangtai.m2course.application;

import com.shijiangtai.m2course.application.command.CourseQuery;
import com.shijiangtai.m2course.application.command.CreateCourseCommand;
import com.shijiangtai.m2course.application.command.UpdateCourseCommand;
import com.shijiangtai.m2course.application.view.CourseCardView;
import com.shijiangtai.m2course.application.view.CourseDetailView;
import com.shijiangtai.shared.application.CurrentUser;
import com.shijiangtai.shared.domain.CourseId;
import com.shijiangtai.shared.domain.PageResult;

/** 课程用例：创建、编辑、归档 / 恢复、详情、列表。 */
public interface CourseApplicationService {

    CourseDetailView create(CurrentUser user, CreateCourseCommand command);

    CourseDetailView update(CurrentUser user, CourseId courseId, UpdateCourseCommand command);

    void archive(CurrentUser user, CourseId courseId);

    void restore(CurrentUser user, CourseId courseId);

    CourseDetailView get(CurrentUser user, CourseId courseId);

    PageResult<CourseCardView> list(CurrentUser user, CourseQuery query);
}
