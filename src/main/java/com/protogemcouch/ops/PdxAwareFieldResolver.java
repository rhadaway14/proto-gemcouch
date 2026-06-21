package com.protogemcouch.ops;

import com.protogemcouch.query.OqlQuery;
import com.protogemcouch.serialization.StoredValue;

import java.util.List;

/**
 * An {@link OqlQuery.FieldResolver} that reads a query field path from a stored PDX instance (via the
 * kept PdxType in {@link PdxTypeRegistry}) and otherwise falls back to map-typed resolution. Shared
 * by both the QUERY path ({@link QueryHandler}) and continuous-query predicate matching
 * ({@code SubscriptionRegistry}), so an OQL predicate like {@code r.status = 'active'} — or a nested
 * {@code r.address.zip} — resolves the same way against PDX objects whether it is a one-shot query or a CQ.
 */
public final class PdxAwareFieldResolver implements OqlQuery.FieldResolver {

    private final PdxTypeRegistry pdxTypeRegistry;

    public PdxAwareFieldResolver(PdxTypeRegistry pdxTypeRegistry) {
        this.pdxTypeRegistry = pdxTypeRegistry;
    }

    @Override
    public Object resolve(StoredValue value, List<String> path) {
        if (value != null && value.type() == StoredValue.Type.PDX_INSTANCE) {
            return PdxFieldAccessor.resolvePath(value.asPdxInstanceValue(), pdxTypeRegistry, path);
        }
        return OqlQuery.MAP_RESOLVER.resolve(value, path);
    }
}
