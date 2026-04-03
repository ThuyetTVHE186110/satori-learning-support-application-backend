package com.fu.clazz.scheduler;

import com.fu.clazz.entity.ClassStatus;
import com.fu.clazz.entity.Clazz;
import com.fu.clazz.repository.ClassRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ScheduledFuture;
import java.util.function.Consumer;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ClassScheduleManagerTest {

    @Mock
    private TaskScheduler taskScheduler;

    @Mock
    private ClassRepository classRepository;

    @Mock
    private TransactionTemplate transactionTemplate;

    @InjectMocks
    private ClassScheduleManager classScheduleManager;

    private Clazz testClazz;
    private UUID classId;

    @BeforeEach
    void setUp() {
        classId = UUID.randomUUID();
        testClazz = Clazz.builder()
                .name("Test Class")
                .status(ClassStatus.NOT_STARTED)
                .startDate(LocalDate.now().plusDays(5))
                .build();
        ReflectionTestUtils.setField(testClazz, "id", classId);
    }

    @Test
    @DisplayName("Should reschedule all NOT_STARTED classes on startup")
    void shouldRescheduleAllOnStartup() {
        // Given
        given(classRepository.findByStatusAndDeletedAtIsNull(ClassStatus.NOT_STARTED))
                .willReturn(List.of(testClazz));
        given(taskScheduler.schedule(any(Runnable.class), any(Instant.class)))
                .willReturn(mock(ScheduledFuture.class));

        // When
        classScheduleManager.rescheduleAllOnStartup();

        // Then
        verify(classRepository).findByStatusAndDeletedAtIsNull(ClassStatus.NOT_STARTED);
        verify(taskScheduler).schedule(any(Runnable.class), any(Instant.class));
    }

    @Test
    @DisplayName("Should activate immediately if startDate has reached/passed")
    void shouldActivateImmediatelyIfDatePassed() {
        // Given
        testClazz.setStartDate(LocalDate.now());
        
        // Mock transaction template to execute the callback immediately
        doAnswer(invocation -> {
            Consumer<TransactionStatus> callback = invocation.getArgument(0);
            callback.accept(null);
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());

        given(classRepository.findByIdAndNotDeleted(any())).willReturn(Optional.of(testClazz));

        // When
        classScheduleManager.scheduleActivation(testClazz);

        // Then
        verify(classRepository).save(argThat(c -> c.getStatus() == ClassStatus.ACTIVE));
        verify(taskScheduler, never()).schedule(any(Runnable.class), any(Instant.class));
    }

    @Test
    @DisplayName("Should schedule activation for future startDate")
    void shouldScheduleForFutureDate() {
        // Given
        given(taskScheduler.schedule(any(Runnable.class), any(Instant.class)))
                .willReturn(mock(ScheduledFuture.class));

        // When
        classScheduleManager.scheduleActivation(testClazz);

        // Then
        verify(taskScheduler).schedule(any(Runnable.class), any(Instant.class));
        verify(classRepository, never()).save(any());
    }

    @Test
    @DisplayName("Should cancel scheduled task")
    void shouldCancelScheduledTask() {
        // Given
        ScheduledFuture<?> mockFuture = mock(ScheduledFuture.class);
        given(taskScheduler.schedule(any(Runnable.class), any(Instant.class))).willReturn((ScheduledFuture) mockFuture);
        
        classScheduleManager.scheduleActivation(testClazz); // Put into map

        // When
        classScheduleManager.cancelScheduledActivation(testClazz.getId());

        // Then
        verify(mockFuture).cancel(false);
    }

    @Test
    @DisplayName("Should not schedule if status is not NOT_STARTED")
    void shouldNotScheduleIfAlreadyActive() {
        // Given
        testClazz.setStatus(ClassStatus.ACTIVE);

        // When
        classScheduleManager.scheduleActivation(testClazz);

        // Then
        verify(taskScheduler, never()).schedule(any(Runnable.class), any(Instant.class));
    }
}
