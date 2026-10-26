package com.neurovox.analysis;

import com.neurovox.model.FeatureValue;
import com.neurovox.model.TaskType;

import java.util.List;
import java.util.Map;

/**
 * Everything Neurovox measured from one take.
 *
 * @param features ordered measurements; for the sustained vowel the first 16 are the UCI columns in
 *                 {@link com.neurovox.features.UciFeature#ORDER}, then descriptive extras and MFCC summaries
 * @param details  small facts about how the analysis ran (region, counts), stored as JSON for the report
 */
public record RecordingAnalysis(TaskType task, String extractorVersion, QualityReport quality,
                                List<FeatureValue> features, Map<String, Object> details) {

    public RecordingAnalysis {
        features = List.copyOf(features);
        details = Map.copyOf(details);
    }
}
