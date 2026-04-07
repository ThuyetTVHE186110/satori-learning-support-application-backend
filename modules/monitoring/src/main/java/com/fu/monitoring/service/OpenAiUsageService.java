package com.fu.monitoring.service;

import com.fu.monitoring.dto.OpenAiUsageResponse;

public interface OpenAiUsageService {
    OpenAiUsageResponse getUsage();
}

