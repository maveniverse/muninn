/*
 * Copyright (c) 2026 Maveniverse Org.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-v20.html
 */
package eu.maveniverse.maven.muninn.extension3;

import static eu.maveniverse.maven.muninn.extension3.SurefireReports.firstChild;
import static eu.maveniverse.maven.muninn.extension3.SurefireReports.intAttr;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.xml.parsers.DocumentBuilder;
import org.apache.maven.plugin.MojoExecution;
import org.apache.maven.project.MavenProject;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Reads the XML reports of Checkstyle, PMD and SpotBugs, whose check goals fail with only a count ("You have 6
 * Checkstyle violations"), and lists the violations with file, line and rule.
 */
final class LintReports {

    private LintReports() {}

    /** The tool whose check goal this is: checkstyle, pmd or spotbugs; null for any other mojo. */
    static String tool(MojoExecution mojo) {
        if (mojo == null || !"check".equals(mojo.getGoal())) {
            return null;
        }
        switch (String.valueOf(mojo.getArtifactId())) {
            case "maven-checkstyle-plugin":
                return "checkstyle";
            case "maven-pmd-plugin":
                return "pmd";
            case "spotbugs-maven-plugin":
                return "spotbugs";
            default:
                return null;
        }
    }

    /** Kind, summary, violations and the report path; null if the report cannot be read. */
    static Map<String, Object> read(
            String tool, Path report, MavenProject project, ReactorPaths paths, FailureAnalyzer.Limits limits) {
        DocumentBuilder db = SurefireReports.documentBuilder();
        Element root;
        try {
            root = db.parse(report.toFile()).getDocumentElement();
        } catch (Exception e) {
            return null; // not written (e.g. a custom output format) or unreadable
        }
        List<Map<String, Object>> violations = new ArrayList<>();
        if ("checkstyle".equals(tool)) {
            for (Element file : elements(root, "file")) {
                for (Element e : elements(file, "error")) {
                    String severity = e.getAttribute("severity");
                    if (severity.equals("error") || severity.equals("warning")) {
                        String source = e.getAttribute("source");
                        String rule = source.substring(source.lastIndexOf('.') + 1);
                        violations.add(violation(
                                paths.rel(Paths.get(file.getAttribute("name"))),
                                intAttr(e, "line"),
                                intAttr(e, "column"),
                                rule.endsWith("Check") ? rule.substring(0, rule.length() - 5) : rule,
                                e.getAttribute("message"),
                                limits));
                    }
                }
            }
        } else if ("pmd".equals(tool)) {
            for (Element file : elements(root, "file")) {
                for (Element v : elements(file, "violation")) {
                    violations.add(violation(
                            paths.rel(Paths.get(file.getAttribute("name"))),
                            intAttr(v, "beginline"),
                            intAttr(v, "begincolumn"),
                            v.getAttribute("rule"),
                            v.getTextContent(),
                            limits));
                }
            }
        } else {
            List<Path> roots = SurefireReports.sourceRoots(project);
            for (Element bug : elements(root, "BugInstance")) {
                Element line = primarySourceLine(bug);
                String file = line != null ? sourceFile(line.getAttribute("sourcepath"), roots, paths) : null;
                Element msg = firstChild(bug, "LongMessage");
                if (msg == null) {
                    msg = firstChild(bug, "ShortMessage");
                }
                violations.add(violation(
                        file,
                        line != null ? intAttr(line, "start") : 0,
                        0,
                        bug.getAttribute("type"),
                        msg != null ? msg.getTextContent() : "",
                        limits));
            }
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("kind", "lint");
        m.put("tool", tool);
        m.put("summary", violations.size() + " " + tool + " violation(s)");
        m.put(
                "violations",
                violations.size() <= limits.diagnostics
                        ? violations
                        : new ArrayList<>(violations.subList(0, limits.diagnostics)));
        if (violations.size() > limits.diagnostics) {
            m.put("omitted", violations.size() - limits.diagnostics);
        }
        m.put("report", paths.rel(report));
        return m;
    }

    private static Map<String, Object> violation(
            String file, int line, int col, String rule, String message, FailureAnalyzer.Limits limits) {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("file", file);
        if (line > 0) {
            v.put("line", line);
        }
        if (col > 0) {
            v.put("col", col);
        }
        v.put("rule", rule);
        v.put("message", Text.clip(message.trim(), limits.message));
        return v;
    }

    /** The line SpotBugs marks primary (e.g. the dereference), else the method's, else the class's. */
    private static Element primarySourceLine(Element bug) {
        Element fallback = null;
        for (Node n = bug.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (!(n instanceof Element)) {
                continue;
            }
            Element e = (Element) n;
            if (e.getNodeName().equals("SourceLine") && "true".equals(e.getAttribute("primary"))) {
                return e;
            }
            Element nested = firstChild(e, "SourceLine");
            if (nested != null && (fallback == null || e.getNodeName().equals("Method"))) {
                fallback = nested;
            }
        }
        return fallback;
    }

    /** SpotBugs names files relative to a source root ("p/Bad.java"). */
    private static String sourceFile(String sourcepath, List<Path> roots, ReactorPaths paths) {
        for (Path root : roots) {
            Path f = root.resolve(sourcepath);
            if (Files.isRegularFile(f)) {
                return paths.rel(f);
            }
        }
        return sourcepath;
    }

    private static List<Element> elements(Element parent, String name) {
        List<Element> out = new ArrayList<>();
        NodeList list = parent.getElementsByTagName(name);
        for (int i = 0; i < list.getLength(); i++) {
            out.add((Element) list.item(i));
        }
        return out;
    }
}
