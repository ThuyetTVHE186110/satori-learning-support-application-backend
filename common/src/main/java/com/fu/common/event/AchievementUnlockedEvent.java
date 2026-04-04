package com.fu.common.event;

import lombok.Getter;
import org.springframework.context.ApplicationEvent;

import java.util.UUID;

/**
 * Event fired when a user unlocks an achievement.
 * Triggers notification to the user.
 */
@Getter
public class AchievementUnlockedEvent extends ApplicationEvent {

    private final UUID userId;
    private final UUID achievementId;
    private final String achievementTitle;
    private final String achievementDescription;
    private final String badgeImageUrl;

    public AchievementUnlockedEvent(Object source, UUID userId, UUID achievementId,
                                     String achievementTitle, String achievementDescription,
                                     String badgeImageUrl) {
        super(source);
        this.userId = userId;
        this.achievementId = achievementId;
        this.achievementTitle = achievementTitle;
        this.achievementDescription = achievementDescription;
        this.badgeImageUrl = badgeImageUrl;
    }
}
