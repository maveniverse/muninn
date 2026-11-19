/*
 * Copyright (c) 2026 Maveniverse Org.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-v20.html
 */
package eu.maveniverse.maven.muninn.extension3;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.apache.maven.project.MavenProject;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Reads surefire/failsafe XML reports (the standard TEST-*.xml format) and turns failing
 * test cases into compact entries. Reading the reports is more robust than scraping the
 * console: the format is stable across surefire versions and test frameworks.
 */
final class SurefireReports {

    /** Frames from these packages never help an agent locate the problem. */
    private static final String[] NOISE = {
        "java.",
        "javax.",
        "jdk.",
        "sun.",
        "com.sun.",
        "kotlin.",
        "scala.",
        "org.junit.",
        "junit.",
        "org.opentest4j.",
        "org.testng.",
        "org.apiguardian.",
        "org.apache.maven.",
        "org.codehaus.plexus.",
        "org.eclipse.sisu.",
        "com.google.inject.",
        "org.assertj.",
        "org.hamcrest.",
        "org.mockito.",
        "net.bytebuddy.",
        "org.spockframework.",
    };

    private SurefireReports() {}

    static final class Result {
        int run;
        int failed;
        int errors;
        int skipped;
        int omitted;
        final List<Map<String, Object>> failures = new ArrayList<>();

        /** Adds another execution's results, keeping at most {@code maxFailures} failure entries. */
        void add(Result other, int maxFailures) {
            run += other.run;
            failed += other.failed;
            errors += other.errors;
            skipped += other.skipped;
            omitted += other.omitted;
            for (Map<String, Object> f : other.failures) {
                if (failures.size() < maxFailures) {
                    failures.add(f);
                } else {
                    omitted++;
                }
            }
        }
    }

