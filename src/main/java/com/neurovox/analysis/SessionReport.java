package com.neurovox.analysis;

import com.neurovox.ml.CompatibilityGate;
import com.neurovox.ml.ResearchIndicator;
import com.neurovox.model.FeatureValue;
import com.neurovox.model.VoiceSample;
import com.neurovox.session.SessionDetails;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Everything the results page shows for one analysed session.
 *
 * @param indicator the research-indicator decision (usually "not shown", with the reason)
 */
public record SessionReport(SessionDetails session, String extractorVersion, Instant analysedAt, List<TaskResult> tasks,
                            ResearchIndicator.Outcome indicator, CompatibilityGate gate) {

    /** One task's stored analysis. */
    public record TaskResult(VoiceSample sample, QualityReport quality, Map<String, Object> details, List<FeatureValue> features) {
    }

    public SessionReport {
        tasks = List.copyOf(tasks);
    }
}
