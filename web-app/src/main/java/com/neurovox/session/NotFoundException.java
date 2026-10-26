package com.neurovox.session;

/** A requested profile, session or sample does not exist. The HTTP layer maps this to 404. */
public final class NotFoundException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public NotFoundException(String message) {
        super(message);
    }
}
