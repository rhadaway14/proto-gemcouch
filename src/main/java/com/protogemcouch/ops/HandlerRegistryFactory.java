package com.protogemcouch.ops;

import com.protogemcouch.couchbase.Repository;
import com.protogemcouch.wire.MessageTypes;

public final class HandlerRegistryFactory {

    private HandlerRegistryFactory() {
    }

    public static OpcodeRegistry create(Repository repository) {
        OpcodeRegistry registry = new OpcodeRegistry();

        PdxTypeRegistry pdxTypeRegistry = new PdxTypeRegistry();
        PdxEnumRegistry pdxEnumRegistry = new PdxEnumRegistry();

        registry.register(MessageTypes.GET, new GetHandler(repository));
        registry.register(MessageTypes.PUT, new PutHandler(repository));
        registry.register(MessageTypes.REMOVE, new RemoveHandler(repository));
        registry.register(MessageTypes.INVALIDATE, new InvalidateHandler(repository));
        registry.register(MessageTypes.CLEAR_REGION, new ClearHandler(repository));
        registry.register(MessageTypes.GET_ENTRY, new GetEntryHandler(repository));
        registry.register(MessageTypes.QUERY, new QueryHandler(repository, pdxTypeRegistry));
        registry.register(MessageTypes.CONTAINS_KEY, new ContainsHandler(repository));
        registry.register(MessageTypes.KEY_SET, new KeySetOnServerHandler(repository));
        registry.register(MessageTypes.PUT_ALL, new PutAllHandler(repository));
        registry.register(MessageTypes.GET_CLIENT_PARTITION_ATTRIBUTES, new GetClientPartitionAttributesHandler());
        registry.register(MessageTypes.SIZE, new SizeOnServerHandler(repository));
        registry.register(MessageTypes.GET_ALL_70, new GetAllHandler(repository));
        registry.register(MessageTypes.CONTROL, new SimpleAckHandler("CONTROL FRAME type=18"));
        registry.register(MessageTypes.PING, new SimpleAckHandler("PING FRAME"));

        /*
         * PDX registry discovery showed Geode PdxInstanceFactory.create()
         * sends opcode 93 with one part containing a serialized
         * org.apache.geode.pdx.internal.PdxType.
         *
         * First-pass behavior:
         *   PdxType payload -> stable in-memory integer type id
         */
        registry.register(
                MessageTypes.GET_PDX_ID_FOR_TYPE,
                new PdxGetIdForTypeHandler(pdxTypeRegistry)
        );

        registry.register(
                MessageTypes.GET_PDX_ID_FOR_ENUM,
                new PdxGetIdForEnumHandler(pdxEnumRegistry)
        );

        return registry;
    }
}