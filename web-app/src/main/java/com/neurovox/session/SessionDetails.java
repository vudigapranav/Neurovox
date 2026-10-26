package com.neurovox.session;

import com.neurovox.model.RecordingSession;
import com.neurovox.model.TaskType;
import com.neurovox.model.VoiceSample;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/** A session together with its recordings, in guided-task order. */
public record SessionDetails(RecordingSession session, List<VoiceSample> samples) {

    public SessionDetails {
        samples = List.copyOf(samples);
    }

    /** Tasks that have not been recorded yet, in guided order. */
    public List<TaskType> missingTasks() {
        return Arrays.stream(TaskType.values())
                .filter(task -> samples.stream().noneMatch(s -> s.taskType() == task))
                .toList();
    }

    public Optional<VoiceSample> sample(TaskType task) {
        return samples.stream().filter(s -> s.taskType() == task).findFirst();
    }
}