    /**
     * The {@code TEST-*.xml} files in {@code reportsDir} written since {@code since} (epoch millis); older ones are
     * leftovers of earlier builds. Files already in {@code seen} with the same timestamp were counted by an earlier
     * execution in this module and are skipped; the listed ones are added to it.
     */
    static List<Path> list(Path reportsDir, long since, Map<Path, FileTime> seen) {
        List<Path> files = new ArrayList<>();
        // ponytail: whole-second cutoff covers filesystems with 1s mtime granularity; a report written in
        // the same second just before this build started still slips through
        long cutoff = since / 1000 * 1000;
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(reportsDir, "TEST-*.xml")) {
            for (Path f : ds) {
                FileTime t = Files.getLastModifiedTime(f);
                if (t.toMillis() >= cutoff && !t.equals(seen.put(f, t))) {
                    files.add(f);
                }
            }
        } catch (IOException e) {
            return files; // no reports directory: nothing ran, or a different layout
        }
        files.sort(Comparator.naturalOrder());
        return files;
    }

    /**
     * @param files   report files, see {@link #list}
     * @param project the module: frames from its classes are "ours", its source roots locate them
     * @param paths   relativizes absolute paths for the report
     */
    static Result read(
            List<Path> files,
            MavenProject project,
            ReactorPaths paths,
            int maxFailures,
            int maxFrames,
            int maxMessage) {
        Result r = new Result();
        List<Path> ownClassDirs = ownClassDirs(project);
        List<Path> sourceRoots = sourceRoots(project);
        DocumentBuilder db;
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setNamespaceAware(false);
            f.setExpandEntityReferences(false);
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            db = f.newDocumentBuilder();
        } catch (Exception e) {
            return r;
        }
        for (Path file : files) {
            Document doc;
            try {
                doc = db.parse(file.toFile());
            } catch (Exception e) {
                continue; // a half-written report from a crashed fork; the log has the rest
            }
            Element suite = doc.getDocumentElement();
            r.run += intAttr(suite, "tests");
            r.failed += intAttr(suite, "failures");
            r.errors += intAttr(suite, "errors");
            r.skipped += intAttr(suite, "skipped");
            NodeList cases = suite.getElementsByTagName("testcase");
            for (int i = 0; i < cases.getLength(); i++) {
                Element tc = (Element) cases.item(i);
                Element problem = firstChild(tc, "failure");
                String kind = "failure";
                if (problem == null) {
                    problem = firstChild(tc, "error");
                    kind = "error";
                }
                if (problem == null) {
                    continue;
                }
                if (r.failures.size() >= maxFailures) {
                    r.omitted++;
                    continue;
                }
                r.failures.add(entry(tc, problem, kind, ownClassDirs, sourceRoots, paths, maxFrames, maxMessage));
            }
        }
        return r;
    }

    private static List<Path> ownClassDirs(MavenProject p) {
        List<Path> dirs = new ArrayList<>();
        dirs.add(Paths.get(p.getBuild().getOutputDirectory()));
        dirs.add(Paths.get(p.getBuild().getTestOutputDirectory()));
        return dirs;
    }

    private static List<Path> sourceRoots(MavenProject p) {
        List<Path> roots = new ArrayList<>();
        for (String s : p.getTestCompileSourceRoots()) {
            roots.add(Paths.get(s));
        }
        for (String s : p.getCompileSourceRoots()) {
            roots.add(Paths.get(s));
        }
        Path base = p.getBasedir().toPath();
        roots.add(base.resolve("src/test/java"));
        roots.add(base.resolve("src/main/java"));
        return roots;
    }

    private static Map<String, Object> entry(
            Element tc,
            Element problem,
            String kind,
            List<Path> ownClassDirs,
            List<Path> sourceRoots,
            ReactorPaths paths,
            int maxFrames,
            int maxMessage) {
        String className = tc.getAttribute("classname");
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("test", className + "#" + tc.getAttribute("name"));
        m.put("kind", kind);
        String type = problem.getAttribute("type");
        if (!type.isEmpty()) {
            m.put("type", type);
        }
        String message = problem.getAttribute("message");
        String trace = problem.getTextContent();
        if (message.isEmpty() && trace != null) {
            message = Text.firstLine(trace);
        }
        m.put("message", Text.clip(message, maxMessage));

        List<String> frames = new ArrayList<>();
        String at = null;
        if (trace != null) {
            for (String raw : trace.split("\\R")) {
                String line = raw.trim();
                if (!line.startsWith("at ")) {
                    continue;
                }
                String frame = line.substring(3);
                String cls = frameClass(frame);
                if (cls == null || isNoise(cls) || !isOwn(cls, ownClassDirs)) {
                    continue;
                }
                if (at == null && (cls.equals(className) || cls.startsWith(className + "$"))) {
                    at = toSourcePath(cls, frame, sourceRoots, paths);
                }
                if (frames.size() < maxFrames) {
                    frames.add(stripModule(frame));
                }
            }
        }
        if (at != null) {
            m.put("at", at);
        }
        if (!frames.isEmpty()) {
            m.put("stack", frames);
        }
        return m;
    }

    /** "com.acme.Foo.bar(Foo.java:12)" -> "com.acme.Foo" */
    private static String frameClass(String frame) {
        int paren = frame.indexOf('(');
        String head = paren > 0 ? frame.substring(0, paren) : frame;
        head = stripModule(head);
        int dot = head.lastIndexOf('.');
        return dot > 0 ? head.substring(0, dot) : null;
    }

    /** Drops JPMS prefixes such as "java.base/" or "app//". */
    private static String stripModule(String frame) {
        int slash = frame.lastIndexOf('/', frame.indexOf('(') > 0 ? frame.indexOf('(') : frame.length());
        return slash >= 0 ? frame.substring(slash + 1) : frame;
    }

    private static boolean isNoise(String cls) {
        for (String p : NOISE) {
            if (cls.startsWith(p)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isOwn(String cls, List<Path> classDirs) {
        String rel = cls.replace('.', '/') + ".class";
        for (Path d : classDirs) {
            if (Files.isRegularFile(d.resolve(rel))) {
                return true;
            }
        }
        return false;
    }

    private static String toSourcePath(String cls, String frame, List<Path> roots, ReactorPaths paths) {
        int open = frame.lastIndexOf('(');
        int close = frame.lastIndexOf(')');
        if (open < 0 || close < open) {
            return null;
        }
        String loc = frame.substring(open + 1, close); // Foo.java:12
        int colon = loc.lastIndexOf(':');
        String fileName = colon > 0 ? loc.substring(0, colon) : loc;
        String line = colon > 0 ? loc.substring(colon + 1) : null;
        String outer = cls.contains("$") ? cls.substring(0, cls.indexOf('$')) : cls;
        int dot = outer.lastIndexOf('.');
        String pkgPath = dot > 0 ? outer.substring(0, dot).replace('.', '/') + "/" : "";
        for (Path root : roots) {
            Path f = root.resolve(pkgPath + fileName);
            if (Files.isRegularFile(f)) {
                return paths.rel(f) + (line != null ? ":" + line : "");
            }
        }
        return loc;
    }

    private static Element firstChild(Element parent, String name) {
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element && name.equals(n.getNodeName())) {
                return (Element) n;
            }
        }
        return null;
    }

    private static int intAttr(Element e, String name) {
        try {
            return Integer.parseInt(e.getAttribute(name).trim());
        } catch (NumberFormatException ex) {
            return 0;
        }
    }
}
