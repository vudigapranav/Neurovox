package com.neurovox.server;

import com.neurovox.audio.PcmSummary;
import com.neurovox.db.Timestamps;
import com.neurovox.model.RecordingSession;
import com.neurovox.model.TaskType;
import com.neurovox.model.VoiceSample;
import com.neurovox.session.SessionDetails;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Converts model objects to the JSON shapes the frontend reads. Built by hand so the API field names
 * are explicit and do not change if a Java field is renamed. Internal file paths are never exposed.
 */
final class JsonViews {

    private JsonViews() {
    }

    static List<Map<String, Object>> tasks() {
        return Arrays.stream(TaskType.values()).map(JsonViews::task).toList();
    }

    static Map<String, Object> task(TaskType task) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("code", task.code());
        m.put("order", task.order());
        m.put("label", task.label());
        m.put("targetSeconds", task.targetSeconds());
        m.put("maxSeconds", task.maxUploadSeconds());
        m.put("feedsResearchClassifier", task.feedsResearchClassifier());
        return m;
    }

    static Map<String, Object> session(SessionDetails details) {
        RecordingSession s = details.session();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", s.id());
        m.put("profileId", s.profileId());
        m.put("status", s.status().code());
        m.put("startedAt", Timestamps.format(s.startedAt()));
        m.put("completedAt", s.completedAt() == null ? null : Timestamps.format(s.completedAt()));
        m.put("recordings", details.samples().stream().map(JsonViews::recording).toList());
        m.put("missingTasks", details.missingTasks().stream().map(TaskType::code).toList());
        return m;
    }

    static Map<String, Object> recording(VoiceSample v) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("task", v.taskType().code());
        m.put("sampleId", v.id());
        m.put("format", v.format().code());
        m.put("sampleRateHz", v.sampleRateHz());
        m.put("sampleCount", v.sampleCount());
        m.put("durationSeconds", round(v.durationSeconds(), 3));
        m.put("recordedAt", Timestamps.format(v.recordedAt()));
        return m;
    }

    static Map<String, Object> audio(PcmSummary a) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("durationSeconds", round(a.durationSeconds(), 3));
        m.put("peak", round(a.peak(), 5));
        m.put("rms", round(a.rms(), 6));
        m.put("rmsDbfs", a.rmsDbfs() == null ? null : round(a.rmsDbfs(), 2));
        return m;
    }

    private static double round(double value, int places) {
        double scale = Math.pow(10, places);
        return Math.round(value * scale) / scale;
    }
}
