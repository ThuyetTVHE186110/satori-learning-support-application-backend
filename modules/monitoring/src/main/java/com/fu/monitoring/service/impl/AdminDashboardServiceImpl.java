package com.fu.monitoring.service.impl;

import com.fu.assignment.repository.AssignmentSubmissionRepository;
import com.fu.conversation.repository.ChatSessionRepository;
import com.fu.monitoring.dto.response.AdminDashboardResponse;
import com.fu.monitoring.service.AdminDashboardService;
import com.fu.practice.repository.PracticeSessionRepository;
import com.fu.roleplay.repository.ConversationSessionRepository;
import com.fu.user.entity.UserRole;
import com.fu.user.repository.UserRepository;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthEndpoint;
import org.springframework.boot.actuate.health.HealthComponent;
import org.springframework.boot.actuate.health.Status;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class AdminDashboardServiceImpl implements AdminDashboardService {

    private final UserRepository userRepository;
    private final PracticeSessionRepository practiceSessionRepository;
    private final ChatSessionRepository chatSessionRepository;
    private final ConversationSessionRepository conversationSessionRepository;
    private final AssignmentSubmissionRepository submissionRepository;
    private final HealthEndpoint healthEndpoint;
    private final MeterRegistry meterRegistry;

    @Override
    public AdminDashboardResponse getDashboard() {
        LocalDate today = LocalDate.now();
        LocalDateTime startOfToday = LocalDateTime.of(today, LocalTime.MIDNIGHT);
        LocalDateTime startOfTomorrow = startOfToday.plusDays(1);
        LocalDateTime sevenDaysAgo = startOfToday.minusDays(6); // inclusive: today + 6 past days = 7
        LocalDateTime thirtyDaysAgo = startOfToday.minusDays(29);

        return AdminDashboardResponse.builder()
                // KPI cards
                .totalAccounts(countTotalAccounts())
                .activeToday(countActiveToday(startOfToday, startOfTomorrow))
                .aiSessionsToday(countAiSessionsToday(startOfToday, startOfTomorrow))
                .submissionsToday(countSubmissionsToday(startOfToday, startOfTomorrow))
                // User growth charts
                .userGrowth7Days(buildUserGrowthTrend(sevenDaysAgo, today))
                .userGrowth30Days(buildUserGrowthTrend(thirtyDaysAgo, today))
                // Role distribution
                .roleDistributionPercent(buildRoleDistribution())
                // System health
                .systemHealth(buildSystemHealth())
                // AI session trend
                .aiSessionTrend7Days(buildAiSessionTrend(sevenDaysAgo))
                .build();
    }

    // ── KPI cards ────────────────────────────────────────────────────────────

    private long countTotalAccounts() {
        return Arrays.stream(UserRole.values())
                .mapToLong(userRepository::countByRole)
                .sum();
    }

    private long countActiveToday(LocalDateTime start, LocalDateTime end) {
        return userRepository.countActiveUsersByDateRange(start, end);
    }

    private long countAiSessionsToday(LocalDateTime start, LocalDateTime end) {
        long practice = practiceSessionRepository.countByCreatedAtBetween(start, end);
        long chat = chatSessionRepository.countCurriculumQABetween(start, end);
        long conversation = conversationSessionRepository.countConversationSessionsBetween(start, end);
        return practice + chat + conversation;
    }

    private long countSubmissionsToday(LocalDateTime start, LocalDateTime end) {
        return submissionRepository.countSubmittedBetween(start, end);
    }

    // ── User growth trend ────────────────────────────────────────────────────

    private List<AdminDashboardResponse.DailyCount> buildUserGrowthTrend(
            LocalDateTime startDateTime, LocalDate endDate) {

        LocalDate startDate = startDateTime.toLocalDate();
        List<Object[]> raw = userRepository.findNewUserTrendByDateRange(startDate, endDate);

        // Build a map from DB results
        Map<LocalDate, Long> dbMap = raw.stream().collect(Collectors.toMap(
                row -> toLocalDate(row[0]),
                row -> row[1] != null ? ((Number) row[1]).longValue() : 0L
        ));

        // Fill every day in range with 0 if no data
        List<AdminDashboardResponse.DailyCount> result = new ArrayList<>();
        LocalDate cursor = startDate;
        while (!cursor.isAfter(endDate)) {
            result.add(AdminDashboardResponse.DailyCount.builder()
                    .date(cursor)
                    .count(dbMap.getOrDefault(cursor, 0L))
                    .build());
            cursor = cursor.plusDays(1);
        }
        return result;
    }

    // ── Role distribution ────────────────────────────────────────────────────

    private Map<String, Double> buildRoleDistribution() {
        Map<String, Long> byRole = new LinkedHashMap<>();
        byRole.put("LEARNER", userRepository.countByRole(UserRole.LEARNER));
        byRole.put("TEACHER", userRepository.countByRole(UserRole.TEACHER));
        byRole.put("CONTENT_MANAGER", userRepository.countByRole(UserRole.CONTENT_MANAGER));
        byRole.put("OPERATION_MANAGER", userRepository.countByRole(UserRole.OPERATION_MANAGER));
        byRole.put("ADMIN", userRepository.countByRole(UserRole.ADMIN));

        long total = byRole.values().stream().mapToLong(Long::longValue).sum();
        if (total == 0) {
            return byRole.entrySet().stream()
                    .collect(Collectors.toMap(Map.Entry::getKey, e -> 0.0, (a, b) -> a, LinkedHashMap::new));
        }

        return byRole.entrySet().stream().collect(Collectors.toMap(
                Map.Entry::getKey,
                e -> Math.round((double) e.getValue() / total * 10000.0) / 100.0,
                (a, b) -> a,
                LinkedHashMap::new
        ));
    }

    // ── System health ────────────────────────────────────────────────────────

    private AdminDashboardResponse.SystemHealth buildSystemHealth() {
        // Overall status from Spring Boot Actuator
        HealthComponent health = healthEndpoint.health();
        String overallStatus = health.getStatus().getCode();

        // DB status — check the "db" health indicator
        String dbStatus = getComponentStatus(health, "db");

        // CPU & RAM from Micrometer
        double cpuUsage = Math.round(getGaugeValue("system.cpu.usage") * 10000.0) / 100.0;
        long totalMem = (long) getGaugeValue("jvm.memory.max");
        long usedMem = (long) getGaugeValue("jvm.memory.used");
        double ramUsage = totalMem > 0
                ? Math.round((double) usedMem / totalMem * 10000.0) / 100.0
                : 0.0;

        // p95 response time
        double p95 = getTimerMeanMs("http.server.requests");

        // AI service — attempt a lightweight ping to OpenAI
        String aiStatus = checkOpenAiReachability();

        return AdminDashboardResponse.SystemHealth.builder()
                .status(overallStatus)
                .cpuUsagePercent(cpuUsage)
                .ramUsagePercent(ramUsage)
                .dbStatus(dbStatus)
                .aiServiceStatus(aiStatus)
                .p95ResponseTimeMs(Math.round(p95 * 100.0) / 100.0)
                .build();
    }

    private String getComponentStatus(HealthComponent parent, String componentName) {
        try {
            if (parent instanceof org.springframework.boot.actuate.health.CompositeHealth composite) {
                HealthComponent component = composite.getComponents().get(componentName);
                if (component != null) {
                    return component.getStatus().getCode();
                }
            }
        } catch (Exception e) {
            log.warn("Could not read health component '{}': {}", componentName, e.getMessage());
        }
        return Status.UNKNOWN.getCode();
    }

    private String checkOpenAiReachability() {
        try {
            RestTemplate rt = new RestTemplate();
            rt.headForHeaders("https://api.openai.com");
            return Status.UP.getCode();
        } catch (org.springframework.web.client.HttpClientErrorException e) {
            // Any HTTP error response (4xx) means the server is reachable — just rejecting the request
            // 421 Misdirected Request is the typical response for a bare HEAD to api.openai.com
            log.debug("OpenAI reachability check returned HTTP {}: server is reachable", e.getStatusCode());
            return Status.UP.getCode();
        } catch (org.springframework.web.client.HttpServerErrorException e) {
            // 5xx from OpenAI — server is up but having issues
            log.warn("OpenAI reachability check returned HTTP {}: server-side error", e.getStatusCode());
            return Status.UP.getCode();
        } catch (Exception e) {
            // Network-level failure (DNS, TCP timeout, TLS error) — truly unreachable
            log.warn("OpenAI reachability check failed (network error): {}", e.getMessage());
            return Status.DOWN.getCode();
        }
    }

    // ── AI session trend ─────────────────────────────────────────────────────

    private List<AdminDashboardResponse.DailyCount> buildAiSessionTrend(LocalDateTime start) {
        LocalDate startDate = start.toLocalDate();
        LocalDate today = LocalDate.now();

        // Merge counts from all 3 session types per day
        Map<LocalDate, Long> merged = new LinkedHashMap<>();

        mergeTrend(merged, practiceSessionRepository.findDailySessionTrend(start));
        mergeTrend(merged, chatSessionRepository.findDailyCurriculumQATrend(start));
        mergeTrend(merged, conversationSessionRepository.findDailyConversationTrend(start));

        // Fill every day in range
        List<AdminDashboardResponse.DailyCount> result = new ArrayList<>();
        LocalDate cursor = startDate;
        while (!cursor.isAfter(today)) {
            result.add(AdminDashboardResponse.DailyCount.builder()
                    .date(cursor)
                    .count(merged.getOrDefault(cursor, 0L))
                    .build());
            cursor = cursor.plusDays(1);
        }
        return result;
    }

    private void mergeTrend(Map<LocalDate, Long> target, List<Object[]> rows) {
        for (Object[] row : rows) {
            LocalDate date = toLocalDate(row[0]);
            long count = row[1] != null ? ((Number) row[1]).longValue() : 0L;
            target.merge(date, count, Long::sum);
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private LocalDate toLocalDate(Object value) {
        if (value instanceof LocalDate d) return d;
        if (value instanceof java.sql.Date d) return d.toLocalDate();
        if (value instanceof LocalDateTime dt) return dt.toLocalDate();
        throw new IllegalArgumentException("Unsupported date type: " + (value == null ? "null" : value.getClass().getName()));
    }

    private double getGaugeValue(String name) {
        return Optional.ofNullable(meterRegistry.find(name).gauge())
                .map(g -> g.value())
                .orElse(0.0);
    }

    private double getTimerMeanMs(String name) {
        return Optional.ofNullable(meterRegistry.find(name).timer())
                .map(t -> t.mean(TimeUnit.MILLISECONDS))
                .orElse(0.0);
    }
}
