package com.betterschool.marketing.types.id;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UuidIdGeneratorTest {

    @Test
    void nextIdReturnsParseableCanonicalUuidString() {
        for (int i = 0; i < 100; i++) {
            String id = UuidIdGenerator.INSTANCE.nextId();
            assertEquals(36, id.length(), "长度必须 36：" + id);
            assertEquals(id, id.toLowerCase(), "必须小写规范形：" + id);
            assertEquals(id, UUID.fromString(id).toString(), "必须可被 UUID.fromString 解析且往返一致：" + id);
        }
    }

    @Test
    void nextIdIsUniqueAcrossCalls() {
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < 10_000; i++) {
            assertTrue(ids.add(UuidIdGenerator.INSTANCE.nextId()));
        }
    }
}
