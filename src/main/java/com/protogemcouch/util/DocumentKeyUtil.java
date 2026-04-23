package com.protogemcouch.util;

public final class DocumentKeyUtil {

    private DocumentKeyUtil() {
    }

    public static String docId(String region, String key) {
        return region + "::" + key;
    }
}