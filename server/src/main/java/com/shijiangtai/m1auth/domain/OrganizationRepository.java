package com.shijiangtai.m1auth.domain;

import com.shijiangtai.shared.domain.SchoolId;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 学校 / 专业 / 班级端口（12 §3.1）。非管理员角色只读（FR-M1-04）。
 */
public interface OrganizationRepository {

    // ---------------------------------------------------------------- schools
    Optional<School> findSchoolById(SchoolId id);

    /** 学校名称平台唯一。 */
    Optional<School> findSchoolByName(String name);

    List<School> listSchools();

    School saveSchool(School school);

    // ----------------------------------------------------------------- majors
    Optional<Major> findMajorById(UUID id);

    Optional<Major> findMajorByName(SchoolId schoolId, String name);

    /** @param includeInactive 管理员列表为 true；注册选项必须 false */
    List<Major> listMajors(SchoolId schoolId, boolean includeInactive);

    Major saveMajor(Major major);

    // ---------------------------------------------------------------- classes
    Optional<SchoolClass> findClassById(UUID id);

    Optional<SchoolClass> findClassByNaturalKey(UUID majorId, String grade, String name);

    /** @param includeInactive 管理员列表为 true；注册匹配必须 false（停用班级不可选，03 §3.5） */
    List<SchoolClass> listClasses(SchoolId schoolId, UUID majorId, String grade,
            boolean includeInactive);

    List<SchoolClass> listClassesByMajor(SchoolId schoolId, UUID majorId, boolean includeInactive);

    SchoolClass saveClass(SchoolClass schoolClass);

    /** 班级内学生数；&gt;0 时禁止改专业 / 年级（FR-M1-04）。 */
    long countStudentsInClass(UUID classId);

    // ----------------------------------------------------------------- grades
    /** 本校已导入的届别（去重、倒序）。 */
    List<String> listGrades(SchoolId schoolId);

    // ------------------------------------------------------------ 导入支撑
    /** 导入时按自然键批量 upsert 用；返回受影响行数。 */
    int upsertMajors(SchoolId schoolId, List<String> names, Instant now);

    int upsertClasses(SchoolId schoolId, List<ClassImportRow> rows, Instant now);

    /** 班级导入行：专业名 + 年级 + 班名，专业须已存在。 */
    record ClassImportRow(String majorName, String grade, String name, String status) {
    }
}
