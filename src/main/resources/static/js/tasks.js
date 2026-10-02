// What the person sees for each task. Codes and durations come from the server (/api/tasks);
// this file holds only the wording.

export const TASK_COPY = {
  sustained_vowel: {
    title: 'Sustained vowel',
    instruction: 'Take a comfortable breath, then say “aah” at a steady, comfortable pitch and loudness. Keep going until the timer ends.',
    tip: 'Stay the same distance from the microphone the whole time.',
    cue: 'aaah',
    use: 'Used for: the 16 voice measurements (pitch, cycle timing, cycle loudness, noise). It is the only task the offline research model was trained on.',
  },
  pa_ta_ka: {
    title: 'Syllable repetition',
    instruction: 'Repeat “pa-ta-ka” as quickly and clearly as you can, without stopping, until the timer ends.',
    tip: 'Keep an even rhythm. It is fine to take a quick breath.',
    cue: 'pa-ta-ka · pa-ta-ka · pa-ta-ka',
    use: 'Used for: descriptive measures such as an estimated syllable rate. Never used by any classifier.',
  },
  reading_passage: {
    title: 'Reading passage',
    instruction: 'Read the passage aloud at your usual pace. If you reach the end before the timer, start again from the beginning.',
    tip: 'Read naturally. There is no need to rush.',
    use: 'Used for: descriptive measures of connected speech (pitch variation, pauses). Never used by any classifier.',
    // An original passage written for Neurovox (not a standardised clinical text). Everyone reads the
    // same words, so recordings are comparable across sessions.
    passage: 'Every morning the baker on our street opens the shutters just before seven. She sets out warm bread, '
      + 'a jar of honey and a small bowl of apples. Children walking to school often stop to watch the steam rise '
      + 'from the loaves. On rainy days she lights a lamp in the window, and the whole shop glows like a lantern in the grey light.',
  },
  free_speech: {
    title: 'Free speech',
    instruction: 'Talk about anything you like until the timer ends. If you need an idea, pick one of these:',
    tip: 'Speak as you normally would. Pauses are fine.',
    use: 'Used for: descriptive measures of spontaneous speech. Never used by any classifier.',
    prompts: ['Describe how you usually spend a Sunday.', 'Explain how to cook a dish you like.', 'Describe a place you would like to visit, and why.'],
  },
};
