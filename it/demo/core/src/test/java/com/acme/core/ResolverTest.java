package com.acme.core;

import java.util.HashMap;

public class ResolverTest {
    private final Resolver resolver = new Resolver();

    public void testLowerBound() {
        check("1.0", resolver.pick("1.0", "1.1"));
    }

    public void testRangeIncludesUpper() {
        // expects an inclusive upper bound, the implementation is exclusive
        check("1.2", resolver.pick("1.0", "1.2"));
    }

    public void testEmptyRange() {
        check(null, resolver.pick("2.0", "3.0"));
    }

    public void testMissingNode() {
        resolver.depth(new HashMap<>(), "root");
    }

    private static void check(String expected, String actual) {
        if (expected == null ? actual != null : !expected.equals(actual)) {
            throw new AssertionError("expected:<" + expected + "> but was:<" + actual + ">");
        }
    }
}
