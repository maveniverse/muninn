/*
 * Copyright (c) 2026 Maveniverse Org.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-v20.html
 */
package eu.maveniverse.maven.muninn.extension3;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import org.apache.maven.project.MavenProject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Fed with the reports Checkstyle 3.6.0, PMD 3.28.0 and SpotBugs 4.10.5.0 wrote for the same bad class. */
class LintReportsTest {

    @TempDir
    Path root;

    private MavenProject project;

    @BeforeEach
    void setUp() throws Exception {
        Path src = Files.createDirectories(root.resolve("lint/src/main/java/p"));
        Files.write(src.resolve("Bad.java"), new byte[0]);
        project = new MavenProject();
        project.setFile(root.resolve("lint/pom.xml").toFile());
    }

    private Map<String, Object> read(String tool, String fixture) throws Exception {
        String xml;
        try (InputStream in = getClass().getResourceAsStream("/lint/" + fixture);
                Scanner sc = new Scanner(in, "UTF-8").useDelimiter("\\A")) {
            xml = sc.next().replace("/ROOT", root.toString());
        }
        Path report = Files.write(root.resolve("lint/" + fixture), xml.getBytes(StandardCharsets.UTF_8));
        return LintReports.read(tool, report, project, new ReactorPaths(root), new FailureAnalyzer.Limits());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> violation(Map<String, Object> m, int i) {
        return ((List<Map<String, Object>>) m.get("violations")).get(i);
    }

    @Test
    void checkstyle() throws Exception {
        Map<String, Object> m = read("checkstyle", "checkstyle-result.xml");
        assertEquals("6 checkstyle violation(s)", m.get("summary"));
        Map<String, Object> v = violation(m, 1);
        assertEquals("lint/src/main/java/p/Bad.java", v.get("file"));
        assertEquals(3, v.get("line"));
        assertEquals(8, v.get("col"));
        assertEquals("UnusedImports", v.get("rule"));
        assertEquals("Unused import - java.util.List.", v.get("message"));
        assertEquals("lint/checkstyle-result.xml", m.get("report"));
    }

    @Test
    void pmd() throws Exception {
        Map<String, Object> m = read("pmd", "pmd.xml");
        assertEquals("1 pmd violation(s)", m.get("summary"));
        Map<String, Object> v = violation(m, 0);
        assertEquals("lint/src/main/java/p/Bad.java", v.get("file"));
        assertEquals(3, v.get("line"));
        assertEquals("UnnecessaryImport", v.get("rule"));
        assertEquals("Unused import 'java.util.List'", v.get("message"));
    }

    @Test
    void spotbugsPointsAtThePrimaryLine() throws Exception {
        Map<String, Object> m = read("spotbugs", "spotbugsXml.xml");
        assertEquals("1 spotbugs violation(s)", m.get("summary"));
        Map<String, Object> v = violation(m, 0);
        assertEquals("lint/src/main/java/p/Bad.java", v.get("file"));
        assertEquals(8, v.get("line"));
        assertEquals("NP_ALWAYS_NULL", v.get("rule"));
        assertEquals("Null pointer dereference of t in p.Bad.size(String)", v.get("message"));
    }
}
