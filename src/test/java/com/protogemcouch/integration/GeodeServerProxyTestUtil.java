package com.protogemcouch.integration;

import org.apache.geode.cache.Region;

public final class GeodeServerProxyTestUtil {

    private GeodeServerProxyTestUtil() {
    }

    public static boolean containsValueForKeyOnServer(Region<String, Object> region, String key) {
        try {
            Object serverProxy = region.getClass()
                    .getMethod("getServerProxy")
                    .invoke(region);

            if (serverProxy == null) {
                throw new IllegalStateException("Region does not have a server proxy");
            }

            Object result = serverProxy.getClass()
                    .getMethod("containsValueForKey", Object.class)
                    .invoke(serverProxy, key);

            if (!(result instanceof Boolean boolResult)) {
                throw new IllegalStateException(
                        "containsValueForKey returned unexpected type: "
                                + (result == null ? "null" : result.getClass().getName())
                );
            }

            return boolResult;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(
                    "Failed to invoke server-side containsValueForKey through Geode server proxy. "
                            + "Region type was: " + region.getClass().getName(),
                    e
            );
        }
    }
}