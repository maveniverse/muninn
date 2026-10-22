/*
 * Copyright (c) 2026 Maveniverse Org.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-v20.html
 */
package eu.maveniverse.maven.muninn.extension3;

/** String helpers for keeping report entries short. */
final class Text {

    private Text() {}

    /** Collapses whitespace runs and cuts at {@code max} characters with an ellipsis marker. */
    static String clip(String s, int max) {
        if (s == null) {
            return null;
        }
        String t = s.trim().replaceAll("[ \\t]+", " ");
        if (t.length() <= max) {
            return t;
        }
        return t.substring(0, max) + "…(+" + (t.length() - max) + " chars)";
    }

    /** First non-blank line. */
    static String firstLine(String s) {
        if (s == null) {
            return null;
        }
        for (String line : s.split("\\R")) {
            if (!line.trim().isEmpty()) {
                return line.trim();
            }
        }
        return s.trim();
    }
}
