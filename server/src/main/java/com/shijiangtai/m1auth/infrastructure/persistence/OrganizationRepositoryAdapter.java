package com.shijiangtai.m1auth.infrastructure.persistence;

import com.shijiangtai.m1auth.domain.ActiveStatus;
import com.shijiangtai.m1auth.domain.Major;
import com.shijiangtai.m1auth.domain.OrganizationRepository;
import com.shijiangtai.m1auth.domain.School;
import com.shijiangtai.m1auth.domain.SchoolClass;
import com.shijiangtai.shared.domain.SchoolId;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** 学校 / 专业 / 班级（12 §3.1）。停用采用 status 而非物理删除（FR-M1-04）。 */
@Repository
public class OrganizationRepositoryAdapter implements OrganizationRepository {

    private static final RowMapper<School> SCHOOL_MAPPER = (rs, i) -> new School(
            new SchoolId(rs.getObject("id", UUID.class)),
            rs.getString("name"),
            AccountRepositoryAdapter.toInstant(
                    rs.getObject("created_at", java.time.OffsetDateTime.class)),
            AccountRepositoryAdapter.toInstant(
                    rs.getObject("updated_at", java.time.OffsetDateTime.class)));

    private static final RowMapper<Major> MAJOR_MAPPER = (rs, i) -> new Major(
            rs.getObject("id", UUID.class),
            new SchoolId(rs.getObject("school_id", UUID.class)),
            rs.getString("name"),
            ActiveStatus.fromDb(rs.getString("status")),
            AccountRepositoryAdapter.toInstant(
                    rs.getObject("created_at", java.time.OffsetDateTime.class)),
            AccountRepositoryAdapter.toInstant(
                    rs.getObject("updated_at", java.time.OffsetDateTime.class)));

    private static final RowMapper<SchoolClass> CLASS_MAPPER = (rs, i) -> new SchoolClass(
            rs.getObject("id", UUID.class),
            new SchoolId(rs.getObject("school_id", UUID.class)),
            rs.getObject("major_id", UUID.class),
            rs.getString("grade"),
            rs.getString("name"),
            ActiveStatus.fromDb(rs.getString("status")),
            AccountRepositoryAdapter.toInstant(
                    rs.getObject("created_at", java.time.OffsetDateTime.class)),
            AccountRepositoryAdapter.toInstant(
                    rs.getObject("updated_at", java.time.OffsetDateTime.class)));

    private final NamedParameterJdbcTemplate jdbc;

