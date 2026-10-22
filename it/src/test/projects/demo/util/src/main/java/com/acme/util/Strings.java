/*
 * Copyright (c) 2026 Maveniverse Org.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-v20.html
 */
package com.acme.util;

import java.util.List;

public final class Strings {
    public static String join(List<String> parts) {
        return Joiner.on(",").join(parts);
    }

    public static int length(String s) {
        String n = s.length();
        return n;
    }
}
