package com.protogemcouch.config;

import com.protogemcouch.observability.StructuredLog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.net.ServerSocket;

public final class StartupValidator {

    private static final Logger log = LoggerFactory.getLogger(StartupValidator.class);

    private StartupValidator() {
    }

    public static void validate(ServerConfig config) {
        validatePortAvailable("SHIM_PORT", config.getShimPort());
        validatePortAvailable("HEALTH_PORT", config.getHealthPort());
        validateIdentifier("CB_BUCKET", config.getCouchbaseBucket());
        validateIdentifier("CB_SCOPE", config.getCouchbaseScope());
        validateIdentifier("CB_COLLECTION", config.getCouchbaseCollection());

        log.info(StructuredLog.event(
                "startup_validation_ok",
                "shimPort", config.getShimPort(),
                "healthPort", config.getHealthPort(),
                "bucket", config.getCouchbaseBucket(),
                "scope", config.getCouchbaseScope(),
                "collection", config.getCouchbaseCollection()
        ));
    }

    private static void validatePortAvailable(String name, int port) {
        try (ServerSocket socket = new ServerSocket()) {
            socket.setReuseAddress(false);
            socket.bind(new InetSocketAddress("0.0.0.0", port));
        } catch (Exception e) {
            throw new ConfigException(name + " is not available for binding: " + port, e);
        }
    }

    private static void validateIdentifier(String name, String value) {
        if (value.contains("`")) {
            throw new ConfigException(name + " must not contain backticks: " + value);
        }
    }
}