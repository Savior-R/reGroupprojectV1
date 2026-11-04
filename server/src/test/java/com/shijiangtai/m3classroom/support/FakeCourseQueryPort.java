package com.shijiangtai.m3classroom.support;

import com.shijiangtai.m3classroom.application.port.CourseQueryPort;
import com.shijiangtai.m3classroom.domain.task.CourseInfo;
import com.shijiangtai.m3classroom.domain.task.CourseStudentInfo;
import com.shijiangtai.shared.domain.AccountId;
import com.shijiangtai.shared.domain.CourseId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

public class FakeCourseQueryPort implements CourseQueryPort {
    private final Map<CourseId, CourseInfo> courses = new ConcurrentHashMap<>();
    private final Map<CourseId, Set<AccountId>> teachers = new ConcurrentHashMap<>();
    private final Map<CourseId, List<CourseStudentInfo>> students = new ConcurrentHashMap<>();

    public void addCourse(CourseInfo course) {
        CourseId courseId = new CourseId(course.id());
        courses.put(courseId, course);
        Set<AccountId> members = ConcurrentHashMap.newKeySet();
        course.teacherAccountIds().forEach(id -> members.add(new AccountId(id)));
        teachers.put(courseId, members);
        students.putIfAbsent(courseId, new CopyOnWriteArrayList<>());
    }

    public void addTeacher(CourseId courseId, AccountId teacherId) {
        teachers.computeIfAbsent(courseId, ignored -> ConcurrentHashMap.newKeySet())
                .add(teacherId);
    }

    public void addStudent(CourseId courseId, CourseStudentInfo student) {
        students.computeIfAbsent(courseId, ignored -> new CopyOnWriteArrayList<>())
                .add(student);
    }

    @Override
    public Optional<CourseInfo> findCourse(CourseId courseId) {
        return Optional.ofNullable(courses.get(courseId));
    }

    @Override
    public boolean isTeacherInGroup(CourseId courseId, AccountId teacherId) {
        return teachers.getOrDefault(courseId, Set.of()).contains(teacherId);
    }

    @Override
    public List<CourseStudentInfo> activeStudents(CourseId courseId) {
        return List.copyOf(students.getOrDefault(courseId, List.of()));
    }
}