    public OrganizationRepositoryAdapter(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ---------------------------------------------------------------- schools

    @Override
    public Optional<School> findSchoolById(SchoolId id) {
        return jdbc.query("select * from m1_auth.schools where id = :id",
                Map.of("id", id.value()), SCHOOL_MAPPER).stream().findFirst();
    }

    @Override
    public Optional<School> findSchoolByName(String name) {
        return jdbc.query("select * from m1_auth.schools where name = :name",
                Map.of("name", name), SCHOOL_MAPPER).stream().findFirst();
    }

    @Override
    public List<School> listSchools() {
        return jdbc.query("select * from m1_auth.schools order by name", Map.of(), SCHOOL_MAPPER);
    }

    @Override
    public School saveSchool(School school) {
        jdbc.update("""
                insert into m1_auth.schools (id, name, created_at, updated_at)
                values (:id, :name, :createdAt, :updatedAt)
                on conflict (id) do update set name = excluded.name, updated_at = excluded.updated_at
                """, new MapSqlParameterSource()
                .addValue("id", school.id().value())
                .addValue("name", school.name())
                .addValue("createdAt", AccountRepositoryAdapter.toOffset(school.createdAt()))
                .addValue("updatedAt", AccountRepositoryAdapter.toOffset(school.updatedAt())));
        return school;
    }

    // ----------------------------------------------------------------- majors

    @Override
    public Optional<Major> findMajorById(UUID id) {
        return jdbc.query("select * from m1_auth.majors where id = :id",
                Map.of("id", id), MAJOR_MAPPER).stream().findFirst();
    }

    @Override
    public Optional<Major> findMajorByName(SchoolId schoolId, String name) {
        return jdbc.query("select * from m1_auth.majors where school_id = :schoolId and name = :name",
                Map.of("schoolId", schoolId.value(), "name", name), MAJOR_MAPPER)
                .stream().findFirst();
    }

    @Override
    public List<Major> listMajors(SchoolId schoolId, boolean includeInactive) {
        String sql = "select * from m1_auth.majors where school_id = :schoolId"
                + (includeInactive ? "" : " and status = 'active'")
                + " order by name";
        return jdbc.query(sql, Map.of("schoolId", schoolId.value()), MAJOR_MAPPER);
    }

    @Override
    public Major saveMajor(Major major) {
        jdbc.update("""
                insert into m1_auth.majors (id, school_id, name, status, created_at, updated_at)
                values (:id, :schoolId, :name, :status, :createdAt, :updatedAt)
                on conflict (id) do update set
                    name = excluded.name, status = excluded.status, updated_at = excluded.updated_at
                """, new MapSqlParameterSource()
                .addValue("id", major.id())
                .addValue("schoolId", major.schoolId().value())
                .addValue("name", major.name())
                .addValue("status", major.status().dbValue())
                .addValue("createdAt", AccountRepositoryAdapter.toOffset(major.createdAt()))
                .addValue("updatedAt", AccountRepositoryAdapter.toOffset(major.updatedAt())));
        return major;
    }

    // ---------------------------------------------------------------- classes

    @Override
    public Optional<SchoolClass> findClassById(UUID id) {
        return jdbc.query("select * from m1_auth.classes where id = :id",
                Map.of("id", id), CLASS_MAPPER).stream().findFirst();
    }

    @Override
    public Optional<SchoolClass> findClassByNaturalKey(UUID majorId, String grade, String name) {
        return jdbc.query("""
                select * from m1_auth.classes
                where major_id = :majorId and grade = :grade and name = :name
                """, Map.of("majorId", majorId, "grade", grade, "name", name), CLASS_MAPPER)
                .stream().findFirst();
    }

    @Override
    public List<SchoolClass> listClasses(
            SchoolId schoolId, UUID majorId, String grade, boolean includeInactive) {
        StringBuilder sql = new StringBuilder(
                "select * from m1_auth.classes where school_id = :schoolId");
        Map<String, Object> params = new HashMap<>();
        params.put("schoolId", schoolId.value());
        if (majorId != null) {
            sql.append(" and major_id = :majorId");
            params.put("majorId", majorId);
        }
        if (grade != null && !grade.isBlank()) {
            sql.append(" and grade = :grade");
            params.put("grade", grade);
        }
        if (!includeInactive) {
            sql.append(" and status = 'active'");
        }
        sql.append(" order by grade desc, name");
        return jdbc.query(sql.toString(), params, CLASS_MAPPER);
    }

    @Override
    public List<SchoolClass> listClassesByMajor(
            SchoolId schoolId, UUID majorId, boolean includeInactive) {
        return listClasses(schoolId, majorId, null, includeInactive);
    }

    @Override
    public SchoolClass saveClass(SchoolClass schoolClass) {
        jdbc.update("""
                insert into m1_auth.classes
                    (id, school_id, major_id, grade, name, status, created_at, updated_at)
                values (:id, :schoolId, :majorId, :grade, :name, :status, :createdAt, :updatedAt)
                on conflict (id) do update set
                    major_id = excluded.major_id, grade = excluded.grade, name = excluded.name,
                    status = excluded.status, updated_at = excluded.updated_at
                """, new MapSqlParameterSource()
                .addValue("id", schoolClass.id())
                .addValue("schoolId", schoolClass.schoolId().value())
                .addValue("majorId", schoolClass.majorId())
                .addValue("grade", schoolClass.grade())
                .addValue("name", schoolClass.name())
                .addValue("status", schoolClass.status().dbValue())
                .addValue("createdAt", AccountRepositoryAdapter.toOffset(schoolClass.createdAt()))
                .addValue("updatedAt", AccountRepositoryAdapter.toOffset(schoolClass.updatedAt())));
        return schoolClass;
    }

    @Override
    public long countStudentsInClass(UUID classId) {
        Long count = jdbc.queryForObject(
                "select count(*) from m1_auth.student_profiles where class_id = :classId",
                Map.of("classId", classId), Long.class);
        return count == null ? 0L : count;
    }

    // ----------------------------------------------------------------- grades

    @Override
    public List<String> listGrades(SchoolId schoolId) {
        return jdbc.queryForList("""
                select distinct grade from m1_auth.classes
                where school_id = :schoolId and status = 'active'
                order by grade desc
                """, Map.of("schoolId", schoolId.value()), String.class);
    }

    // -------------------------------------------------------------- 导入支撑

    @Override
    public int upsertMajors(SchoolId schoolId, List<String> names, Instant now) {
        if (names.isEmpty()) {
            return 0;
        }
        MapSqlParameterSource[] batch = names.stream()
                .map(name -> new MapSqlParameterSource()
                        .addValue("id", UUID.randomUUID())
                        .addValue("schoolId", schoolId.value())
                        .addValue("name", name)
                        .addValue("now", AccountRepositoryAdapter.toOffset(now)))
                .toArray(MapSqlParameterSource[]::new);
        int[] affected = jdbc.batchUpdate("""
                insert into m1_auth.majors (id, school_id, name, status, created_at, updated_at)
                values (:id, :schoolId, :name, 'active', :now, :now)
                on conflict (school_id, name) do update set updated_at = excluded.updated_at
                """, batch);
        return affected.length;
    }

    @Override
    public int upsertClasses(SchoolId schoolId, List<ClassImportRow> rows, Instant now) {
        if (rows.isEmpty()) {
            return 0;
        }
        Map<String, UUID> majorIds = new HashMap<>();
        List<MapSqlParameterSource> batch = new java.util.ArrayList<>();
        for (ClassImportRow row : rows) {
            UUID majorId = majorIds.computeIfAbsent(row.majorName(), name -> findMajorByName(
                            schoolId, name)
                    .orElseThrow(() -> new IllegalArgumentException("专业不存在: " + name))
                    .id());
            batch.add(new MapSqlParameterSource()
                    .addValue("id", UUID.randomUUID())
                    .addValue("schoolId", schoolId.value())
                    .addValue("majorId", majorId)
                    .addValue("grade", row.grade())
                    .addValue("name", row.name())
                    .addValue("status", row.status() == null ? "active" : row.status())
                    .addValue("now", AccountRepositoryAdapter.toOffset(now)));
        }
        int[] affected = jdbc.batchUpdate("""
                insert into m1_auth.classes
                    (id, school_id, major_id, grade, name, status, created_at, updated_at)
                values (:id, :schoolId, :majorId, :grade, :name, :status, :now, :now)
                on conflict (major_id, grade, name) do update set
                    status = excluded.status, updated_at = excluded.updated_at
                """, batch.toArray(MapSqlParameterSource[]::new));
        return affected.length;
    }
}
