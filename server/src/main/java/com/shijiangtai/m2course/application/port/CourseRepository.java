package com.shijiangtai.m2course.application.port;

import com.shijiangtai.m2course.application.command.CourseSearchCriteria;
import com.shijiangtai.m2course.domain.Course;
import com.shijiangtai.shared.domain.CourseId;
import com.shijiangtai.shared.domain.SchoolId;
import java.util.List;
import java.util.Optional;

/** 课程聚合持久化端口。 */
public interface CourseRepository {

    Course save(Course course);

    Optional<Course> findById(CourseId courseId);

    /** 修改老师组 / 状态前锁课程行，防止并发移出最后一名教师。 */
    Optional<Course> findByIdForUpdate(CourseId courseId);

    boolean existsNameInSchool(SchoolId schoolId, String name);

    List<Course> search(CourseSearchCriteria criteria);

    long count(CourseSearchCriteria criteria);
}
