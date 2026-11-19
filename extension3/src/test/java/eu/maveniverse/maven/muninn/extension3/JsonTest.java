/*
 * Copyright (c) 2026 Maveniverse Org.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-v20.html
 */
package eu.maveniverse.maven.muninn.extension3;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class JsonTest {

    @Test
    void escapesAndOmitsNulls() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("s", "a\"b\\c\nd\te\u0001");
        m.put("gone", null);
        m.put("n", 1.5);
        m.put("b", true);
        m.put("l", Arrays.asList(1, "x", null));
        assertEquals("{\"s\":\"a\\\"b\\\\c\\nd\\te\\u0001\",\"n\":1.5,\"b\":true,\"l\":[1,\"x\",null]}", Json.write(m));
    }
}
