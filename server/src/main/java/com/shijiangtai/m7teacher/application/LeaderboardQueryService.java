package com.shijiangtai.m7teacher.application;

import com.shijiangtai.m7teacher.application.port.UpstreamQueryPort;
import com.shijiangtai.m7teacher.application.view.LeaderboardItem;
import com.shijiangtai.m7teacher.application.view.LeaderboardView;
import com.shijiangtai.m7teacher.domain.FinalScore;
import com.shijiangtai.m7teacher.domain.FinalScoreRepository;
import com.shijiangtai.m7teacher.domain.FinalScoreStatus;
import com.shijiangtai.shared.application.CurrentUser;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Completed-only leaderboard with NULLS LAST ordering (07 §7.3, FR-M7-05).
 */
@Service
public class LeaderboardQueryService {

    private final M7AccessPolicy accessPolicy;
    private final UpstreamQueryPort upstream;
    private final FinalScoreRepository finalScoreRepository;

    public LeaderboardQueryService(
            M7AccessPolicy accessPolicy,
            UpstreamQueryPort upstream,
            FinalScoreRepository finalScoreRepository) {
        this.accessPolicy = accessPolicy;
        this.upstream = upstream;
        this.finalScoreRepository = finalScoreRepository;
    }

    public LeaderboardView leaderboard(
            CurrentUser user, UUID taskId, UUID classId, int page, int size) {
        TaskContext context = accessPolicy.requireTeacherTask(user, taskId);
        accessPolicy.requirePublished(context);
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(size, 1), 100);

        List<FinalScore> completed = finalScoreRepository.findByTaskId(taskId).stream()
                .filter(score -> score.status() == FinalScoreStatus.COMPLETED)
                .filter(score -> classId == null || classId.equals(score.classIdSnapshot()))
                .sorted(Comparator
                        .comparing(FinalScore::finalTotal,
                                Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(FinalScore::teacherTotal,
                                Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(FinalScore::aiTotal,
                                Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(score -> score.studentAccountId().toString()))
                .toList();

        int from = Math.min((safePage - 1) * safeSize, completed.size());
        int to = Math.min(from + safeSize, completed.size());
        List<FinalScore> pageRows = completed.subList(from, to);

        Set<UUID> accountIds = new HashSet<>();
        pageRows.forEach(score -> accountIds.add(score.studentAccountId()));
        Map<UUID, UpstreamQueryPort.Account> accounts = upstream.findAccounts(accountIds);
        Set<UUID> classIds = new HashSet<>();
        pageRows.forEach(score -> {
            if (score.classIdSnapshot() != null) {
                classIds.add(score.classIdSnapshot());
            }
        });
        Map<UUID, String> classNames = upstream.findClassNames(classIds);

        List<LeaderboardItem> items = new java.util.ArrayList<>();
        for (int index = 0; index < pageRows.size(); index++) {
            FinalScore score = pageRows.get(index);
            UpstreamQueryPort.Account account = accounts.get(score.studentAccountId());
            items.add(new LeaderboardItem(
                    from + index + 1,
                    score.studentAccountId(),
                    account == null ? score.studentAccountId().toString() : account.username(),
                    score.classIdSnapshot() == null
                            ? "未分班"
                            : classNames.getOrDefault(score.classIdSnapshot(), "未分班"),
                    score.aiTotal(),
                    score.teacherTotal(),
                    score.finalTotal()));
        }
        return new LeaderboardView(taskId, classId, items, completed.size(), safePage, safeSize);
    }
}
