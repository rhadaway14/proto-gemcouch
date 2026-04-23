package com.protogemcouch.couchbase;

import com.protogemcouch.config.ServerConfig;

public final class RepositoryFactory {

    private RepositoryFactory() {
    }

    public static Repository create(ServerConfig config) {
        CouchbaseRepository repository = new CouchbaseRepository(config);
        repository.connect();
        return repository;
    }

    public static void close(Repository repository) {
        if (repository instanceof CouchbaseRepository couchbaseRepository) {
            couchbaseRepository.disconnect();
        }
    }
}