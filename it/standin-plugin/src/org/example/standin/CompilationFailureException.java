/*
 * Copyright (c) 2026 Maveniverse Org.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-v20.html
 */
package org.example.standin;

import java.util.List;
import org.apache.maven.plugin.MojoFailureException;

/** Same message shape as maven-compiler-plugin's CompilationFailureException. */
public class CompilationFailureException extends MojoFailureException {
    private static final String LS = System.lineSeparator();

    public CompilationFailureException(List<String> messages) {
        super(null, shortMessage(messages), longMessage(messages));
    }

    static String longMessage(List<String> messages) {
        StringBuilder sb = new StringBuilder();
        for (String m : messages) sb.append(m).append(LS);
        return sb.toString();
    }

    static String shortMessage(List<String> messages) {
        StringBuilder sb = new StringBuilder("Compilation failure");
        if (messages.size() == 1) sb.append(LS).append(messages.get(0)).append(LS);
        return sb.toString();
    }
}
