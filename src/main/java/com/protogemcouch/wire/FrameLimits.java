package com.protogemcouch.wire;

/**
 * Immutable safety limits applied while decoding inbound Geode protocol frames.
 *
 * <p>The Geode wire format encodes payload length, part count, and per-part length as raw 32-bit
 * integers. Without bounds, a corrupt or hostile frame (e.g. a part length of {@link
 * Integer#MAX_VALUE}) would drive the decoder into an enormous allocation and crash the shim with
 * an {@link OutOfMemoryError}. These limits cap that exposure so a bad frame is rejected and the
 * offending connection closed, rather than taking down the process.
 */
public final class FrameLimits {

    /** 50 MiB. Generous for legitimate bulk PUT_ALL/GET_ALL payloads, far below an OOM trigger. */
    public static final int DEFAULT_MAX_FRAME_BYTES = 50 * 1024 * 1024;

    /** Upper bound on the number of parts a single frame may declare. */
    public static final int DEFAULT_MAX_PARTS = 100_000;

    private final int maxFrameBytes;
    private final int maxParts;

    public FrameLimits(int maxFrameBytes, int maxParts) {
        if (maxFrameBytes <= 0) {
            throw new IllegalArgumentException("maxFrameBytes must be positive, but was: " + maxFrameBytes);
        }
        if (maxParts <= 0) {
            throw new IllegalArgumentException("maxParts must be positive, but was: " + maxParts);
        }
        this.maxFrameBytes = maxFrameBytes;
        this.maxParts = maxParts;
    }

    /** Default limits. */
    public static FrameLimits defaults() {
        return new FrameLimits(DEFAULT_MAX_FRAME_BYTES, DEFAULT_MAX_PARTS);
    }

    /**
     * Build limits from environment variables, falling back to defaults:
     * <ul>
     *   <li>{@code MAX_FRAME_BYTES}</li>
     *   <li>{@code MAX_FRAME_PARTS}</li>
     * </ul>
     * Invalid or non-positive values fall back to the default rather than failing startup, since
     * these are defensive caps rather than required configuration.
     */
    public static FrameLimits fromEnv() {
        return new FrameLimits(
                parsePositiveOrDefault(System.getenv("MAX_FRAME_BYTES"), DEFAULT_MAX_FRAME_BYTES),
                parsePositiveOrDefault(System.getenv("MAX_FRAME_PARTS"), DEFAULT_MAX_PARTS)
        );
    }

    public int maxFrameBytes() {
        return maxFrameBytes;
    }

    public int maxParts() {
        return maxParts;
    }

    private static int parsePositiveOrDefault(String rawValue, int defaultValue) {
        if (rawValue == null || rawValue.isBlank()) {
            return defaultValue;
        }
        try {
            int parsed = Integer.parseInt(rawValue.trim());
            return parsed > 0 ? parsed : defaultValue;
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    @Override
    public String toString() {
        return "FrameLimits{maxFrameBytes=" + maxFrameBytes + ", maxParts=" + maxParts + '}';
    }
}
