package com.neurovox.analysis;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.neurovox.audio.AudioClip;
import com.neurovox.audio.PcmDecoder;
import com.neurovox.db.Database;
import com.neurovox.db.SampleAnalysisRepository;
import com.neurovox.db.ScreeningResultRepository;
import com.neurovox.db.SpeechFeatureRepository;
import com.neurovox.db.Timestamps;
import com.neurovox.ml.ResearchIndicator;
import com.neurovox.model.FeatureValue;
import com.neurovox.model.RecordingSession;
import com.neurovox.model.SpeechFeatures;
import com.neurovox.model.TaskType;
import com.neurovox.model.VoiceSample;
import com.neurovox.session.NotFoundException;
import com.neurovox.session.SessionDetails;
import com.neurovox.session.SessionService;

import java.lang.reflect.Type;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Logger;

/**
 * Runs the analysis of a completed session and reads results back.
 *
 * <p>{@link #analyze} reads each stored recording (checking its SHA-256), runs {@link RecordingAnalyzer}, and saves
 * every sample's features and quality facts in one transaction, so a session is either fully analysed or not at all.
 * The research indicator is then evaluated from the sustained vowel only; it is stored only if the gate allows it.
 */
public final class AnalysisService {

    private static final Logger LOG = Logger.getLogger(AnalysisService.class.getName());
    private static final Gson GSON = new Gson();
    private static final Type MAP_TYPE = new TypeToken<Map<String, Object>>() { }.getType();

    private final Database database;
    private final SessionService sessions;
    private final RecordingAnalyzer analyzer;
    private final ResearchIndicator indicator;
    private final Clock clock;
    private final SpeechFeatureRepository features = new SpeechFeatureRepository();
    private final SampleAnalysisRepository analyses = new SampleAnalysisRepository();
    private final ScreeningResultRepository screening = new ScreeningResultRepository();

    public AnalysisService(Database database, SessionService sessions, RecordingAnalyzer analyzer,
                           ResearchIndicator indicator, Clock clock) {
        this.database = database;
        this.sessions = sessions;
        this.analyzer = analyzer;
        this.indicator = indicator;
        this.clock = clock;
    }

    public ResearchIndicator indicator() {
        return indicator;
    }

    /** The quality gate for an upload, before anything is stored. */
    public QualityReport checkUpload(TaskType task, AudioClip clip) {
        return analyzer.check(task, clip);
    }

    /** Analyses every recording of a complete session (again, if it was analysed before) and returns the report. */
    public SessionReport analyze(long sessionId) {
        SessionDetails details = sessions.details(sessionId)
                .orElseThrow(() -> new NotFoundException("Session " + sessionId + " does not exist."));
        if (!details.session().isComplete()) {
            throw new IllegalStateException("Session " + sessionId + " is not complete yet: record "
                    + details.missingTasks().stream().map(TaskType::label).toList() + " first.");
        }
        Instant now = Timestamps.now(clock);
        List<RecordingAnalysis> results = new ArrayList<>();
        long start = System.nanoTime();
        for (VoiceSample sample : details.samples()) {
            float[] pcm = PcmDecoder.decodeF32le(sessions.readRecording(sample));
            results.add(analyzer.analyze(sample.taskType(), AudioClip.ofFloats(pcm, sample.sampleRateHz())));
        }
        LOG.info(() -> String.format("Analysed session %d in %.0f ms", sessionId, (System.nanoTime() - start) / 1e6));

        VoiceSample vowelSample = details.sample(TaskType.SUSTAINED_VOWEL).orElseThrow();
        database.transaction(c -> {
            for (int i = 0; i < results.size(); i++) {
                RecordingAnalysis r = results.get(i);
                VoiceSample sample = details.samples().get(i);
                features.save(c, new SpeechFeatures(sample.id(), r.extractorVersion(), now, r.features()));
                Map<String, Object> stored = new LinkedHashMap<>(r.details());
                stored.put("quality", r.quality());
                analyses.save(c, new SampleAnalysisRepository.Row(sample.id(), r.extractorVersion(),
                        r.quality().status().code(), GSON.toJson(stored), now));
            }
            screening.deleteBySession(c, sessionId);
            SpeechFeatures vowel = new SpeechFeatures(vowelSample.id(), RecordingAnalyzer.EXTRACTOR_VERSION, now,
                    results.get(details.samples().indexOf(vowelSample)).features());
            ResearchIndicator.Outcome outcome = indicator.evaluate(vowel);
            if (outcome.shown()) {
                screening.insert(c, sessionId, vowelSample.id(), outcome.modelVersion(), outcome.score(), now);
            }
            return null;
        });
        return report(sessionId).orElseThrow();
    }

