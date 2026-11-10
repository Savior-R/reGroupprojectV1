package com.shijiangtai.m2course.application;

import com.shijiangtai.m2course.application.command.CourseQuery;
import com.shijiangtai.m2course.application.command.CourseSearchCriteria;
import com.shijiangtai.m2course.application.command.CreateCourseCommand;
import com.shijiangtai.m2course.application.command.UpdateCourseCommand;
import com.shijiangtai.m2course.application.port.CourseEventPublisher;
import com.shijiangtai.m2course.application.port.CourseMemberRepository;
import com.shijiangtai.m2course.application.port.CourseRepository;
import com.shijiangtai.m2course.application.port.M1DirectoryPort;
import com.shijiangtai.m2course.application.port.M1DirectoryPort.AccountSnapshot;
import com.shijiangtai.m2course.application.port.M1DirectoryPort.MajorSnapshot;
import com.shijiangtai.m2course.application.view.CourseCardView;
import com.shijiangtai.m2course.application.view.CourseDetailView;
import com.shijiangtai.m2course.application.view.MajorBrief;
import com.shijiangtai.m2course.application.view.TeacherBrief;
import com.shijiangtai.m2course.domain.Course;
import com.shijiangtai.m2course.domain.CourseArchivedEvent;
import com.shijiangtai.m2course.domain.CoursePolicy;
import com.shijiangtai.m2course.domain.CourseStatus;
import com.shijiangtai.m2course.domain.CourseTeacher;
import com.shijiangtai.m2course.domain.MajorId;
import com.shijiangtai.shared.application.AccountType;
import com.shijiangtai.shared.application.ClockProvider;
import com.shijiangtai.shared.application.CurrentUser;
import com.shijiangtai.shared.application.IdGenerator;
import com.shijiangtai.shared.domain.AccountId;
import com.shijiangtai.shared.domain.CourseId;
import com.shijiangtai.shared.domain.DomainException;
import com.shijiangtai.shared.domain.ErrorCode;
import com.shijiangtai.shared.domain.PageResult;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 课程用例实现。
 *
 * <p>权限：学生只看本校专业范围内的启用课程；教师本校只读、本组可管理；管理员本校可管不可建；
 * 跨校一律 403。状态矩阵见 {@link CoursePolicy}。
 */
@Service
public class CourseApplicationServiceImpl implements CourseApplicationService {
    private final CourseRepository courseRepository;
    private final CourseMemberRepository memberRepository;
    private final M1DirectoryPort directory;
    private final CourseEventPublisher eventPublisher;
    private final ClockProvider clock;
    private final IdGenerator idGenerator;

    public CourseApplicationServiceImpl(
            CourseRepository courseRepository,
            CourseMemberRepository memberRepository,
            M1DirectoryPort directory,
            CourseEventPublisher eventPublisher,
            ClockProvider clock,
            IdGenerator idGenerator) {
        this.courseRepository = courseRepository;
        this.memberRepository = memberRepository;
        this.directory = directory;
        this.eventPublisher = eventPublisher;
        this.clock = clock;
        this.idGenerator = idGenerator;
    }

    @Override
    @Transactional
    public CourseDetailView create(CurrentUser user, CreateCourseCommand command) {
        CoursePolicy.requireCanCreate(user);
        Instant now = clock.now();
        CoursePolicy.requireMajorSelection(command.majorIds());
        CoursePolicy.requireValidEndAt(command.endAt(), now);
        Set<MajorId> majorIds = new LinkedHashSet<>(command.majorIds());

        validateMajorsForCreate(user, majorIds);

        String name = CoursePolicy.normalizeName(command.name());
        if (courseRepository.existsNameInSchool(user.schoolId(), name)) {
            throw new DomainException(ErrorCode.COURSE_NAME_TAKEN, "课程名称在本校已存在");
        }

        Course course = Course.create(
                new CourseId(idGenerator.nextId()),
                user.schoolId(),
                user.accountId(),
                name,
                majorIds,
                command.endAt(),
                now);
        courseRepository.save(course);
        return toDetail(user, course, now);
    }

    @Override
    @Transactional
    public CourseDetailView update(CurrentUser user, CourseId courseId, UpdateCourseCommand command) {
        Instant now = clock.now();
        Course course = loadCourse(courseId);
        CoursePolicy.requireManageRight(user, course);
        CoursePolicy.requireEditable(course, now);

        if (command.name().present()) {
            String name = CoursePolicy.normalizeName(command.name().value());
            if (!name.equals(course.name()) && courseRepository.existsNameInSchool(course.schoolId(), name)) {
                throw new DomainException(ErrorCode.COURSE_NAME_TAKEN, "课程名称在本校已存在");
            }
            course.rename(name, now);
        }
        if (command.endAt().present()) {
            course.changeEndAt(command.endAt().value(), now);
        }
        courseRepository.save(course);
        return toDetail(user, course, now);
    }

