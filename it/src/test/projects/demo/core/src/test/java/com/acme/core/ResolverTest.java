package com.acme.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.HashMap;
import org.junit.jupiter.api.Test;

public class ResolverTest {
    private final Resolver resolver = new Resolver();

    @Test
    void lowerBound() {
        assertEquals("1.0", resolver.pick("1.0", "1.1"));
    }

    @Test
    void rangeIncludesUpper() {
        // expects an inclusive upper bound, the implementation is exclusive
        assertEquals("1.2", resolver.pick("1.0", "1.2"));
    }

    @Test
    void emptyRange() {
        assertNull(resolver.pick("2.0", "3.0"));
    }

    @Test
    void missingNode() {
        resolver.depth(new HashMap<>(), "root");
    }
}
