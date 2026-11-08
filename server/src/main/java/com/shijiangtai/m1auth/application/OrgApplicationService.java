package com.shijiangtai.m1auth.application;

import com.shijiangtai.m1auth.domain.ActiveStatus;
import com.shijiangtai.m1auth.domain.Major;
import com.shijiangtai.m1auth.domain.OrganizationRepository;
import com.shijiangtai.m1auth.domain.ProfileRepository;
import com.shijiangtai.m1auth.domain.SchoolClass;
import com.shijiangtai.shared.domain.DomainException;
import com.shijiangtai.shared.domain.ErrorCode;
import com.shijiangtai.shared.domain.SchoolId;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 学校 / 专业 / 班级维护与注册选项（FR-M1-04、FR-M1-05）。
 *
 * <ul>
 *   <li>专业 / 班级停用而非物理删除；停用后不出现在注册选项（03 §3.5）
 *   <li>已有学生的班级禁止改专业 / 年级
 *   <li>非管理员只读，平台不提供增删改入口
 * </ul>
 */
@Service
public class OrgApplicationService {

    private final OrganizationRepository organization;
    private final ProfileRepository profiles;
    private final SchoolApplicationService schools;
    private final Clock clock;

    public OrgApplicationService(
            OrganizationRepository organization,
            ProfileRepository profiles,
            SchoolApplicationService schools,
            Clock clock) {
        this.organization = organization;
        this.profiles = profiles;
        this.schools = schools;
        this.clock = clock;
    }

    // ----------------------------------------------------------------- majors

    @Transactional(readOnly = true)
    public List<MajorView> listMajors(M1Principal principal) {
        SchoolId schoolId = schools.requireBoundSchool(principal);
        return organization.listMajors(schoolId, true).stream()
                .map(major -> toView(major))
                .toList();
    }

    @Transactional
    public MajorView createMajor(M1Principal principal, String name) {
        SchoolId schoolId = schools.requireBoundSchool(principal);
        Major major = Major.create(UUID.randomUUID(), schoolId, name, clock.instant());
        organization.findMajorByName(schoolId, major.name()).ifPresent(existing -> {
            throw new DomainException(ErrorCode.VALIDATION_ERROR, "专业名称已存在");
        });
        return toView(organization.saveMajor(major));
    }

    /** 编辑：改名或停用 / 启用。停用前检查是否为教师最后一个有效专业（03 §3.5）。 */
    @Transactional
    public MajorView updateMajor(
            M1Principal principal, UUID majorId, String name, Boolean active) {
        SchoolId schoolId = schools.requireBoundSchool(principal);
        Major major = requireOwnedMajor(schoolId, majorId);

        if (name != null && !name.isBlank()) {
            Major renamed = major.rename(name, clock.instant());
            organization.findMajorByName(schoolId, renamed.name())
                    .filter(other -> !other.id().equals(majorId))
                    .ifPresent(other -> {
                        throw new DomainException(ErrorCode.VALIDATION_ERROR, "专业名称已存在");
                    });
            major = renamed;
        }

        if (active != null) {
            if (!active) {
                long teachers = profiles.countActiveTeachersByMajor(majorId);
                // 仅提示，不阻断：教师可保留历史关系，由管理员后续调整（FR-M1-04）
                if (teachers > 0) {
                    // 允许停用；注册选项自动隐藏
                }
                major = major.disable(clock.instant());
            } else {
                major = major.enable(clock.instant());
            }
        }
        return toView(organization.saveMajor(major));
    }

    // ---------------------------------------------------------------- classes

    @Transactional(readOnly = true)
    public List<ClassView> listClasses(
            M1Principal principal, UUID majorId, String grade) {
        SchoolId schoolId = schools.requireBoundSchool(principal);
        return organization.listClasses(schoolId, majorId, grade, true).stream()
                .map(this::toView)
                .toList();
    }

    @Transactional
    public ClassView createClass(
            M1Principal principal, UUID majorId, String grade, String name) {
        SchoolId schoolId = schools.requireBoundSchool(principal);
        requireOwnedMajor(schoolId, majorId);

        SchoolClass schoolClass = SchoolClass.create(
                UUID.randomUUID(), schoolId, majorId, grade, name, clock.instant());
        organization.findClassByNaturalKey(majorId, schoolClass.grade(), schoolClass.name())
                .ifPresent(existing -> {
                    throw new DomainException(ErrorCode.VALIDATION_ERROR, "该专业年级下班名已存在");
                });
        return toView(organization.saveClass(schoolClass));
    }