    @Override
    @Transactional
    public void archive(CurrentUser user, CourseId courseId) {
        CoursePolicy.requireAdmin(user);
        Course course = loadCourse(courseId);
        CoursePolicy.requireSameSchool(user, course.schoolId());
        if (course.isArchived()) {
            return;
        }
        course.archive(clock.now());
        courseRepository.save(course);
        eventPublisher.publishAfterCommit(new CourseArchivedEvent(course.id()));
    }

    @Override
    @Transactional
    public void restore(CurrentUser user, CourseId courseId) {
        CoursePolicy.requireAdmin(user);
        Course course = loadCourse(courseId);
        CoursePolicy.requireSameSchool(user, course.schoolId());
        if (!course.isArchived()) {
            return;
        }
        course.restoreToActive(clock.now());
        courseRepository.save(course);
    }

    @Override
    @Transactional(readOnly = true)
    public CourseDetailView get(CurrentUser user, CourseId courseId) {
        Instant now = clock.now();
        Course course = loadCourse(courseId);
        CoursePolicy.requireSameSchool(user, course.schoolId());
        if (user.type() == AccountType.STUDENT) {
            Optional<MajorId> studentMajor = studentMajor(user);
            if (studentMajor.isEmpty() || !course.isVisibleToStudent(studentMajor.get())) {
                throw new DomainException(ErrorCode.RESOURCE_NOT_FOUND, "课程不存在");
            }
        }
        return toDetail(user, course, now);
    }

    @Override
    @Transactional(readOnly = true)
    public PageResult<CourseCardView> list(CurrentUser user, CourseQuery rawQuery) {
        CourseQuery query = rawQuery.normalized();
        Instant now = clock.now();
        StatusFilter filter = parseStatus(query.status());

        Optional<MajorId> studentMajor = Optional.empty();
        CourseSearchCriteria criteria;
        switch (user.type()) {
            case STUDENT -> {
                studentMajor = studentMajor(user);
                if (studentMajor.isEmpty()) {
                    return new PageResult<>(List.of(), 0, query.page(), query.size());
                }
                criteria = new CourseSearchCriteria(
                        user.schoolId(), query.keyword(), false, CourseStatus.ACTIVE, null,
                        null, studentMajor.get(), query.page(), query.size());
            }
            case TEACHER -> criteria = new CourseSearchCriteria(
                    user.schoolId(), query.keyword(), false, filter.status(), filter.ended(),
                    "all".equalsIgnoreCase(query.scope()) ? null : user.accountId(),
                    null, query.page(), query.size());
            case ADMIN -> criteria = new CourseSearchCriteria(
                    user.schoolId(), query.keyword(), true, filter.status(), filter.ended(),
                    null, null, query.page(), query.size());
            default -> throw new DomainException(ErrorCode.FORBIDDEN, "不支持的角色");
        }

        List<Course> courses = courseRepository.search(criteria);
        long total = courseRepository.count(criteria);
        if (courses.isEmpty()) {
            return new PageResult<>(List.of(), total, query.page(), query.size());
        }

        List<CourseId> courseIds = courses.stream().map(Course::id).toList();
        Map<CourseId, Long> studentCounts = memberRepository.countActiveStudents(courseIds);
        Map<AccountId, String> teacherNames = accountNames(courses.stream()
                .flatMap(course -> course.activeTeachers().stream())
                .map(CourseTeacher::accountId)
                .toList());
        Map<MajorId, String> majorNames = majorNames(courses.stream()
                .flatMap(course -> course.majorIds().stream())
                .toList());
        Set<CourseId> joinedCourses = user.type() == AccountType.STUDENT
                ? memberRepository.findActiveCourseIds(user.accountId(), courseIds)
                : Set.of();

        List<CourseCardView> items = courses.stream()
                .map(course -> toCard(user, course, now, studentCounts, teacherNames, majorNames, joinedCourses))
                .toList();
        return new PageResult<>(items, total, query.page(), query.size());
    }

    private void validateMajorsForCreate(CurrentUser user, Set<MajorId> majorIds) {
        List<MajorSnapshot> majors = directory.findMajors(majorIds);
        Map<MajorId, MajorSnapshot> byId = majors.stream()
                .collect(Collectors.toMap(MajorSnapshot::id, Function.identity(), (left, right) -> left));
        for (MajorId majorId : majorIds) {
            MajorSnapshot major = byId.get(majorId);
            if (major == null) {
                throw new DomainException(ErrorCode.RESOURCE_NOT_FOUND, "专业不存在");
            }
            if (!major.schoolId().equals(user.schoolId())) {
                throw new DomainException(ErrorCode.CROSS_SCHOOL_ACCESS, "所选专业不属于本校");
            }
            if (!major.active()) {
                throw new DomainException(ErrorCode.MAJOR_DISABLED, "所选专业已停用");
            }
        }
        Set<MajorId> teacherMajors = Set.copyOf(directory.activeMajorIds(user.accountId()));
        if (!teacherMajors.containsAll(majorIds)) {
            throw new DomainException(ErrorCode.COURSE_MAJOR_MISMATCH, "所选专业超出本人专业范围");
        }
    }

