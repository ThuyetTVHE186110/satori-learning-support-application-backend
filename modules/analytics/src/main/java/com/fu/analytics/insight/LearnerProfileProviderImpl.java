package com.fu.analytics.insight;

import com.fu.infrastructure.learner.LearnerProfile;
import com.fu.infrastructure.learner.LearnerProfileProvider;
import com.fu.user.entity.UserLearningPreferences;
import com.fu.user.repository.UserLearningPreferencesRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class LearnerProfileProviderImpl implements LearnerProfileProvider {

    private final UserLearningPreferencesRepository prefsRepository;

    @Override
    public Optional<LearnerProfile> getLearnerProfile(UUID userId) {
        return prefsRepository.findByUserId(userId)
                .map(prefs -> new LearnerProfile(
                        prefs.getSkillScores() != null ? prefs.getSkillScores() : List.of(),
                        prefs.getWeakPoints() != null ? prefs.getWeakPoints() : List.of(),
                        prefs.getStrongPoints() != null ? prefs.getStrongPoints() : List.of()));
    }
}
