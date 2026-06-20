package com.protogemcouch.tools;

import org.apache.geode.cache.InterestResultPolicy;
import org.apache.geode.cache.Region;
import org.apache.geode.cache.client.ClientCache;
import org.apache.geode.cache.client.ClientCacheFactory;
import org.apache.geode.cache.client.ClientRegionShortcut;

/**
 * Dev capture tool for subscription redundancy + keepalive. Connects a subscription client with
 * redundancy requested and a short ping interval, registers interest, and idles — so the shim's logs
 * reveal which control-connection opcodes a redundancy/keepalive client sends (e.g. MAKE_PRIMARY and
 * the periodic ping), surfaced by the shim as unknown opcodes / "UNKNOWN FRAME TYPE".
 *
 * <pre>mvn -o compile exec:java -Dexec.mainClass=com.protogemcouch.tools.RedundancyKeepaliveProbe \
 *   -Dexec.args="127.0.0.1 40405"</pre>
 */
public final class RedundancyKeepaliveProbe {

    private RedundancyKeepaliveProbe() {
    }

    public static void main(String[] args) throws Exception {
        String host = args.length > 0 ? args[0] : "127.0.0.1";
        int port = args.length > 1 ? Integer.parseInt(args[1]) : 40405;

        ClientCache cache = new ClientCacheFactory()
                .set("log-level", "warn")
                .setPoolSubscriptionEnabled(true)
                .setPoolSubscriptionRedundancy(1)   // request a redundant subscription copy
                .setPoolSubscriptionAckInterval(200)
                .setPoolPingInterval(1000)           // frequent keepalive pings
                .addPoolServer(host, port)
                .create();

        Region<String, Object> region = cache.<String, Object>createClientRegionFactory(ClientRegionShortcut.CACHING_PROXY)
                .create("redunProbe");
        region.registerInterest("ALL_KEYS", InterestResultPolicy.NONE);
        System.out.println("REDUNDANCY_PROBE registered; idling for pings + make-primary");
        Thread.sleep(6000); // let pings + MAKE_PRIMARY happen

        cache.close();
        System.out.println("REDUNDANCY_PROBE closed");
        System.exit(0);
    }
}
