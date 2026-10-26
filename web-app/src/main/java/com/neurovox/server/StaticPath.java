package com.neurovox.server;

/**
 * Converts a request path into a classpath resource name under {@code static/}, rejecting anything
 * that could escape that folder.
 *
 * <p>Rules (checked on the raw, undecoded path):
 * <ul>
 *   <li>must start with {@code /} and use only letters, digits, {@code - _ . /} — so no percent-encoding,
 *       backslashes, spaces or control characters can smuggle in {@code ..} or separators;</li>
 *   <li>no empty segments ({@code //}) and no segment starting with a dot — this rejects
 *       {@code .}, {@code ..} and hidden files such as {@code .DS_Store};</li>
 *   <li>a path ending in {@code /} means that folder's {@code index.html}.</li>
 * </ul>
 */
public final class StaticPath {

    public static final String ROOT = "static";

    private StaticPath() {
    }

    public static String toResource(String rawPath) {
        if (rawPath == null || rawPath.isEmpty() || rawPath.charAt(0) != '/') {
            throw invalid();
        }
        for (int i = 0; i < rawPath.length(); i++) {
            char c = rawPath.charAt(i);
            boolean allowed = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                    || c == '-' || c == '_' || c == '.' || c == '/';
            if (!allowed) {
                throw invalid();
            }
        }
        String path = rawPath.endsWith("/") ? rawPath + "index.html" : rawPath;
        String[] segments = path.substring(1).split("/", -1);
        for (String segment : segments) {
            if (segment.isEmpty() || segment.startsWith(".")) {
                throw invalid();
            }
        }
        return ROOT + path;
    }

    private static HttpError invalid() {
        return HttpError.badRequest("invalid_path", "That address is not a valid file path.");
    }
}
