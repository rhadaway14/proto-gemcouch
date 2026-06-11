package com.protogemcouch.ops;

import com.protogemcouch.query.OqlQuery;
import com.protogemcouch.serialization.StoredValue;

/**
 * An {@link OqlQuery.FieldResolver} that reads a query field from a stored PDX instance (via the kept
 * PdxType in {@link PdxTypeRegistry}) and otherwise falls back to map-typed resolution. Shared
 * by both the QUERY path ({@link QueryHandler}) and continuous-query predicate matching
 * ({@code SubscriptionRegistry}), so an OQL predicate like {@code r.status = 'active'} resolves the
 * same way against PDX objects whether it is a one-shot query or a CQ.
 */
public final class PdxAwareFieldResolver implements OqlQuery.FieldResolver {

    private final PdxTypeRegistry pdxTypeRegistry;

    public PdxAwareFieldResolver(PdxTypeRegistry pdxTypeRegistry) {
        this.pdxTypeRegistry = pdxTypeRegistry;
    }

    @Override
    public Object resolve(StoredValue value, String field) {
        if (value != null && value.type() == StoredValue.Type.PDX_INSTANCE) {
            Object resolved = PdxFieldAccessor.read(value.asPdxInstanceValue(), pdxTypeRegistry, field);
            return resolved == null ? OqlQuery.ABSENT : resolved;
        }
        return OqlQuery.MAP_RESOLVER.resolve(value, field);
    }
}
