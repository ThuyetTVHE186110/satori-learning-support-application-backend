package com.fu.monitoring.service.impl;

import com.fu.assignment.repository.AssignmentRepository;
import com.fu.clazz.repository.ClassRepository;
import com.fu.course.repository.CourseRepository;
import com.fu.monitoring.dto.DashboardStatsResponse;
import com.fu.monitoring.service.SystemMonitoringService;
import com.fu.user.repository.UserRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.search.Search;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.actuate.health.HealthComponent;
import org.springframework.boot.actuate.health.HealthEndpoint;
import org.springframework.boot.actuate.health.Status;
import org.springframework.stereotype.Service;

import java.lang.management.ManagementFactory;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
public class SystemMonitoringServiceImpl implements SystemMonitoringService {

    private final HealthEndpoint healthEndpoint;
    private final MeterRegistry meterRegistry;
    private final UserRepository userRepository;
    private final CourseRepository courseRepository;
    private final ClassRepository classRepository;
    private final AssignmentRepository assignmentRepository;

    @Override
    public DashboardStatsResponse getDashboardStats() {
        return DashboardStatsResponse.builder()
                .system(getSystemInfo())
                .statistics(getUsageStatistics())
                .performance(getPerformanceMetrics())
                .build();
    }

    private DashboardStatsResponse.SystemInfo getSystemInfo() {
        HealthComponent health = healthEndpoint.health();
        Status status = health.getStatus();
        
        long uptimeMs = ManagementFactory.getRuntimeMXBean().getUptime();
        String uptime = formatUptime(uptimeMs);

        double cpuUsage = getMetricValue("system.cpu.usage") * 100;
        
        long totalMemory = (long) getMetricValue("jvm.memory.max");
        long usedMemory = (long) getMetricValue("jvm.memory.used");
        
        return DashboardStatsResponse.SystemInfo.builder()
                .status(status.getCode())
                .uptime(uptime)
                .cpuUsage(Math.round(cpuUsage * 100.0) / 100.0)
                .memory(DashboardStatsResponse.MemoryInfo.builder()
                        .total(totalMemory)
                        .used(usedMemory)
                        .free(totalMemory - usedMemory)
                        .usagePercentage(Math.round((double) usedMemory / totalMemory * 10000.0) / 100.0)
                        .build())
                .build();
    }

    private DashboardStatsResponse.UsageStatistics getUsageStatistics() {
        return DashboardStatsResponse.UsageStatistics.builder()
                .totalUsers(userRepository.count())
                .totalCourses(courseRepository.count())
                .totalClasses(classRepository.count())
                .totalAssignments(assignmentRepository.count())
                .activeSessions(getMetricValue("http.server.requests.active"))
                .build();
    }

    private DashboardStatsResponse.PerformanceMetrics getPerformanceMetrics() {
        double p95 = getMetricValue("http.server.requests", "uri", "/**", "phi", "0.95");
        
        Map<String, Long> statusMap = new HashMap<>();
        
        return DashboardStatsResponse.PerformanceMetrics.builder()
                .p95ResponseTimeMs(Math.round(p95 * 100.0) / 100.0)
                .errorCount24h(0)
                .requestCountPerStatus(statusMap)
                .build();
    }

    private double getMetricValue(String name) {
        return Optional.ofNullable(meterRegistry.find(name).gauge())
                .map(gauge -> gauge.value())
                .orElse(0.0);
    }

    private double getMetricValue(String name, String... tags) {
        Search search = meterRegistry.find(name);
        if (tags.length >= 2) {
            for (int i = 0; i < tags.length; i += 2) {
                search = search.tag(tags[i], tags[i+1]);
            }
        }
        return Optional.ofNullable(search.timer())
                .map(timer -> timer.mean(TimeUnit.MILLISECONDS))
                .orElse(0.0);
    }

    private String formatUptime(long ms) {
        long days = TimeUnit.MILLISECONDS.toDays(ms);
        long hours = TimeUnit.MILLISECONDS.toHours(ms) % 24;
        long minutes = TimeUnit.MILLISECONDS.toMinutes(ms) % 60;
        long seconds = TimeUnit.MILLISECONDS.toSeconds(ms) % 60;
        return String.format("%dd %dh %dm %ds", days, hours, minutes, seconds);
    }
}
