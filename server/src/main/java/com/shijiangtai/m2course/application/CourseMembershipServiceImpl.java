package com.shijiangtai.m2course.application;

import com.shijiangtai.m2course.application.command.MemberQuery;
import com.shijiangtai.m2course.application.port.CourseEventPublisher;
import com.shijiangtai.m2course.application.port.CourseMemberRepository;
import com.shijiangtai.m2course.application.port.CourseRepository;
import com.shijiangtai.m2course.application.port.M1DirectoryPort;
import com.shijiangtai.m2course.application.port.M1DirectoryPort.AccountSnapshot;
import com.shijiangtai.m2course.application.port.M1DirectoryPort.ClassSnapshot;
import com.shijiangtai.m2course.application.port.M1DirectoryPort.StudentSnapshot;
import com.shijiangtai.m2course.application.view.BatchAddStudentsResult;
import com.shijiangtai.m2course.application.view.CourseStudentView;
import com.shijiangtai.m2course.application.view.CourseTeacherView;
import com.shijiangtai.m2course.domain.Course;
import com.shijiangtai.m2course.domain.CoursePolicy;
import com.shijiangtai.m2course.domain.CourseStudent;
import com.shijiangtai.m2course.domain.CourseTeacher;
import com.shijiangtai.m2course.domain.CourseTeacherRemovedEvent;
import com.shijiangtai.m2course.domain.JoinMethod;
import com.shijiangtai.m2course.domain.MajorId;
import com.shijiangtai.m2course.domain.StudentStatus;
import com.shijiangtai.shared.application.AccountType;
import com.shijiangtai.shared.application.ClockProvider;
import com.shijiangtai.shared.application.CurrentUser;
import com.shijiangtai.shared.domain.AccountId;
import com.shijiangtai.shared.domain.ClassId;
import com.shijiangtai.shared.domain.CourseId;
import com.shijiangtai.shared.domain.DomainException;
import com.shijiangtai.shared.domain.ErrorCode;
import com.shijiangtai.shared.domain.PageResult;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 课程成员用例实现。
 *
 * <p>老师组：组内平权，任何在组教师或本校管理员可增删；至少保留一名有效教师，移出保留历史。
 * 学生：自主加入 / 退出、按班级批量或按账号单个加入，软删除保留历史。
 */
@Service
public class CourseMembershipServiceImpl implements CourseMembershipService {
    private final CourseRepository courseRepository;
    private final CourseMemberRepository memberRepository;
    private final M1DirectoryPort directory;
    private final CourseEventPublisher eventPublisher;
    private final ClockProvider clock;

