package com.fu.clazz.scheduler;

import com.fu.clazz.entity.Clazz;
import com.fu.clazz.repository.ClassRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ClassSchedulerServiceTest {

    @Mock
    private ClassRepository classRepository;

    @InjectMocks
    private ClassSchedulerService classSchedulerService;

    private Clazz testClazz;

    @BeforeEach
    void setUp() {
        testClazz = Clazz.builder()
                .name("Test Class")
                .isActive(false)
                .startDate(LocalDate.now().minusDays(1))
                .build();
    }

    @Test
    @DisplayName("Should activate classes when their startDate is reached")
    void shouldActivateClassesWhenStartDateReached() {
        // Given
        given(classRepository.findInactiveClassesToActivate(any(LocalDate.class)))
                .willReturn(List.of(testClazz));

        // When
        classSchedulerService.activateReachedClasses();

        // Then
        verify(classRepository).saveAll(argThat(list -> {
            List<Clazz> classes = (List<Clazz>) list;
            return classes.size() == 1 && classes.get(0).getIsActive();
        }));
    }

    @Test
    @DisplayName("Should do nothing when no classes to activate")
    void shouldDoNothingWhenNoClassesToActivate() {
        // Given
        given(classRepository.findInactiveClassesToActivate(any(LocalDate.class)))
                .willReturn(List.of());

        // When
        classSchedulerService.activateReachedClasses();

        // Then
        verify(classRepository, never()).saveAll(any());
    }
}
