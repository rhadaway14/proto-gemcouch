package com.protogemcouch.util;

public final class DocumentKeyUtil {

    private DocumentKeyUtil() {
    }

    public static String docId(String region, String key) {
        return region + "::" + key;
    }

    /** The docId prefix shared by every key in a region: {@code region + "::"}. */
    public static String regionPrefix(String region) {
        return region + "::";
    }
}