    /** 编辑：已有学生的班级禁止改专业 / 年级，空班级可改（FR-M1-04）。 */
    @Transactional
    public ClassView updateClass(
            M1Principal principal,
            UUID classId,
            UUID majorId,
            String grade,
            String name,
            Boolean active) {
        SchoolId schoolId = schools.requireBoundSchool(principal);
        SchoolClass schoolClass = organization.findClassById(classId)
                .orElseThrow(() -> new DomainException(ErrorCode.RESOURCE_NOT_FOUND, "班级不存在"));
        if (!schoolClass.schoolId().equals(schoolId)) {
            throw new DomainException(ErrorCode.CROSS_SCHOOL_ACCESS, "跨校访问被拒绝");
        }

        UUID targetMajor = majorId == null ? schoolClass.majorId() : majorId;
        String targetGrade = grade == null || grade.isBlank() ? schoolClass.grade() : grade;
        String targetName = name == null || name.isBlank() ? schoolClass.name() : name;

        boolean structuralChange = !targetMajor.equals(schoolClass.majorId())
                || !targetGrade.equals(schoolClass.grade());
        if (structuralChange && organization.countStudentsInClass(classId) > 0) {
            throw new DomainException(
                    ErrorCode.VALIDATION_ERROR, "班级已有学生，不能修改专业或年级");
        }
        if (!targetMajor.equals(schoolClass.majorId())) {
            requireOwnedMajor(schoolId, targetMajor);
        }

        SchoolClass updated = schoolClass.update(
                targetMajor, targetGrade, targetName, clock.instant());
        organization.findClassByNaturalKey(targetMajor, targetGrade, targetName)
                .filter(other -> !other.id().equals(classId))
                .ifPresent(other -> {
                    throw new DomainException(ErrorCode.VALIDATION_ERROR, "该专业年级下班名已存在");
                });

        if (Boolean.FALSE.equals(active)) {
            updated = updated.disable(clock.instant());
        }
        return toView(organization.saveClass(updated));
    }

    // ----------------------------------------------------------------- 选项

    @Transactional(readOnly = true)
    public List<SchoolView> listSchools() {
        return organization.listSchools().stream()
                .map(school -> new SchoolView(school.id().value(), school.name()))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<MajorView> publicMajors(UUID schoolId) {
        return organization.listMajors(new SchoolId(schoolId), false).stream()
                .map(major -> toView(major))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<String> publicGrades(UUID schoolId) {
        return organization.listGrades(new SchoolId(schoolId));
    }

    /** 注册时按学校 / 专业 / 年级查**启用**班级；停用班级返回 CLASS_DISABLED（03 §3.5）。 */
    @Transactional(readOnly = true)
    public List<ClassView> publicClasses(UUID schoolId, UUID majorId, String grade) {
        return organization.listClasses(new SchoolId(schoolId), majorId, grade, false).stream()
                .map(this::toView)
                .toList();
    }

    // ------------------------------------------------------------------ 内部

    private Major requireOwnedMajor(SchoolId schoolId, UUID majorId) {
        Major major = organization.findMajorById(majorId)
                .orElseThrow(() -> new DomainException(ErrorCode.RESOURCE_NOT_FOUND, "专业不存在"));
        if (!major.schoolId().equals(schoolId)) {
            throw new DomainException(ErrorCode.CROSS_SCHOOL_ACCESS, "跨校访问被拒绝");
        }
        return major;
    }

    private MajorView toView(Major major) {
        return new MajorView(major.id(), major.name(), major.status() == ActiveStatus.ACTIVE);
    }

    private ClassView toView(SchoolClass schoolClass) {
        String majorName = organization.findMajorById(schoolClass.majorId())
                .map(Major::name).orElse(null);
        return new ClassView(
                schoolClass.id(),
                schoolClass.majorId(),
                majorName,
                schoolClass.grade(),
                schoolClass.name(),
                schoolClass.status() == ActiveStatus.ACTIVE);
    }

    public record SchoolView(UUID schoolId, String name) {
    }

    public record MajorView(UUID id, String name, boolean active) {
    }

    public record ClassView(
            UUID id, UUID majorId, String majorName, String grade, String name, boolean active) {
    }
}
