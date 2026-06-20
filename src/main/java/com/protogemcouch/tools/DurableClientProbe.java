package com.protogemcouch.tools;

import org.apache.geode.cache.InterestResultPolicy;
import org.apache.geode.cache.Region;
import org.apache.geode.cache.client.ClientCache;
import org.apache.geode.cache.client.ClientCacheFactory;
import org.apache.geode.cache.client.ClientRegionShortcut;

/**
 * Dev capture tool for durable-client support. Connects a <b>durable</b> Geode client (a recognizable
 * {@code durable-client-id}) with subscriptions enabled, registers interest, and calls
 * {@code readyForEvents()} — so the shim's logs reveal:
 * <ul>
 *   <li>the durable id inside the handshake membership bytes (search the {@code handshake_request} hex
 *       for the ASCII marker, e.g. {@code DURABLEMARKER1} = {@code 4455...}), and</li>
 *   <li>the opcode {@code readyForEvents()} sends (logged by the shim as an unknown opcode until it is
 *       handled — that's the {@code CLIENT_READY} opcode).</li>
 * </ul>
 *
 * <pre>mvn -o compile exec:java -Dexec.mainClass=com.protogemcouch.tools.DurableClientProbe \
 *   -Dexec.args="127.0.0.1 40405"</pre>
 *
 * <p><b>Findings (Geode 1.15 client):</b>
 * <ul>
 *   <li>The durable id is the <b>last {@code 57 00 <len> <bytes>} string</b> in the handshake (an empty
 *       {@code 57 00 00} for a non-durable client), immediately followed by a 4-byte int = the
 *       durable-client-timeout (seconds). So the durable id is a stable cross-reconnect identity, unlike
 *       the raw membership hex.</li>
 *   <li>{@code readyForEvents()} sends <b>opcode 53</b> ({@code CLIENT_READY}) on the control connection
 *       — the trigger to replay a reconnected durable client's queued events.</li>
 * </ul>
 */
public final class DurableClientProbe {

    private DurableClientProbe() {
    }

    public static void main(String[] args) throws Exception {
        String host = args.length > 0 ? args[0] : "127.0.0.1";
        int port = args.length > 1 ? Integer.parseInt(args[1]) : 40405;
        String durableId = args.length > 2 ? args[2] : "DURABLEMARKER1";

        ClientCache cache = new ClientCacheFactory()
                .set("log-level", "warn")
                .set("durable-client-id", durableId)
                .set("durable-client-timeout", "60")
                .setPoolSubscriptionEnabled(true)
                .setPoolSubscriptionRedundancy(0)
                .addPoolServer(host, port)
                .create();

        Region<String, Object> region = cache.<String, Object>createClientRegionFactory(ClientRegionShortcut.CACHING_PROXY)
                .create("durableProbe");
        region.registerInterest("ALL_KEYS", InterestResultPolicy.NONE);

        cache.readyForEvents(); // sends the CLIENT_READY opcode
        System.out.println("DURABLE_PROBE durableId=" + durableId + " readyForEvents sent");
        Thread.sleep(2000);

        cache.close(true); // keepalive close: a durable client asks the server to retain its queue
        System.out.println("DURABLE_PROBE closed (keepalive=true)");
        System.exit(0);
    }
}
