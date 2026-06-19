package com.protogemcouch.subscription;

import com.protogemcouch.serialization.StoredValue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.io.UncheckedIOException;

/**
 * A mutation event broadcast across replicas by an {@link EventBackplane}. Mirrors the local publish
 * entry points of {@link SubscriptionRegistry} one-to-one ({@link Kind}), carrying exactly what a
 * receiving replica needs to re-deliver the event to its own feeds: the region/key, the new value
 * (for writes / CQ matching), the prior value (for CQ update/destroy decisions), the create-vs-update
 * flag, the originating client id (for self-event suppression), and the originating replica id (so a
 * replica drops its own echoed events).
 */
public record RemoteEvent(
        Kind kind,
        String region,
        String key,
        StoredValue value,
        StoredValue priorValue,
        boolean update,
        String originClientId,
        String originInstanceId
) implements Serializable {

    private static final long serialVersionUID = 1L;

    public enum Kind {
        /** Register-interest write (LOCAL_CREATE / LOCAL_UPDATE depending on {@link #update()}). */
        WRITE,
        /** Register-interest destroy. */
        DESTROY,
        /** Register-interest invalidate. */
        INVALIDATE,
        /** Continuous-query write evaluation (uses {@link #value()} + {@link #priorValue()}). */
        CQ_EVENT,
        /** Continuous-query destroy evaluation (uses {@link #priorValue()}). */
        CQ_DESTROY
    }

    /** Serialize for the backplane wire (Java serialization; {@link StoredValue} is Serializable). */
    public byte[] toBytes() {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ObjectOutputStream out = new ObjectOutputStream(bos)) {
            out.writeObject(this);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return bos.toByteArray();
    }

    /** Inverse of {@link #toBytes()}. */
    public static RemoteEvent fromBytes(byte[] bytes) {
        try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(bytes))) {
            return (RemoteEvent) in.readObject();
        } catch (IOException | ClassNotFoundException e) {
            throw new IllegalArgumentException("Unreadable RemoteEvent payload", e);
        }
    }
}
