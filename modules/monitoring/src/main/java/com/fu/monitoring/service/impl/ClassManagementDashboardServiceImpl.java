package com.fu.monitoring.service.impl;

import com.fu.achievement.repository.BadgeRepository;
import com.fu.achievement.repository.UserBadgesRepository;
import com.fu.achievement.repository.UserStatsRepository;
import com.fu.clazz.entity.Clazz;
import com.fu.clazz.repository.ClassRepository;
import com.fu.course.entity.Course;
import com.fu.course.repository.CourseRepository;
import com.fu.monitoring.dto.response.AchievementMetricsResponse;
import com.fu.monitoring.dto.response.BadgeDto;
import com.fu.monitoring.dto.response.ClassManagementDashboardResponse;
import com.fu.monitoring.dto.response.ClassStatusChartResponse;
import com.fu.monitoring.dto.response.DashboardKpiResponse;
import com.fu.monitoring.dto.response.MonthlyEnrollmentDto;
import com.fu.monitoring.dto.response.PopularBadgesResponse;
import com.fu.monitoring.dto.response.PriorityClassResponse;
import com.fu.monitoring.dto.response.PriorityClassesResponse;
import com.fu.monitoring.dto.response.RareBadgesResponse;
import com.fu.monitoring.dto.response.StudentTrendResponse;
import com.fu.monitoring.service.ClassManagementDashboardService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class ClassManagementDashboardServiceImpl implements ClassManagementDashboardService {

    private final ClassRepository classRepository;
    private final BadgeRepository badgeRepository;
    private final UserBadgesRepository userBadgesRepository;
    private final UserStatsRepository userStatsRepository;
    private final CourseRepository courseRepository;

    private static final int DAYS_THRESHOLD = 7;

    // =====================================================
    // AGGREGATE DASHBOARD
    // =====================================================

    @Override
    public ClassManagementDashboardResponse getDashboard() {
        log.debug("Building complete class management dashboard");
        return ClassManagementDashboardResponse.builder()
                .kpis(getKpiSummary())
                .statusChart(getStatusChart())
                .studentTrend(getStudentTrend())
                .priorityClasses(getPriorityClasses())
                .achievements(getAchievementMetrics())
                .popularBadges(getPopularBadges())
                .rareBadges(getRareBadges())
                .generatedAt(LocalDateTime.now())
                .build();
    }

    // =====================================================
    // KPI SUMMARY
    // =====================================================

    @Override
    public DashboardKpiResponse getKpiSummary() {
        return DashboardKpiResponse.builder()
                .totalClasses(nullSafe(classRepository.countAllClasses()))
                .activeClasses(nullSafe(classRepository.countActiveClasses()))
                .totalStudents(nullSafe(classRepository.countTotalStudents()))
                .totalTeachers(nullSafe(classRepository.countUniqueTeachers()))
                .build();
    }

    // =====================================================
    // STATUS CHART
    // =====================================================

    @Override
    public ClassStatusChartResponse getStatusChart() {
        return ClassStatusChartResponse.builder()
                .active(nullSafe(classRepository.countByStatusActive()))
                .closed(nullSafe(classRepository.countByStatusClosed()))
                .notStarted(nullSafe(classRepository.countByStatusNotStarted()))
                .build();
    }

    // =====================================================
    // STUDENT ENROLLMENT TREND
    // =====================================================

    @Override
    public StudentTrendResponse getStudentTrend() {
        List<Object[]> results = classRepository.getMonthlyEnrollmentTrend();

        List<MonthlyEnrollmentDto> data = results.stream()
                .map(row -> {
                    // row[0] is a Timestamp from DATE_TRUNC
                    LocalDate monthStart = toLocalDate(row[0]);
                    Long count = row[1] != null ? ((Number) row[1]).longValue() : 0L;
                    return MonthlyEnrollmentDto.builder()
                            .month(formatMonthVietnamese(monthStart))
                            .enrollmentCount(count)
                            .build();
                })
                .collect(Collectors.toList());

        return StudentTrendResponse.builder()
                .data(data)
                .build();
    }

    // =====================================================
    // PRIORITY CLASSES
    // =====================================================

    @Override
    public PriorityClassesResponse getPriorityClasses() {
        LocalDate today = LocalDate.now();
        LocalDate rangeEnd = today.plusDays(DAYS_THRESHOLD);

        // Collect all flagged class IDs with their reasons and priority levels
        Set<UUID> priorityClassIds = new HashSet<>();
        Map<UUID, List<String>> classReasons = new HashMap<>();
        Map<UUID, String> classPriorities = new HashMap<>();

        // Criterion 1: OVERDUE → CRITICAL
        classRepository.findOverdueClasses(today).forEach(c -> {
            priorityClassIds.add(c.getId());
            classReasons.computeIfAbsent(c.getId(), k -> new ArrayList<>()).add("Đã qua thời gian lớp học kết thúc, hãy đóng lại lớp.");
            classPriorities.put(c.getId(), "CRITICAL");
        });

        // Criterion 2: ENDING_SOON → HIGH
        classRepository.findClassesEndingSoon(today, rangeEnd).forEach(c -> {
            priorityClassIds.add(c.getId());
            classReasons.computeIfAbsent(c.getId(), k -> new ArrayList<>()).add("Lớp này sắp kết thúc.");
            classPriorities.putIfAbsent(c.getId(), "HIGH");
        });

        // Criterion 3: STARTING_SOON → MEDIUM
        classRepository.findClassesStartingSoon(today, rangeEnd).forEach(c -> {
            priorityClassIds.add(c.getId());
            classReasons.computeIfAbsent(c.getId(), k -> new ArrayList<>()).add("Lớp này sắp được mở.");
            classPriorities.putIfAbsent(c.getId(), "MEDIUM");
        });

        // Criterion 4: INCOMPLETE → LOW
        classRepository.findIncompleteClasses().forEach(c -> {
            priorityClassIds.add(c.getId());
            classReasons.computeIfAbsent(c.getId(), k -> new ArrayList<>()).add("Số lượng học viên trong lớp chưa đầy.");
            classPriorities.putIfAbsent(c.getId(), "LOW");
        });

        List<PriorityClassResponse> responses = priorityClassIds.stream()
                .map(classId -> {
                    Clazz clazz = classRepository.findById(classId).orElse(null);
                    if (clazz == null) return null;
                    Course course = courseRepository.findById(clazz.getCourseId()).orElse(null);
                    Integer enrolledCount = classRepository.getEnrolledCount(classId);
                    return PriorityClassResponse.builder()
                            .id(clazz.getId())
                            .name(clazz.getName())
                            .courseName(course != null ? course.getName() : "Unknown Course")
                            .enrolledCount(enrolledCount != null ? enrolledCount : 0)
                            .maxStudents(clazz.getMaxStudents())
                            .startDate(clazz.getStartDate())
                            .endDate(clazz.getEndDate())
                            .expectedEndDate(clazz.getExpectedEndDate())
                            .status(clazz.getStatus())
                            .reasons(classReasons.get(classId))
                            .priorityLevel(classPriorities.get(classId))
                            .build();
                })
                .filter(Objects::nonNull)
                .sorted(priorityComparator())
                .limit(10)
                .collect(Collectors.toList());

        long criticalCount = responses.stream()
                .filter(r -> "CRITICAL".equals(r.getPriorityLevel()))
                .count();

        return PriorityClassesResponse.builder()
                .content(responses)
                .totalElements((long) responses.size())
                .totalPages(1)
                .criticalCount((int) criticalCount)
                .build();
    }

    // =====================================================
    // ACHIEVEMENT METRICS
    // =====================================================

    @Override
    public AchievementMetricsResponse getAchievementMetrics() {
        // Total rows in user_badges = total times any badge was awarded
        long totalBadgesAwarded = userBadgesRepository.count();

        // totalBadgeReceipts = same count — every row is one badge receipt
        long totalBadgeReceipts = totalBadgesAwarded;

        // Badge award rate: users who earned at least 1 badge / total users with stats
        long totalUsersWithStats = userStatsRepository.count();
        long usersWithBadges = userBadgesRepository.findAll().stream()
                .map(ub -> ub.getUserId())
                .distinct()
                .count();

        double badgeAwardRate = totalUsersWithStats > 0
                ? Math.round((usersWithBadges * 100.0 / totalUsersWithStats) * 100.0) / 100.0
                : 0.0;

        return AchievementMetricsResponse.builder()
                .totalBadgesAwarded(totalBadgesAwarded)
                .badgeAwardRate(badgeAwardRate)
                .totalBadgeReceipts(totalBadgeReceipts)
                .build();
    }

    // =====================================================
    // POPULAR BADGES
    // =====================================================

    @Override
    public PopularBadgesResponse getPopularBadges() {
        List<Object[]> results = badgeRepository.findTop5PopularBadges();

        List<BadgeDto> badges = results.stream()
                .map(row -> BadgeDto.builder()
                        .id(toUUID(row[0]))
                        .name((String) row[1])
                        .awardCount(row[2] != null ? ((Number) row[2]).longValue() : 0L)
                        .build())
                .collect(Collectors.toList());

        return PopularBadgesResponse.builder()
                .badges(badges)
                .build();
    }

    // =====================================================
    // RARE BADGES — "Thành tích ít người đạt"
    // =====================================================

    @Override
    public RareBadgesResponse getRareBadges() {
        List<Object[]> results = badgeRepository.findBottom5RareBadges();

        List<RareBadgesResponse.RareBadgeDto> badges = results.stream()
                .map(row -> RareBadgesResponse.RareBadgeDto.builder()
                        .id(toUUID(row[0]))
                        .name((String) row[1])
                        .awardCount(row[2] != null ? ((Number) row[2]).longValue() : 0L)
                        .label("Chú ý")
                        .build())
                .collect(Collectors.toList());

        return RareBadgesResponse.builder()
                .badges(badges)
                .build();
    }

    // =====================================================
    // HELPERS
    // =====================================================

    private Comparator<PriorityClassResponse> priorityComparator() {
        Map<String, Integer> order = Map.of(
                "CRITICAL", 0,
                "HIGH", 1,
                "MEDIUM", 2,
                "LOW", 3
        );
        return Comparator.comparingInt(r -> order.getOrDefault(r.getPriorityLevel(), 4));
    }

    private String formatMonthVietnamese(LocalDate date) {
        return "THÁNG " + date.getMonthValue();
    }

    private LocalDate toLocalDate(Object value) {
        if (value instanceof java.sql.Date d) return d.toLocalDate();
        if (value instanceof Timestamp ts) return ts.toLocalDateTime().toLocalDate();
        if (value instanceof LocalDate ld) return ld;
        if (value instanceof java.time.LocalDateTime ldt) return ldt.toLocalDate();
        if (value instanceof java.time.OffsetDateTime odt) {
            // Convert UTC → system local timezone to get the correct month
            return odt.atZoneSameInstant(java.time.ZoneId.systemDefault()).toLocalDate();
        }
        if (value instanceof java.time.ZonedDateTime zdt) {
            return zdt.withZoneSameInstant(java.time.ZoneId.systemDefault()).toLocalDate();
        }
        // Fallback: parse the toString() representation
        if (value != null) {
            String s = value.toString();
            if (s.contains("T")) {
                return java.time.OffsetDateTime
                        .parse(s, java.time.format.DateTimeFormatter.ISO_DATE_TIME)
                        .atZoneSameInstant(java.time.ZoneId.systemDefault())
                        .toLocalDate();
            }
            return LocalDate.parse(s.substring(0, 10));
        }
        throw new IllegalArgumentException("Cannot convert to LocalDate: " + value);
    }

    private UUID toUUID(Object value) {
        if (value instanceof UUID u) return u;
        if (value instanceof String s) return UUID.fromString(s);
        throw new IllegalArgumentException("Cannot convert to UUID: " + value);
    }

    private long nullSafe(Long value) {
        return value != null ? value : 0L;
    }
}
