package com.protogemcouch.wire;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class GoldenWireResponseTest {

    private static final int TX_ID = -1;
    private static final String GOLDEN_GET_VALUE = "golden-value";

    private static final Path GOLDEN_DIR = Path.of(
            "src",
            "test",
            "resources",
            "golden-wire"
    );

    @Test
    void supportedResponseFramesShouldMatchGoldenWireFixtures() {
        Map<String, byte[]> fixtures = new LinkedHashMap<>();

        fixtures.put("put-response.hex", GemResponseWriter.buildPutResponse(TX_ID));
        fixtures.put("get-string-response.hex", GemResponseWriter.buildGetResponse(TX_ID, GOLDEN_GET_VALUE));
        fixtures.put("remove-response.hex", GemResponseWriter.buildRemoveResponse(TX_ID));
        fixtures.put("contains-true-response.hex", GemResponseWriter.buildContainsResponse(TX_ID, true));
        fixtures.put("contains-false-response.hex", GemResponseWriter.buildContainsResponse(TX_ID, false));

        for (Map.Entry<String, byte[]> entry : fixtures.entrySet()) {
            assertGoldenFixture(entry.getKey(), entry.getValue());
        }
    }

    @Test
    void containsBooleanResponsesShouldUseGeodeBooleanObjectEncoding() {
        String trueHex = toHex(GemResponseWriter.buildContainsResponse(TX_ID, true));
        String falseHex = toHex(GemResponseWriter.buildContainsResponse(TX_ID, false));

        /*
         * Geode Boolean object encoding:
         *
         * 35 01 = Boolean.TRUE
         * 35 00 = Boolean.FALSE
         *
         * These checks protect against regressing back to raw int/byte[] responses,
         * which caused the Geode client ClassCastException earlier.
         */
        assertTrue(
                trueHex.contains("3501"),
                "CONTAINS true response should contain Geode Boolean TRUE encoding 35 01. Actual: " + trueHex
        );

        assertTrue(
                falseHex.contains("3500"),
                "CONTAINS false response should contain Geode Boolean FALSE encoding 35 00. Actual: " + falseHex
        );
    }

    @Test
    void getStringResponseShouldContainGeodeStringEncoding() {
        String hex = toHex(GemResponseWriter.buildGetResponse(TX_ID, GOLDEN_GET_VALUE));

        /*
         * Geode String object encoding used by the validated path:
         *
         * 57 = String type marker
         * followed by two-byte UTF-8 length
         * followed by UTF-8 bytes
         */
        assertTrue(
                hex.contains("57"),
                "GET String response should contain Geode String marker 0x57. Actual: " + hex
        );

        assertTrue(
                hex.contains(toHex(GOLDEN_GET_VALUE.getBytes(StandardCharsets.UTF_8))),
                "GET String response should contain the UTF-8 encoded string payload. Actual: " + hex
        );
    }

    private static void assertGoldenFixture(String fixtureName, byte[] actualBytes) {
        assertNotNull(actualBytes, "Actual bytes should not be null for " + fixtureName);
        assertTrue(actualBytes.length > 0, "Actual bytes should not be empty for " + fixtureName);

        String actualHex = toHex(actualBytes);
        Path fixturePath = GOLDEN_DIR.resolve(fixtureName);

        if (shouldUpdateGoldenFiles()) {
            writeFixture(fixturePath, actualHex);
            return;
        }

        assertTrue(
                Files.exists(fixturePath),
                "Missing golden-wire fixture: " + fixturePath
                        + System.lineSeparator()
                        + "Run this once to generate fixtures:"
                        + System.lineSeparator()
                        + "  mvn test -Dgolden.update=true"
        );

        String expectedHex = readFixture(fixturePath);

        assertEquals(
                normalizeHex(expectedHex),
                normalizeHex(actualHex),
                "Golden-wire fixture mismatch for " + fixtureName
        );
    }

    private static boolean shouldUpdateGoldenFiles() {
        String propertyValue = System.getProperty("golden.update");
        if (propertyValue != null && Boolean.parseBoolean(propertyValue)) {
            return true;
        }

        String envValue = System.getenv("GOLDEN_UPDATE");
        return envValue != null && Boolean.parseBoolean(envValue);
    }

    private static void writeFixture(Path fixturePath, String hex) {
        try {
            Files.createDirectories(fixturePath.getParent());

            String formatted = wrapHex(hex, 64) + System.lineSeparator();

            Files.writeString(
                    fixturePath,
                    formatted,
                    StandardCharsets.UTF_8
            );
        } catch (IOException e) {
            throw new IllegalStateException("Failed to write golden fixture " + fixturePath, e);
        }
    }

    private static String readFixture(Path fixturePath) {
        try {
            return Files.readString(fixturePath, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read golden fixture " + fixturePath, e);
        }
    }

    private static String toHex(byte[] bytes) {
        return HexFormat.of().formatHex(bytes);
    }

    private static String normalizeHex(String hex) {
        return hex == null
                ? ""
                : hex.replaceAll("\\s+", "").trim().toLowerCase();
    }

    private static String wrapHex(String hex, int width) {
        String normalized = normalizeHex(hex);

        StringBuilder out = new StringBuilder();
        for (int i = 0; i < normalized.length(); i += width) {
            int end = Math.min(i + width, normalized.length());
            out.append(normalized, i, end).append(System.lineSeparator());
        }

        return out.toString().trim();
    }
}