package com.neurovox.audio;

/** The uploaded bytes are not usable audio. The message is shown to the user. */
public final class InvalidAudioException extends IllegalArgumentException {

    private static final long serialVersionUID = 1L;

    public InvalidAudioException(String message) {
        super(message);
    }
}