    public CourseMembershipServiceImpl(
            CourseRepository courseRepository,
            CourseMemberRepository memberRepository,
            M1DirectoryPort directory,
            CourseEventPublisher eventPublisher,
            ClockProvider clock) {
        this.courseRepository = courseRepository;
        this.memberRepository = memberRepository;
        this.directory = directory;
        this.eventPublisher = eventPublisher;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public List<CourseTeacherView> listTeachers(CurrentUser user, CourseId courseId, boolean includeHistory) {
        Course course = loadCourse(courseId);
        CoursePolicy.requireMembersReadRight(user, course);
        List<CourseTeacher> teachers = course.teachers().stream()
                .filter(teacher -> includeHistory || teacher.isActive())
                .toList();
        Map<AccountId, String> names = accountNames(teachers.stream().map(CourseTeacher::accountId).toList());
        return teachers.stream()
                .map(teacher -> new CourseTeacherView(
                        teacher.accountId().value(),
                        names.getOrDefault(teacher.accountId(), ""),
                        teacher.status().name().toLowerCase(java.util.Locale.ROOT),
                        teacher.joinedAt(),
                        teacher.leftAt()))
                .toList();
    }

    @Override
    @Transactional
    public void addTeacher(CurrentUser user, CourseId courseId, AccountId teacherId) {
        Instant now = clock.now();
        Course course = loadCourseForUpdate(courseId);
        CoursePolicy.requireManageRight(user, course);
        CoursePolicy.requireOpenForMembership(course, now);

        AccountSnapshot account = directory.findAccount(teacherId)
                .orElseThrow(() -> new DomainException(ErrorCode.RESOURCE_NOT_FOUND, "教师账号不存在"));
        if (account.type() != AccountType.TEACHER) {
            throw new DomainException(ErrorCode.VALIDATION_ERROR, "该账号不是教师");
        }
        if (!account.schoolId().equals(course.schoolId())) {
            throw new DomainException(ErrorCode.CROSS_SCHOOL_ACCESS, "不能添加其他学校的教师");
        }
        Set<MajorId> teacherMajors = Set.copyOf(directory.activeMajorIds(teacherId));
        if (!course.coversAnyMajor(teacherMajors)) {
            throw new DomainException(ErrorCode.COURSE_MAJOR_MISMATCH, "该教师专业与课程专业范围无交集");
        }
        course.addTeacher(teacherId, now);
        courseRepository.save(course);
    }

    @Override
    @Transactional
    public void removeTeacher(CurrentUser user, CourseId courseId, AccountId teacherId) {
        Instant now = clock.now();
        Course course = loadCourseForUpdate(courseId);
        CoursePolicy.requireManageRight(user, course);
        CoursePolicy.requireOpenForMembership(course, now);
        course.removeTeacher(teacherId, now);
        courseRepository.save(course);
        eventPublisher.publishAfterCommit(new CourseTeacherRemovedEvent(course.id(), teacherId));
    }

    @Override
    @Transactional(readOnly = true)
    public PageResult<CourseStudentView> listStudents(CurrentUser user, CourseId courseId, MemberQuery rawQuery) {
        MemberQuery query = rawQuery.normalized();
        Course course = loadCourse(courseId);
        CoursePolicy.requireMembersReadRight(user, course);

        List<CourseStudent> students = memberRepository.findStudents(courseId, query.includeHistory());
        Map<AccountId, StudentSnapshot> profiles = studentProfiles(
                students.stream().map(CourseStudent::accountId).toList());
        Map<ClassId, String> classNames = classNames(profiles.values());

        List<CourseStudentView> all = students.stream()
                .map(student -> toView(student, profiles, classNames))
                .toList();
        long total = all.size();
        int fromIndex = (int) Math.min((long) (query.page() - 1) * query.size(), total);
        int toIndex = (int) Math.min((long) fromIndex + query.size(), total);
        return new PageResult<>(all.subList(fromIndex, toIndex), total, query.page(), query.size());
    }

    @Override
    @Transactional
    public BatchAddStudentsResult addStudentsByClass(CurrentUser user, CourseId courseId, ClassId classId) {
        Instant now = clock.now();
        Course course = loadCourse(courseId);
        CoursePolicy.requireManageRight(user, course);
        CoursePolicy.requireOpenForMembership(course, now);

        ClassSnapshot clazz = directory.findClass(classId)
                .orElseThrow(() -> new DomainException(ErrorCode.RESOURCE_NOT_FOUND, "班级不存在"));
        if (!clazz.schoolId().equals(course.schoolId())) {
            throw new DomainException(ErrorCode.CROSS_SCHOOL_ACCESS, "不能添加其他学校的班级");
        }
        if (!clazz.active()) {
            throw new DomainException(ErrorCode.CLASS_DISABLED, "班级已停用，不能批量加人");
        }
        if (!course.coversMajor(clazz.majorId())) {
            throw new DomainException(ErrorCode.CLASS_NOT_ELIGIBLE, "班级专业不在课程专业范围内");
        }

        JoinMethod method = user.type() == AccountType.ADMIN ? JoinMethod.ADMIN : JoinMethod.CLASS;
        List<StudentSnapshot> students = directory.findStudentsByClass(classId);
        int success = 0;
        int skipped = 0;
        List<BatchAddStudentsResult.Failure> errors = new ArrayList<>();
        for (StudentSnapshot student : students) {
            if (memberRepository.hasActiveStudent(courseId, student.id())) {
                skipped++;
                continue;
            }
            if (!course.coversMajor(student.majorId())) {
                skipped++;
                errors.add(new BatchAddStudentsResult.Failure(
                        student.id().value().toString(), "学生专业不在课程专业范围内"));
                continue;
            }
            memberRepository.addStudent(courseId, student.id(), method, now);
            success++;
        }
        return new BatchAddStudentsResult(students.size(), success, skipped, errors);
    }

    @Override
    @Transactional
    public void addStudentByAccount(CurrentUser user, CourseId courseId, AccountId studentId) {
        Instant now = clock.now();
        Course course = loadCourse(courseId);
        CoursePolicy.requireManageRight(user, course);
        CoursePolicy.requireOpenForMembership(course, now);

        AccountSnapshot account = directory.findAccount(studentId)
                .orElseThrow(() -> new DomainException(ErrorCode.RESOURCE_NOT_FOUND, "学生账号不存在"));
        if (account.type() != AccountType.STUDENT) {
            throw new DomainException(ErrorCode.VALIDATION_ERROR, "该账号不是学生");
        }
        if (!account.schoolId().equals(course.schoolId())) {
            throw new DomainException(ErrorCode.CROSS_SCHOOL_ACCESS, "不能添加其他学校的学生");
        }
        StudentSnapshot profile = directory.findStudent(studentId)
                .orElseThrow(() -> new DomainException(ErrorCode.RESOURCE_NOT_FOUND, "学生档案不存在"));
        if (!course.coversMajor(profile.majorId())) {
            throw new DomainException(ErrorCode.COURSE_MAJOR_MISMATCH, "学生专业不在课程专业范围内");
        }
        if (memberRepository.hasActiveStudent(courseId, studentId)) {
            throw new DomainException(ErrorCode.ALREADY_JOINED, "该学生已在课程中");
        }
        JoinMethod method = user.type() == AccountType.ADMIN ? JoinMethod.ADMIN : JoinMethod.ACCOUNT;
        memberRepository.addStudent(courseId, studentId, method, now);
    }

    @Override
    @Transactional
    public void removeStudent(CurrentUser user, CourseId courseId, AccountId studentId) {
        Instant now = clock.now();
        Course course = loadCourse(courseId);
        CoursePolicy.requireManageRight(user, course);
        CoursePolicy.requireOpenForMembership(course, now);
        if (!memberRepository.hasActiveStudent(courseId, studentId)) {
            throw new DomainException(ErrorCode.RESOURCE_NOT_FOUND, "该学生不在课程中");
        }
        memberRepository.markStudentStatus(courseId, studentId, StudentStatus.REMOVED, now);
    }

    @Override
    @Transactional
    public void join(CurrentUser user, CourseId courseId) {
        Instant now = clock.now();
        CoursePolicy.requireStudent(user);
        Course course = loadCourse(courseId);
        CoursePolicy.requireSameSchool(user, course.schoolId());
        CoursePolicy.requireOpenForMembership(course, now);

        StudentSnapshot profile = directory.findStudent(user.accountId())
                .orElseThrow(() -> new DomainException(ErrorCode.FORBIDDEN, "学生档案不存在，无法加入课程"));
        if (!course.coversMajor(profile.majorId())) {
            throw new DomainException(ErrorCode.COURSE_MAJOR_MISMATCH, "本人专业不在课程专业范围内");
        }
        if (memberRepository.hasActiveStudent(courseId, user.accountId())) {
            throw new DomainException(ErrorCode.ALREADY_JOINED, "已加入该课程");
        }
        memberRepository.addStudent(courseId, user.accountId(), JoinMethod.SELF, now);
    }

    @Override
    @Transactional
    public void leave(CurrentUser user, CourseId courseId) {
        Instant now = clock.now();
        CoursePolicy.requireStudent(user);
        Course course = loadCourse(courseId);
        CoursePolicy.requireSameSchool(user, course.schoolId());
        CoursePolicy.requireOpenForMembership(course, now);
        if (!memberRepository.hasActiveStudent(courseId, user.accountId())) {
            throw new DomainException(ErrorCode.RESOURCE_NOT_FOUND, "不在该课程中");
        }
        memberRepository.markStudentStatus(courseId, user.accountId(), StudentStatus.LEFT, now);
    }

    private CourseStudentView toView(
            CourseStudent student,
            Map<AccountId, StudentSnapshot> profiles,
            Map<ClassId, String> classNames) {
        StudentSnapshot profile = profiles.get(student.accountId());
        ClassId classId = profile == null ? null : profile.classId();
        return new CourseStudentView(
                student.accountId().value(),
                profile == null ? "" : profile.username(),
                classId == null ? null : classId.value(),
                classId == null ? null : classNames.get(classId),
                student.joinMethod().code(),
                student.status().name().toLowerCase(java.util.Locale.ROOT),
                student.joinedAt(),
                student.leftAt());
    }

    private Map<AccountId, StudentSnapshot> studentProfiles(List<AccountId> accountIds) {
        if (accountIds.isEmpty()) {
            return Map.of();
        }
        return directory.findStudents(new LinkedHashSet<>(accountIds)).stream()
                .collect(Collectors.toMap(StudentSnapshot::id, Function.identity(), (left, right) -> left));
    }

    private Map<ClassId, String> classNames(java.util.Collection<StudentSnapshot> profiles) {
        Set<ClassId> classIds = profiles.stream()
                .map(StudentSnapshot::classId)
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (classIds.isEmpty()) {
            return Map.of();
        }
        return classIds.stream()
                .map(directory::findClass)
                .flatMap(java.util.Optional::stream)
                .collect(Collectors.toMap(ClassSnapshot::id, ClassSnapshot::displayName, (left, right) -> left));
    }

    private Map<AccountId, String> accountNames(List<AccountId> accountIds) {
        if (accountIds.isEmpty()) {
            return Map.of();
        }
        return directory.findAccounts(new LinkedHashSet<>(accountIds)).stream()
                .collect(Collectors.toMap(AccountSnapshot::id, AccountSnapshot::username, (left, right) -> left));
    }

    private Course loadCourse(CourseId courseId) {
        return courseRepository.findById(courseId)
                .orElseThrow(() -> new DomainException(ErrorCode.RESOURCE_NOT_FOUND, "课程不存在"));
    }

    private Course loadCourseForUpdate(CourseId courseId) {
        return courseRepository.findByIdForUpdate(courseId)
                .orElseThrow(() -> new DomainException(ErrorCode.RESOURCE_NOT_FOUND, "课程不存在"));
    }
}
