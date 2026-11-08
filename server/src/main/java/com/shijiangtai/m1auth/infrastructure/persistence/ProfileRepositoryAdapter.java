package com.shijiangtai.m1auth.infrastructure.persistence;

import com.shijiangtai.m1auth.domain.ProfileRepository;
import com.shijiangtai.shared.domain.AccountId;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** 角色档案与教师专业（12 §3.1 student_profiles / teacher_profiles / teacher_majors）。 */
@Repository
public class ProfileRepositoryAdapter implements ProfileRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public ProfileRepositoryAdapter(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<UUID> findStudentClassId(AccountId accountId) {
        return jdbc.queryForList(
                        "select class_id from m1_auth.student_profiles where account_id = :accountId",
                        Map.of("accountId", accountId.value()),
                        UUID.class)
                .stream()
                .findFirst();
    }

    @Override
    public void insertStudentProfile(AccountId accountId, UUID classId) {
        jdbc.update("""
                insert into m1_auth.student_profiles (account_id, class_id, created_at, updated_at)
                values (:accountId, :classId, now(), now())
                on conflict (account_id) do update set
                    class_id = excluded.class_id, updated_at = now()
                """, Map.of("accountId", accountId.value(), "classId", classId));
    }

    @Override
    public void insertTeacherProfile(AccountId accountId) {
        jdbc.update("""
                insert into m1_auth.teacher_profiles (account_id, created_at, updated_at)
                values (:accountId, now(), now())
                on conflict (account_id) do nothing
                """, Map.of("accountId", accountId.value()));
    }

    @Override
    public List<UUID> findActiveMajorIds(AccountId accountId) {
        return jdbc.queryForList("""
                select major_id from m1_auth.teacher_majors
                where account_id = :accountId and status = 'active'
                order by effective_from
                """, Map.of("accountId", accountId.value()), UUID.class);
    }

    @Override
    public void replaceActiveMajors(AccountId accountId, List<UUID> majorIds, Instant now) {
        jdbc.update("""
                update m1_auth.teacher_majors
                set status = 'inactive', effective_to = :now, updated_at = :now
                where account_id = :accountId and status = 'active'
                """, new MapSqlParameterSource()
                .addValue("accountId", accountId.value())
                .addValue("now", AccountRepositoryAdapter.toOffset(now)));

        if (majorIds.isEmpty()) {
            return;
        }
        MapSqlParameterSource[] batch = majorIds.stream()
                .map(majorId -> new MapSqlParameterSource()
                        .addValue("id", UUID.randomUUID())
                        .addValue("accountId", accountId.value())
                        .addValue("majorId", majorId)
                        .addValue("now", AccountRepositoryAdapter.toOffset(now)))
                .toArray(MapSqlParameterSource[]::new);
        jdbc.batchUpdate("""
                insert into m1_auth.teacher_majors
                    (id, account_id, major_id, status, effective_from, created_at, updated_at)
                values (:id, :accountId, :majorId, 'active', :now, :now, :now)
                """, batch);
    }

    @Override
    public List<AccountId> findTeacherIdsBySchool(UUID schoolId) {
        return jdbc.queryForList("""
                select a.id from m1_auth.accounts a
                join m1_auth.account_types t on t.id = a.account_type_id
                where a.school_id = :schoolId and t.code = 'teacher'
                order by a.username
                """, Map.of("schoolId", schoolId), UUID.class)
                .stream()
                .map(AccountId::new)
                .toList();
    }

    @Override
    public long countActiveTeachersByMajor(UUID majorId) {
        Long count = jdbc.queryForObject("""
                select count(*) from m1_auth.teacher_majors
                where major_id = :majorId and status = 'active'
                """, Map.of("majorId", majorId), Long.class);
        return count == null ? 0L : count;
    }
}
