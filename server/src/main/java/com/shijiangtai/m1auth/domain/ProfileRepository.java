package com.shijiangtai.m1auth.domain;

import com.shijiangtai.shared.domain.AccountId;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 角色档案与教师专业端口（12 §3.1 student_profiles / teacher_profiles / teacher_majors）。
 * 年级与专业由班级带出，不冗余存储（M1 §3.1）。
 */
public interface ProfileRepository {

    /** 学生所在班级；非学生或无档案返回空。 */
    Optional<UUID> findStudentClassId(AccountId accountId);

    void insertStudentProfile(AccountId accountId, UUID classId);

    void insertTeacherProfile(AccountId accountId);

    /** 教师当前有效专业（{@code status='active'}）。 */
    List<UUID> findActiveMajorIds(AccountId accountId);

    /**
     * 用新专业集合替换教师有效专业：旧 active 行置 inactive 并写 {@code effective_to}，
     * 新行 active。历史保留（FR-M1-08）。
     */
    void replaceActiveMajors(AccountId accountId, List<UUID> majorIds, Instant now);

    /** 本校教师账号 ID 列表（管理员教师管理页）。 */
    List<AccountId> findTeacherIdsBySchool(UUID schoolId);

    /** 某专业下仍有有效归属的教师人数；停用专业前校验用（03 §3.5）。 */
    long countActiveTeachersByMajor(UUID majorId);
}
