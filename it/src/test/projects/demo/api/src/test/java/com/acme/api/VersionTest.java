package com.acme.api;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

public class VersionTest {
    @Test
    void current() {
        assertEquals("1.0", Version.current());
    }

    @Disabled("exercises the skipped count")
    @Test
    void later() {}
}
