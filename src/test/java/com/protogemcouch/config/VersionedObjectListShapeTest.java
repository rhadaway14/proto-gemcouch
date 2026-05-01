package com.protogemcouch.wire;

import org.apache.geode.internal.cache.tier.sockets.VersionedObjectList;
import org.apache.geode.internal.util.BlobHelper;
import org.junit.jupiter.api.Test;

import java.util.HexFormat;

class VersionedObjectListShapeTest {

    @Test
    void printVersionedObjectListWireShape() throws Exception {
        VersionedObjectList list = new VersionedObjectList(3, true, false);

        list.addObject("key-1", "value-1", null);
        list.addObject("key-2", "value-2", null);
        list.addObjectPartForAbsentKey("missing", null);

        byte[] bytes = BlobHelper.serializeToBlob(list);

        System.out.println("VERSIONED_OBJECT_LIST_HEX_START");
        System.out.println(HexFormat.of().formatHex(bytes));
        System.out.println("VERSIONED_OBJECT_LIST_HEX_END");
    }
}