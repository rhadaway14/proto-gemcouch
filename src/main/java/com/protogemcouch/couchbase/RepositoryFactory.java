package com.protogemcouch.couchbase;

import com.protogemcouch.config.ConfigException;
import com.protogemcouch.config.ServerConfig;

public final class RepositoryFactory {

    private RepositoryFactory() {
    }

    public static Repository create(ServerConfig config) {
        try {
            CouchbaseRepository repository = new CouchbaseRepository(config);
            repository.connect();
            return repository;
        } catch (Exception e) {
            throw new ConfigException("Failed to create repository during startup", e);
        }
    }

    public static void close(Repository repository) {
        if (repository instanceof CouchbaseRepository couchbaseRepository) {
            couchbaseRepository.disconnect();
        }
    }
}