    /** The stored report for the current extractor version, or empty if the session has not been analysed. */
    public Optional<SessionReport> report(long sessionId) {
        Optional<SessionDetails> found = sessions.details(sessionId);
        if (found.isEmpty()) {
            throw new NotFoundException("Session " + sessionId + " does not exist.");
        }
        SessionDetails details = found.get();
        if (details.samples().isEmpty()) {
            return Optional.empty();
        }
        String version = RecordingAnalyzer.EXTRACTOR_VERSION;
        return database.read(c -> {
            List<SessionReport.TaskResult> tasks = new ArrayList<>();
            Instant analysedAt = null;
            SpeechFeatures vowel = null;
            for (VoiceSample sample : details.samples()) {
                Optional<SampleAnalysisRepository.Row> row = analyses.find(c, sample.id(), version);
                Optional<SpeechFeatures> f = features.find(c, sample.id(), version);
                if (row.isEmpty() || f.isEmpty()) {
                    return Optional.empty();
                }
                Map<String, Object> facts = GSON.fromJson(row.get().detailsJson(), MAP_TYPE);
                QualityReport quality = GSON.fromJson(GSON.toJson(facts.remove("quality")), QualityReport.class);
                tasks.add(new SessionReport.TaskResult(sample, quality, facts, f.get().values()));
                analysedAt = row.get().computedAt();
                if (sample.taskType() == TaskType.SUSTAINED_VOWEL) {
                    vowel = f.get();
                }
            }
            if (vowel == null || tasks.size() < TaskType.values().length) {
                return Optional.empty();
            }
            return Optional.of(new SessionReport(details, version, analysedAt, tasks, indicator.evaluate(vowel), indicator.gate()));
        });
    }

    /** One row of the history list. {@code vowelFeatures} holds the available UCI values by key (empty if not analysed). */
    public record HistoryEntry(RecordingSession session, int recordings, boolean analysed, String vowelQuality,
                               Map<String, Double> vowelFeatures) {
    }

    /** A profile's sessions, newest first, with the sustained-vowel measurements for trend charts. */
    public List<HistoryEntry> history(long profileId) {
        List<HistoryEntry> out = new ArrayList<>();
        for (RecordingSession session : sessions.history(profileId)) {
            SessionDetails details = sessions.details(session.id()).orElseThrow();
            Optional<VoiceSample> vowel = details.sample(TaskType.SUSTAINED_VOWEL);
            Map<String, Double> values = new LinkedHashMap<>();
            String quality = null;
            boolean analysed = false;
            if (vowel.isPresent()) {
                Optional<SampleAnalysisRepository.Row> row = database.read(c -> analyses.find(c, vowel.get().id(), RecordingAnalyzer.EXTRACTOR_VERSION));
                Optional<SpeechFeatures> f = database.read(c -> features.find(c, vowel.get().id(), RecordingAnalyzer.EXTRACTOR_VERSION));
                if (row.isPresent() && f.isPresent()) {
                    analysed = details.session().isComplete();
                    quality = row.get().qualityStatus();
                    for (FeatureValue v : f.get().values()) {
                        if (v.isAvailable()) {
                            values.put(v.name(), v.value());
                        }
                    }
                }
            }
            out.add(new HistoryEntry(session, details.samples().size(), analysed, quality, values));
        }
        return out;
    }
}
