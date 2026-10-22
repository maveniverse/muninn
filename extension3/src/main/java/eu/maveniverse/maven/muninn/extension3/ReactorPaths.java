/*
 * Copyright (c) 2026 Maveniverse Org.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-v20.html
 */
package eu.maveniverse.maven.muninn.extension3;

import java.nio.file.Path;

/** Makes paths relative to the reactor root, which is what an agent's working directory is. */
final class ReactorPaths {

    private final Path root;

    ReactorPaths(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    String rel(Path p) {
        Path abs = p.toAbsolutePath().normalize();
        if (abs.startsWith(root)) {
            String r =
                    root.relativize(abs).toString().replace(root.getFileSystem().getSeparator(), "/");
            return r.isEmpty() ? "." : r;
        }
        return abs.toString();
    }

    /** Rewrites absolute paths under the reactor root anywhere in a message. */
    String relString(String s) {
        if (s == null) {
            return null;
        }
        String sep = root.getFileSystem().getSeparator();
        String prefix = root.toString() + sep;
        String out = s.replace("file://" + prefix, "").replace(prefix, "");
        return "/".equals(sep) ? out : out.replace(sep, "/");
    }

    Path root() {
        return root;
    }
}
