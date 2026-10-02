package com.neurovox.server;

import com.neurovox.analysis.AnalysisService;
import com.neurovox.analysis.QualityReport;
import com.neurovox.analysis.SessionReport;
import com.neurovox.audio.PcmSummary;
import com.neurovox.features.FeatureCatalog;
import com.neurovox.features.UciFeature;
import com.neurovox.ml.CompatibilityGate;
import com.neurovox.ml.ResearchIndicator;
import com.neurovox.model.FeatureValue;
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
        m.put("minSeconds", task.minSeconds());
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

    static Map<String, Object> quality(QualityReport q) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", q.status().code());
        m.put("checks", q.checks().stream().map(c -> {
            Map<String, Object> check = new LinkedHashMap<>();
            check.put("code", c.code());
            check.put("status", c.status().code());
            check.put("value", Double.isFinite(c.value()) ? round(c.value(), 4) : null);
            check.put("message", c.message());
            check.put("recovery", c.recovery());
            return check;
        }).toList());
        return m;
    }

    static Map<String, Object> feature(FeatureValue v) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", v.name());
        m.put("value", v.value());
        m.put("unit", v.unit());
        m.put("available", v.isAvailable());
        m.put("unavailableReason", v.unavailableReason());
        FeatureCatalog.describe(v.name()).ifPresent(d -> {
            m.put("group", d.group());
            m.put("plainName", d.plainName());
            m.put("explanation", d.explanation());
        });
        return m;
    }

    /** The 16 UCI columns in fixed order, with units and plain names (for the history chart). */
    static List<Map<String, Object>> uciCatalog() {
        return UciFeature.ORDER.stream().map(f -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("key", f.key());
            m.put("unit", f.unit());
            m.put("plainName", f.plainName());
            m.put("explanation", f.explanation());
            return m;
        }).toList();
    }

    static Map<String, Object> indicator(ResearchIndicator.Outcome o) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("shown", o.shown());
        m.put("reason", o.reason());
        m.put("modelOutput", o.score());
        m.put("aboveThreshold", o.aboveThreshold());
        m.put("modelVersion", o.modelVersion());
        m.put("input", "sustained_vowel");
        m.put("label", "Experimental research indicator — not a diagnosis, not a probability of disease, not clinical confidence.");
        return m;
    }

    static Map<String, Object> gate(CompatibilityGate g) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("decision", g.decision().code());
        m.put("decidedOn", g.decidedOn());
        m.put("summary", g.summary());
        m.put("reasons", g.reasons());
        m.put("evidence", g.evidence());
        return m;
    }

    static Map<String, Object> report(SessionReport r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("session", session(r.session()));
        m.put("extractorVersion", r.extractorVersion());
        m.put("analysedAt", Timestamps.format(r.analysedAt()));
        m.put("tasks", r.tasks().stream().map(t -> {
            Map<String, Object> task = new LinkedHashMap<>();
            task.put("task", t.sample().taskType().code());
            task.put("feedsResearchClassifier", t.sample().taskType().feedsResearchClassifier());
            task.put("recording", recording(t.sample()));
            task.put("quality", quality(t.quality()));
            task.put("details", t.details());
            task.put("features", t.features().stream().map(JsonViews::feature).toList());
            return task;
        }).toList());
        m.put("researchIndicator", indicator(r.indicator()));
        m.put("gate", gate(r.gate()));
        m.put("notComputed", UciFeature.NOT_COMPUTED);
        return m;
    }

    static Map<String, Object> historyEntry(AnalysisService.HistoryEntry e) {
        RecordingSession s = e.session();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", s.id());
        m.put("status", s.status().code());
        m.put("startedAt", Timestamps.format(s.startedAt()));
        m.put("completedAt", s.completedAt() == null ? null : Timestamps.format(s.completedAt()));
        m.put("recordings", e.recordings());
        m.put("analysed", e.analysed());
        m.put("vowelQuality", e.vowelQuality());
        m.put("vowel", e.vowelFeatures());
        return m;
    }

    private static double round(double value, int places) {
        double scale = Math.pow(10, places);
        return Math.round(value * scale) / scale;
    }
}
