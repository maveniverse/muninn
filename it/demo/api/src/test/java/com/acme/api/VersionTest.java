package com.acme.api;

public class VersionTest {
    public void testCurrent() {
        if (!"1.0".equals(Version.current())) throw new AssertionError("wrong version");
    }
}
