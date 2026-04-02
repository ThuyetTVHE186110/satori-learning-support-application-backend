package com.fu.clazz.scheduler;

import com.fu.clazz.entity.Clazz;
import com.fu.clazz.repository.ClassRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

/**
 * Scheduler for automated class status updates.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ClassSchedulerService {

    private final ClassRepository classRepository;

    /**
     * Automatically activate classes that have reached their startDate.
     * Scheduled to run every day at 00:00.
     */
    @Scheduled(cron = "0 0 0 * * *")
    @Transactional
    public void activateReachedClasses() {
        log.info("Bắt đầu tiến trình tự động kích hoạt lớp học...");
        
        LocalDate today = LocalDate.now();
        List<Clazz> classesToActivate = classRepository.findInactiveClassesToActivate(today);

        if (classesToActivate.isEmpty()) {
            log.info("Không có lớp học nào cần kích hoạt trong hôm nay ({}).", today);
            return;
        }

        log.info("Tìm thấy {} lớp học cần kích hoạt.", classesToActivate.size());

        for (Clazz clazz : classesToActivate) {
            clazz.setIsActive(true);
            log.info("Kích hoạt lớp học: {} (Ngày bắt đầu: {})", clazz.getName(), clazz.getStartDate());
        }

        classRepository.saveAll(classesToActivate);
        log.info("Đã kích hoạt thành công {} lớp học.", classesToActivate.size());
    }
}
