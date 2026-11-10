package com.shijiangtai.m7teacher.api;

import com.shijiangtai.m7teacher.application.LeaderboardQueryService;
import com.shijiangtai.m7teacher.application.StatisticsQueryService;
import com.shijiangtai.m7teacher.application.view.CourseStatsView;
import com.shijiangtai.m7teacher.application.view.LeaderboardView;
import com.shijiangtai.m7teacher.application.view.TaskStatsView;
import com.shijiangtai.shared.application.CurrentUserProvider;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class StatisticsController {

    private final StatisticsQueryService statisticsQueryService;
    private final LeaderboardQueryService leaderboardQueryService;
    private final CurrentUserProvider currentUserProvider;

    public StatisticsController(
            StatisticsQueryService statisticsQueryService,
            LeaderboardQueryService leaderboardQueryService,
            CurrentUserProvider currentUserProvider) {
        this.statisticsQueryService = statisticsQueryService;
        this.leaderboardQueryService = leaderboardQueryService;
        this.currentUserProvider = currentUserProvider;
    }

    @GetMapping("/tasks/{taskId}/stats")
    public TaskStatsView taskStats(
            @PathVariable UUID taskId,
            @RequestParam(name = "class_id", required = false) UUID classId) {
        return statisticsQueryService.taskStats(
                currentUserProvider.require(), taskId, classId);
    }

    @GetMapping("/tasks/{taskId}/leaderboard")
    public LeaderboardView leaderboard(
            @PathVariable UUID taskId,
            @RequestParam(name = "class_id", required = false) UUID classId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return leaderboardQueryService.leaderboard(
                currentUserProvider.require(), taskId, classId, page, size);
    }

    @GetMapping("/courses/{courseId}/stats")
    public CourseStatsView courseStats(
            @PathVariable UUID courseId,
            @RequestParam(name = "class_id", required = false) UUID classId) {
        return statisticsQueryService.courseStats(
                currentUserProvider.require(), courseId, classId);
    }
}
