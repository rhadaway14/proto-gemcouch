package com.protogemcouch.ops;

import com.protogemcouch.couchbase.Repository;
import com.protogemcouch.observability.AuditLog;
import com.protogemcouch.observability.MetricsRegistry;
import com.protogemcouch.subscription.SubscriptionRegistry;
import com.protogemcouch.tx.TransactionRegistry;
import com.protogemcouch.wire.MessageTypes;

public final class HandlerRegistryFactory {

    private HandlerRegistryFactory() {
    }

    public static OpcodeRegistry create(Repository repository) {
        return create(repository, new SubscriptionRegistry(), null);
    }

    public static OpcodeRegistry create(Repository repository, SubscriptionRegistry subscriptions) {
        return create(repository, subscriptions, null);
    }

    /**
     * Build the opcode registry. When {@code metrics} is non-null, sampled gauges for the in-memory
     * registry sizes (PDX types/enums, active transactions, subscription feeds/interests/CQs, durable
     * clients/queue depth) are registered, and the PDX type/enum registries are bounded by the optional
     * {@code MAX_PDX_TYPES} / {@code MAX_PDX_ENUMS} caps (0 = unlimited) with a metric + audit on reject.
     */
    public static OpcodeRegistry create(Repository repository, SubscriptionRegistry subscriptions,
                                        MetricsRegistry metrics) {
        OpcodeRegistry registry = new OpcodeRegistry();

        int maxPdxTypes = intEnv("MAX_PDX_TYPES", 0);
        int maxPdxEnums = intEnv("MAX_PDX_ENUMS", 0);
        PdxTypeRegistry pdxTypeRegistry = new PdxTypeRegistry(maxPdxTypes,
                () -> onPdxRegistryReject(metrics, "type", maxPdxTypes));
        PdxEnumRegistry pdxEnumRegistry = new PdxEnumRegistry(maxPdxEnums,
                () -> onPdxRegistryReject(metrics, "enum", maxPdxEnums));
        TransactionRegistry transactions = new TransactionRegistry();

        registry.register(MessageTypes.GET, new GetHandler(repository, transactions));
        registry.register(MessageTypes.PUT, new PutHandler(repository, transactions, subscriptions));
        registry.register(MessageTypes.REMOVE, new RemoveHandler(repository, transactions, subscriptions));
        registry.register(MessageTypes.COMMIT, new CommitHandler(repository, transactions));
        registry.register(MessageTypes.ROLLBACK, new RollbackHandler(transactions));
        // TX_FAILOVER (88): single-hop clients nominate the tx host before a transactional getEntry;
        // the single-backend shim is always the host, so ack it (else the client spins and the read hangs).
        registry.register(MessageTypes.TX_FAILOVER, new TxFailoverHandler());
        registry.register(MessageTypes.INVALIDATE, new InvalidateHandler(repository, subscriptions));
        registry.register(MessageTypes.CLEAR_REGION, new ClearHandler(repository));
        registry.register(MessageTypes.DESTROY_REGION, new DestroyRegionHandler(repository));
        registry.register(MessageTypes.GET_ENTRY, new GetEntryHandler(repository, transactions));
        // One PDX-aware field resolver shared by the QUERY path and CQ predicate matching, so an OQL
        // predicate on a PDX object field resolves identically whether one-shot or continuous.
        PdxAwareFieldResolver pdxFieldResolver = new PdxAwareFieldResolver(pdxTypeRegistry);
        subscriptions.setCqFieldResolver(pdxFieldResolver);
        QueryHandler queryHandler = new QueryHandler(repository, pdxFieldResolver);
        registry.register(MessageTypes.QUERY, queryHandler);
        registry.register(MessageTypes.QUERY_WITH_PARAMETERS, queryHandler);
        registry.register(MessageTypes.CONTAINS_KEY, new ContainsHandler(repository, transactions));
        registry.register(MessageTypes.KEY_SET, new KeySetOnServerHandler(repository, transactions));
        registry.register(MessageTypes.PUT_ALL, new PutAllHandler(repository));
        registry.register(MessageTypes.GET_CLIENT_PARTITION_ATTRIBUTES, new GetClientPartitionAttributesHandler());
        registry.register(MessageTypes.SIZE, new SizeOnServerHandler(repository, transactions));
        registry.register(MessageTypes.GET_ALL_70, new GetAllHandler(repository, transactions));
        registry.register(MessageTypes.CONTROL, new SimpleAckHandler("CONTROL FRAME type=18"));
        registry.register(MessageTypes.PING, new SimpleAckHandler("PING FRAME"));

        RegisterInterestHandler registerInterest = new RegisterInterestHandler(repository, subscriptions);
        registry.register(MessageTypes.REGISTER_INTEREST, registerInterest);
        registry.register(MessageTypes.REGISTER_INTEREST_LIST, registerInterest);
        UnregisterInterestHandler unregisterInterest = new UnregisterInterestHandler(subscriptions);
        registry.register(MessageTypes.UNREGISTER_INTEREST, unregisterInterest);
        registry.register(MessageTypes.UNREGISTER_INTEREST_LIST, unregisterInterest);

        ExecuteCqHandler executeCq = new ExecuteCqHandler(subscriptions, repository, pdxFieldResolver);
        registry.register(MessageTypes.EXECUTECQ, executeCq);
        registry.register(MessageTypes.EXECUTECQ_WITH_IR, executeCq);
        CloseCqHandler closeCq = new CloseCqHandler(subscriptions);
        registry.register(MessageTypes.STOPCQ, closeCq);
        registry.register(MessageTypes.CLOSECQ, closeCq);
        // Subscription/CQ clients periodically ack received events; drain without a reply.
        registry.register(MessageTypes.PERIODIC_ACK, new PeriodicAckHandler());
        // A durable client's readyForEvents() — replay its queued events, then resume live delivery.
        registry.register(MessageTypes.CLIENT_READY, new ClientReadyHandler(subscriptions));

        // The shim cannot run user Function code; reject function execution gracefully so a real
        // client raises a clean ServerOperationException instead of crashing/hanging.
        FunctionHandler functionHandler = new FunctionHandler();
        registry.register(MessageTypes.GET_FUNCTION_ATTRIBUTES, functionHandler);
        registry.register(MessageTypes.EXECUTE_FUNCTION, functionHandler);
        registry.register(MessageTypes.EXECUTE_REGION_FUNCTION, functionHandler);
        registry.register(MessageTypes.EXECUTE_REGION_FUNCTION_SINGLE_HOP, functionHandler);

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

        // Reverse PDX lookup so a client can decode a PDX value it did not write (e.g. a CQ event value
        // pushed from another client) — serves back the type the writer registered.
        registry.register(
                MessageTypes.GET_PDX_TYPE_BY_ID,
                new GetPdxTypeByIdHandler(pdxTypeRegistry)
        );

        // Bulk PDX registry discovery: a client syncing its whole registry pulls every type/enum at
        // once (GET_PDX_TYPES / GET_PDX_ENUMS), plus the reverse enum lookup (GET_PDX_ENUM_BY_ID).
        registry.register(MessageTypes.GET_PDX_TYPES, new GetPdxTypesHandler(pdxTypeRegistry));
        registry.register(MessageTypes.GET_PDX_ENUMS, new GetPdxEnumsHandler(pdxEnumRegistry));
        registry.register(MessageTypes.GET_PDX_ENUM_BY_ID, new GetPdxEnumByIdHandler(pdxEnumRegistry));

        if (metrics != null) {
            // Sampled at scrape time, so operators can watch (and alert on) the in-memory state these
            // registries hold — the growth/observability follow-up from the 1.0.0 security review.
            metrics.registerGauge("protogemcouch_pdx_types",
                    "Distinct PDX types currently registered.", () -> (long) pdxTypeRegistry.size());
            metrics.registerGauge("protogemcouch_pdx_enums",
                    "Distinct PDX enums currently registered.", () -> (long) pdxEnumRegistry.size());
            metrics.registerGauge("protogemcouch_active_transactions",
                    "Client transactions currently buffered (in flight).", () -> (long) transactions.activeCount());
            metrics.registerGauge("protogemcouch_subscription_feeds",
                    "Open server-to-client subscription feed channels.", () -> (long) subscriptions.feedCount());
            metrics.registerGauge("protogemcouch_registered_interests",
                    "Total registered interests across clients and regions.", () -> (long) subscriptions.interestCount());
            metrics.registerGauge("protogemcouch_registered_cqs",
                    "Total registered continuous queries across clients.", () -> (long) subscriptions.cqCount());
            metrics.registerGauge("protogemcouch_durable_clients",
                    "Durable subscription clients currently retained.", () -> (long) subscriptions.durableClientCount());
            metrics.registerGauge("protogemcouch_durable_queue_depth",
                    "Total queued (undelivered) events across durable clients.", subscriptions::durableQueueDepth);
        }

        return registry;
    }

    private static void onPdxRegistryReject(MetricsRegistry metrics, String kind, int cap) {
        if (metrics != null) {
            metrics.recordPdxRegistryRejected();
        }
        AuditLog.event("pdx_registry_cap_exceeded", "kind", kind, "cap", cap);
    }

    private static int intEnv(String name, int fallback) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}