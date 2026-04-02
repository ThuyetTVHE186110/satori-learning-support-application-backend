package com.fu.achievement.repository;

import com.fu.achievement.dto.response.BadgeSummaryResponse;
import com.fu.achievement.entity.Badge;
import com.fu.achievement.enums.BadgeStatus;
import com.fu.achievement.enums.BadgeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface BadgeRepository extends JpaRepository<Badge, UUID> {

    @Query("SELECT new com.fu.achievement.dto.response.BadgeSummaryResponse(" +
           "b.id, b.name, b.description, b.badgeType, " +
           "b.expReward, b.iconUrl, b.status, b.createdAt, COUNT(ub.id)) " +
           "FROM Badge b LEFT JOIN UserBadges ub ON b.id = ub.badgeId " +
           "WHERE (cast(:keyword as string) IS NULL OR LOWER(b.name) LIKE LOWER(CONCAT('%', cast(:keyword as string), '%'))) " +
           "AND (cast(:status as string) IS NULL OR b.status = :status) " +
           "AND (cast(:badgeType as string) IS NULL OR b.badgeType = :badgeType) " +
           "GROUP BY b.id")
    Page<BadgeSummaryResponse> searchBadges(
            @Param("keyword") String keyword,
            @Param("status") BadgeStatus status,
            @Param("badgeType") BadgeType badgeType,
            Pageable pageable
    );
}
