package com.fu.monitoring.service.impl;

import com.fu.assignment.repository.AssignmentRepository;
import com.fu.clazz.repository.ClassRepository;
import com.fu.course.repository.CourseRepository;
import com.fu.monitoring.dto.DashboardStatsResponse;
import com.fu.user.repository.UserRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthEndpoint;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SystemMonitoringServiceImplTest {

    @Test
    void getDashboardStats_shouldBuildSystemUsageAndPerformanceData() {
        HealthEndpoint healthEndpoint = mock(HealthEndpoint.class);
        UserRepository userRepository = mock(UserRepository.class);
        CourseRepository courseRepository = mock(CourseRepository.class);
        ClassRepository classRepository = mock(ClassRepository.class);
        AssignmentRepository assignmentRepository = mock(AssignmentRepository.class);

        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        Gauge.builder("system.cpu.usage", () -> 0.25).register(meterRegistry);
        Gauge.builder("jvm.memory.max", () -> 1000.0).register(meterRegistry);
        Gauge.builder("jvm.memory.used", () -> 250.0).register(meterRegistry);
        Gauge.builder("http.server.requests.active", () -> 6.0).register(meterRegistry);

        Timer timer = Timer.builder("http.server.requests")
                .tag("uri", "/**")
                .tag("phi", "0.95")
                .register(meterRegistry);
        timer.record(Duration.ofMillis(120));

        when(healthEndpoint.health()).thenReturn(Health.up().build());
        when(userRepository.count()).thenReturn(120L);
        when(courseRepository.count()).thenReturn(15L);
        when(classRepository.count()).thenReturn(20L);
        when(assignmentRepository.count()).thenReturn(75L);

        SystemMonitoringServiceImpl service = new SystemMonitoringServiceImpl(
                healthEndpoint,
                meterRegistry,
                userRepository,
                courseRepository,
                classRepository,
                assignmentRepository
        );

        DashboardStatsResponse result = service.getDashboardStats();

        assertThat(result.getSystem().getStatus()).isEqualTo("UP");
        assertThat(result.getSystem().getCpuUsage()).isEqualTo(25.0);
        assertThat(result.getSystem().getMemory().getUsed()).isEqualTo(250L);
        assertThat(result.getSystem().getMemory().getUsagePercentage()).isEqualTo(25.0);

        assertThat(result.getStatistics().getTotalUsers()).isEqualTo(120L);
        assertThat(result.getStatistics().getTotalCourses()).isEqualTo(15L);
        assertThat(result.getStatistics().getTotalClasses()).isEqualTo(20L);
        assertThat(result.getStatistics().getTotalAssignments()).isEqualTo(75L);
        assertThat(result.getStatistics().getActiveSessions()).isEqualTo(6L);

        assertThat(result.getPerformance().getP95ResponseTimeMs()).isGreaterThan(0.0);
        assertThat(result.getPerformance().getRequestCountPerStatus()).isEmpty();
    }

    @Test
    void getDashboardStats_shouldFallbackToZeroWhenMetricsAreMissing() {
        HealthEndpoint healthEndpoint = mock(HealthEndpoint.class);
        UserRepository userRepository = mock(UserRepository.class);
        CourseRepository courseRepository = mock(CourseRepository.class);
        ClassRepository classRepository = mock(ClassRepository.class);
        AssignmentRepository assignmentRepository = mock(AssignmentRepository.class);

        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

        when(healthEndpoint.health()).thenReturn(Health.down().build());
        when(userRepository.count()).thenReturn(0L);
        when(courseRepository.count()).thenReturn(0L);
        when(classRepository.count()).thenReturn(0L);
        when(assignmentRepository.count()).thenReturn(0L);

        SystemMonitoringServiceImpl service = new SystemMonitoringServiceImpl(
                healthEndpoint,
                meterRegistry,
                userRepository,
                courseRepository,
                classRepository,
                assignmentRepository
        );

        DashboardStatsResponse result = service.getDashboardStats();

        assertThat(result.getSystem().getStatus()).isEqualTo("DOWN");
        assertThat(result.getSystem().getCpuUsage()).isZero();
        assertThat(result.getSystem().getMemory().getTotal()).isZero();
        assertThat(result.getSystem().getMemory().getUsed()).isZero();
        assertThat(result.getSystem().getMemory().getUsagePercentage()).isZero();
        assertThat(result.getPerformance().getP95ResponseTimeMs()).isZero();
        assertThat(result.getStatistics().getActiveSessions()).isZero();
    }

    @Test
    void getDashboardStats_shouldRoundCpuAndMemoryUsageToTwoDecimals() {
        HealthEndpoint healthEndpoint = mock(HealthEndpoint.class);
        UserRepository userRepository = mock(UserRepository.class);
        CourseRepository courseRepository = mock(CourseRepository.class);
        ClassRepository classRepository = mock(ClassRepository.class);
        AssignmentRepository assignmentRepository = mock(AssignmentRepository.class);

        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        Gauge.builder("system.cpu.usage", () -> 0.12349).register(meterRegistry);
        Gauge.builder("jvm.memory.max", () -> 300.0).register(meterRegistry);
        Gauge.builder("jvm.memory.used", () -> 100.0).register(meterRegistry);
        Gauge.builder("http.server.requests.active", () -> 2.0).register(meterRegistry);

        when(healthEndpoint.health()).thenReturn(Health.up().build());
        when(userRepository.count()).thenReturn(1L);
        when(courseRepository.count()).thenReturn(1L);
        when(classRepository.count()).thenReturn(1L);
        when(assignmentRepository.count()).thenReturn(1L);

        SystemMonitoringServiceImpl service = new SystemMonitoringServiceImpl(
                healthEndpoint,
                meterRegistry,
                userRepository,
                courseRepository,
                classRepository,
                assignmentRepository
        );

        DashboardStatsResponse result = service.getDashboardStats();

        assertThat(result.getSystem().getCpuUsage()).isEqualTo(12.35);
        assertThat(result.getSystem().getMemory().getUsagePercentage()).isEqualTo(33.33);
    }
}