    private Course loadCourse(CourseId courseId) {
        return courseRepository.findById(courseId)
                .orElseThrow(() -> new DomainException(ErrorCode.RESOURCE_NOT_FOUND, "课程不存在"));
    }

    private Optional<MajorId> studentMajor(CurrentUser user) {
        return directory.findStudent(user.accountId()).map(M1DirectoryPort.StudentSnapshot::majorId);
    }

    private CourseCardView toCard(
            CurrentUser user,
            Course course,
            Instant now,
            Map<CourseId, Long> studentCounts,
            Map<AccountId, String> teacherNames,
            Map<MajorId, String> majorNames,
            Set<CourseId> joinedCourses) {
        return new CourseCardView(
                course.id().value(),
                course.name(),
                course.status().name().toLowerCase(java.util.Locale.ROOT),
                course.isEnded(now),
                course.endAt(),
                briefs(course.majorIds(), majorNames),
                course.activeTeachers().stream()
                        .map(teacher -> new TeacherBrief(
                                teacher.accountId().value(),
                                teacherNames.getOrDefault(teacher.accountId(), "")))
                        .toList(),
                studentCounts.getOrDefault(course.id(), 0L),
                joinedCourses.contains(course.id()),
                canManage(user, course));
    }

    private CourseDetailView toDetail(CurrentUser user, Course course, Instant now) {
        List<TeacherBrief> teachers = course.activeTeachers().stream()
                .map(teacher -> new TeacherBrief(
                        teacher.accountId().value(),
                        accountNames(List.of(teacher.accountId())).getOrDefault(teacher.accountId(), "")))
                .toList();
        long studentCount = memberRepository.countActiveStudents(List.of(course.id()))
                .getOrDefault(course.id(), 0L);
        boolean joined = user.type() == AccountType.STUDENT
                && memberRepository.hasActiveStudent(course.id(), user.accountId());
        boolean open = !course.isArchived() && !course.isEnded(now);
        boolean studentVisible = user.type() == AccountType.STUDENT
                && studentMajor(user).map(course::isVisibleToStudent).orElse(false);
        return new CourseDetailView(
                course.id().value(),
                course.name(),
                course.schoolId().value(),
                course.createdBy().value(),
                course.status().name().toLowerCase(java.util.Locale.ROOT),
                course.isEnded(now),
                course.endAt(),
                course.archivedAt(),
                course.createdAt(),
                briefs(course.majorIds(), majorNames(course.majorIds())),
                teachers,
                studentCount,
                joined,
                canManage(user, course),
                studentVisible && open && !joined,
                joined && open,
                user.type() == AccountType.ADMIN && !course.isArchived(),
                user.type() == AccountType.ADMIN && course.isArchived());
    }

    private boolean canManage(CurrentUser user, Course course) {
        if (user.type() == AccountType.ADMIN) {
            return user.schoolId().equals(course.schoolId());
        }
        return user.type() == AccountType.TEACHER && course.hasActiveTeacher(user.accountId());
    }

    private List<MajorBrief> briefs(Collection<MajorId> majorIds, Map<MajorId, String> names) {
        return majorIds.stream()
                .map(majorId -> new MajorBrief(majorId.value(), names.getOrDefault(majorId, "")))
                .toList();
    }

    private Map<MajorId, String> majorNames(Collection<MajorId> majorIds) {
        if (majorIds.isEmpty()) {
            return Map.of();
        }
        return directory.findMajors(new LinkedHashSet<>(majorIds)).stream()
                .collect(Collectors.toMap(MajorSnapshot::id, MajorSnapshot::name, (left, right) -> left));
    }

    private Map<AccountId, String> accountNames(Collection<AccountId> accountIds) {
        if (accountIds.isEmpty()) {
            return Map.of();
        }
        return directory.findAccounts(new LinkedHashSet<>(accountIds)).stream()
                .collect(Collectors.toMap(AccountSnapshot::id, AccountSnapshot::username, (left, right) -> left));
    }

    /**
     * 状态筛选：active=启用中、ended=已结束、archived=已归档；空值不限。
     */
    private StatusFilter parseStatus(String status) {
        if (status == null) {
            return new StatusFilter(null, null);
        }
        return switch (status.toLowerCase(java.util.Locale.ROOT)) {
            case "active" -> new StatusFilter(CourseStatus.ACTIVE, Boolean.FALSE);
            case "ended" -> new StatusFilter(CourseStatus.ACTIVE, Boolean.TRUE);
            case "archived" -> new StatusFilter(CourseStatus.ARCHIVED, null);
            default -> throw new DomainException(
                    ErrorCode.VALIDATION_ERROR, "status 只支持 active / ended / archived");
        };
    }

    private record StatusFilter(CourseStatus status, Boolean ended) {
    }
}
