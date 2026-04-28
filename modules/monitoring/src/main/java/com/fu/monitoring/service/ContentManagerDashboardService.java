package com.fu.monitoring.service;

import com.fu.monitoring.dto.response.*;

public interface ContentManagerDashboardService {
    ContentManagerDashboardResponse getDashboard();
    
    MostPracticedContentSection getMostPracticedContent(int topN);
    
    PracticeTrendSection getPracticeTrend();
    
    ContentImprovementSection getContentNeedingImprovement(double threshold, int limit);
}
