package com.protogemcouch.probe;

import com.couchbase.client.java.Cluster;
import com.couchbase.client.java.Collection;
import com.couchbase.client.java.json.JsonObject;

public class SeedRemoveDoc {
    public static void main(String[] args) {
        Cluster cluster = Cluster.connect("couchbase://127.0.0.1", "Administrator", "password");
        Collection collection = cluster.bucket("test")
                .scope("_default")
                .collection("_default");

        collection.upsert("/helloWorld::proto::remove-test",
                JsonObject.create().put("value", "value-before-remove"));

        System.out.println("SEEDED");
        cluster.disconnect();
    }
}