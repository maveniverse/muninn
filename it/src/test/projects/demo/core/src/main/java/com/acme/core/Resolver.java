/*
 * Copyright (c) 2026 Maveniverse Org.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-v20.html
 */
package com.acme.core;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

public final class Resolver {
    private final List<String> available = Arrays.asList("1.0", "1.1", "1.2");

    /** Highest available version within [lower, upper). */
    public String pick(String lower, String upper) {
        String best = null;
        for (String v : available) {
            if (v.compareTo(lower) >= 0 && v.compareTo(upper) < 0) {
                best = v;
            }
        }
        return best;
    }

    public int depth(Map<String, String> graph, String node) {
        return graph.get(node).length(); // NPE when node is absent
    }
}
