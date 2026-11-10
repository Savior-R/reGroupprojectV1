package com.shijiangtai.m2course.application.port;

import com.shijiangtai.m2course.domain.MajorId;
import com.shijiangtai.shared.application.AccountType;
import com.shijiangtai.shared.domain.AccountId;
import com.shijiangtai.shared.domain.ClassId;
import com.shijiangtai.shared.domain.SchoolId;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * M1 组织数据的只读端口：账号、学校、专业、班级、教师专业范围、学生班级。
 *
 * <p>M1（m1auth）本阶段尚未实现（在 feat/m1-auth 分支），M2 按 docs/design/02 的账号查询契约
 * 以只读端口开发；端口实现见 {@code infrastructure/crossmodule}。M2 不写 M1 任何表。
 */
public interface M1DirectoryPort {

    Optional<AccountSnapshot> findAccount(AccountId accountId);

    List<AccountSnapshot> findAccounts(Collection<AccountId> accountIds);

    Optional<MajorSnapshot> findMajor(MajorId majorId);

    List<MajorSnapshot> findMajors(Collection<MajorId> majorIds);

    /** 教师当前有效的专业范围。 */
    List<MajorId> activeMajorIds(AccountId teacherId);

    Optional<ClassSnapshot> findClass(ClassId classId);

    List<StudentSnapshot> findStudentsByClass(ClassId classId);

    Optional<StudentSnapshot> findStudent(AccountId accountId);

    List<StudentSnapshot> findStudents(Collection<AccountId> accountIds);

    record AccountSnapshot(AccountId id, String username, SchoolId schoolId, AccountType type) {
    }

    record MajorSnapshot(MajorId id, SchoolId schoolId, String name, boolean active) {
    }

    record ClassSnapshot(ClassId id, SchoolId schoolId, MajorId majorId, String grade, String name, boolean active) {
        public String displayName() {
            return grade + " " + name;
        }
    }

    record StudentSnapshot(AccountId id, String username, SchoolId schoolId, ClassId classId, MajorId majorId) {
    }
}
