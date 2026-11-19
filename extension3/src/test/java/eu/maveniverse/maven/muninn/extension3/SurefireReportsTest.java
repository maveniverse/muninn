/*
 * Copyright (c) 2026 Maveniverse Org.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-v20.html
 */
package eu.maveniverse.maven.muninn.extension3;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SurefireReportsTest {

    @TempDir
    Path dir;

    /** Two executions sharing a reports directory, the second starting in the same second the first ended. */
    @Test
    void reportsOfAnEarlierExecutionAreNotListedTwice() throws Exception {
        long start = System.currentTimeMillis();
        Path first = Files.write(dir.resolve("TEST-a.A.xml"), "<testsuite tests=\"1\"/>".getBytes());
        Files.setLastModifiedTime(first, FileTime.fromMillis(start));
        Map<Path, FileTime> seen = new HashMap<>();
        assertEquals(1, SurefireReports.list(dir, start, seen).size());

        Path second = Files.write(dir.resolve("TEST-b.B.xml"), "<testsuite tests=\"1\"/>".getBytes());
        Files.setLastModifiedTime(second, FileTime.fromMillis(start));
        assertEquals(1, SurefireReports.list(dir, start, seen).size());

        // the same test class run again by the second execution rewrites the file: count it again
        Files.setLastModifiedTime(first, FileTime.fromMillis(start + 2000));
        assertEquals(1, SurefireReports.list(dir, start, seen).size());
    }
}
