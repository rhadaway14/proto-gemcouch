package com.protogemcouch.subscription;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Peer discovery for {@link MeshEventBackplane}. Two strategies, no external system:
 * <ul>
 *   <li>{@link #staticList(String)} — a fixed {@code host:port,host:port} list (compose, bare metal).</li>
 *   <li>{@link #dns(String, int)} — resolve a name's A records each call, on the mesh port; point it at
 *       a Kubernetes <b>headless Service</b> so it returns every replica's pod IP and tracks scaling.</li>
 * </ul>
 */
public final class MeshPeers {

    private MeshPeers() {
    }

    /** Fixed peer list parsed from {@code host:port,host:port}. */
    public static Supplier<List<InetSocketAddress>> staticList(String csv) {
        List<InetSocketAddress> fixed = parse(csv);
        return () -> fixed;
    }

    /** Re-resolve {@code host} to all its A records as peers on {@code port} each call (DNS = source of truth). */
    public static Supplier<List<InetSocketAddress>> dns(String host, int port) {
        return () -> {
            try {
                InetAddress[] addresses = InetAddress.getAllByName(host);
                List<InetSocketAddress> peers = new ArrayList<>(addresses.length);
                for (InetAddress address : addresses) {
                    peers.add(new InetSocketAddress(address, port));
                }
                return peers;
            } catch (UnknownHostException e) {
                return List.of(); // service not resolvable yet (e.g. no replicas up); retried next interval
            }
        };
    }

    static List<InetSocketAddress> parse(String csv) {
        List<InetSocketAddress> peers = new ArrayList<>();
        if (csv == null || csv.isBlank()) {
            return peers;
        }
        for (String token : csv.split(",")) {
            String trimmed = token.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            int colon = trimmed.lastIndexOf(':');
            if (colon <= 0 || colon == trimmed.length() - 1) {
                continue; // not host:port
            }
            try {
                String host = trimmed.substring(0, colon);
                int port = Integer.parseInt(trimmed.substring(colon + 1));
                peers.add(new InetSocketAddress(host, port));
            } catch (NumberFormatException ignored) {
                // skip a malformed entry rather than fail startup
            }
        }
        return peers;
    }

    /** This host's own addresses at {@code port}, so the mesh can skip connecting to itself. */
    static Set<InetSocketAddress> localAddresses(int port) {
        Set<InetSocketAddress> local = new HashSet<>();
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                for (InetAddress address : Collections.list(ni.getInetAddresses())) {
                    local.add(new InetSocketAddress(address, port));
                }
            }
        } catch (SocketException ignored) {
            // best effort; the originInstanceId guard still prevents self-delivery loops
        }
        return local;
    }
}
