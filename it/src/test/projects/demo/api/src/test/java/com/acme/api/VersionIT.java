package com.acme.api;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

public class VersionIT {
    @Test
    void current() {
        assertEquals("1.0", Version.current());
    }
}
