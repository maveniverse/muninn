/*
 * Copyright (c) 2026 Maveniverse Org.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-v20.html
 */
package eu.maveniverse.maven.muninn.extension3;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.maven.lifecycle.LifecycleExecutionException;
import org.apache.maven.model.building.ModelProblem;
import org.apache.maven.plugin.AbstractMojoExecutionException;
import org.apache.maven.plugin.MojoExecution;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.ProjectBuildingException;
import org.apache.maven.project.ProjectBuildingResult;
import org.eclipse.aether.collection.DependencyCollectionException;
import org.eclipse.aether.graph.DependencyNode;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.resolution.ArtifactResolutionException;
import org.eclipse.aether.resolution.ArtifactResult;
import org.eclipse.aether.resolution.DependencyResolutionException;
import org.eclipse.aether.transfer.ArtifactNotFoundException;
import org.eclipse.aether.transfer.ArtifactTransferException;

/**
 * Turns a build failure into the smallest structured description that lets an agent act.
 * Each analyzer recognizes one failure shape; anything unrecognized falls back to the
 * root cause message, which is still far shorter than Maven's console epilogue.
 */
final class FailureAnalyzer {

    /** maven-compiler-plugin / plexus-compiler message format: {@code /abs/Foo.java:[12,9] message} */
    private static final Pattern COMPILER_LINE =
            Pattern.compile("^(?:\\[(?:ERROR|WARNING)\\] )?(.+?\\.(?:java|kt|groovy|scala)):\\[(\\d+),(\\d+)\\] (.*)$");

    /** javadoc tool output, as relayed by maven-javadoc-plugin: {@code Exit code: 1 - /abs/Foo.java:12: error: msg} */
    private static final Pattern JAVADOC_LINE =
            Pattern.compile("^(?:.*?Exit code: \\d+ - )?(.+?\\.java):(\\d+): (error|warning): (.*)$");

    private static final Pattern REPORTS_DIR = Pattern.compile("See (.+?) for the individual test results");

    private final ReactorPaths paths;
    private final Limits limits;
    private final boolean offline;

    FailureAnalyzer(ReactorPaths paths, Limits limits, boolean offline) {
        this.paths = paths;
        this.limits = limits;
        this.offline = offline;
    }

    /** Failure of a module during the lifecycle. */
    Map<String, Object> analyzeModule(Throwable error, MavenProject project, MojoExecution mojo) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (mojo != null) {
            m.put("phase", mojo.getLifecyclePhase());
            m.put("mojo", mojoId(mojo));
        }
        Throwable cause = unwrap(error);

        List<Map<String, Object>> missing = resolutionFailures(cause);
        if (!missing.isEmpty()) {
            m.put("kind", "dependency");
            m.put("summary", "could not resolve " + missing.size() + " artifact(s)");
            m.put("missing", missing);
            if (anyReason(missing, "cached_failure")) {
                m.put("hint", "failure is cached in the local repository; re-run with -U after fixing the repository");
            }
            return m;
        }

        String longMessage = longMessage(cause);
        if (isJavadocFailure(cause, mojo)) {
            javadoc(m, longMessage, cause);
            return m;
        }
        List<Map<String, Object>> diagnostics = compilerDiagnostics(longMessage);
        if (!diagnostics.isEmpty() || isCompilationFailure(cause)) {
            m.put("kind", "compile");
            int total = diagnostics.size();
            m.put("summary", total + " compilation error(s)");
            m.put("errors", cap(diagnostics, limits.diagnostics));
            if (total > limits.diagnostics) {
                m.put("omitted", total - limits.diagnostics);
            }
            return m;
        }

        if (isTestFailure(cause, mojo)) {
            Path reports = reportsDir(cause.getMessage(), project, mojo);
            SurefireReports.Result r = SurefireReports.read(
                    reports,
                    ownClassDirs(project),
                    sourceRoots(project),
                    paths,
                    limits.tests,
                    limits.frames,
                    limits.message);
            m.put("kind", "test");
            Map<String, Object> counts = new LinkedHashMap<>();
            counts.put("run", r.run);
            counts.put("failed", r.failed);
            counts.put("errors", r.errors);
            counts.put("skipped", r.skipped);
            m.put("tests", counts);
            if (r.failures.isEmpty()) {
                // Fork crash, timeout or no reports: the summary line is all we can say for sure.
                m.put("summary", Text.clip(Text.firstLine(cause.getMessage()), limits.message));
            } else {
                m.put("summary", (r.failed + r.errors) + " of " + r.run + " test(s) failed");
                m.put("failures", r.failures);
                if (r.omitted > 0) {
                    m.put("omitted", r.omitted);
                }
            }
            m.put("reports", paths.rel(reports));
            return m;
        }

        m.put("kind", mojo != null ? "mojo" : "build");
        m.put("summary", Text.clip(paths.relString(rootMessage(cause)), limits.message));
        Throwable root = root(cause);
        if (root != cause || !(cause instanceof AbstractMojoExecutionException)) {
            m.put("exception", root.getClass().getName());
        }
        return m;
    }

    /** Failures not attached to a module: invalid POMs, missing extensions, bad CLI arguments. */
    Map<String, Object> analyzeSession(Throwable error) {
        Map<String, Object> m = new LinkedHashMap<>();
        Throwable cause = unwrap(error);
        ProjectBuildingException pbe = find(cause, ProjectBuildingException.class);
        if (pbe != null && pbe.getResults() != null) {
            List<Map<String, Object>> problems = new ArrayList<>();
            for (ProjectBuildingResult result : pbe.getResults()) {
                for (ModelProblem p : result.getProblems()) {
                    if (p.getSeverity() == ModelProblem.Severity.WARNING) {
                        continue;
                    }
                    Map<String, Object> e = new LinkedHashMap<>();
                    String src = p.getSource();
                    if (src != null && !src.isEmpty()) {
                        e.put("file", paths.relString(src) + (p.getLineNumber() > 0 ? ":" + p.getLineNumber() : ""));
                    }
                    e.put("message", Text.clip(paths.relString(p.getMessage()), limits.message));
                    problems.add(e);
                }
            }
            m.put("kind", "pom");
            m.put("summary", problems.size() + " problem(s) building the project model");
            m.put("problems", cap(problems, limits.diagnostics));
            return m;
        }
        List<Map<String, Object>> missing = resolutionFailures(cause);
        if (!missing.isEmpty()) {
            m.put("kind", "dependency");
            m.put("summary", "could not resolve " + missing.size() + " artifact(s)");
            m.put("missing", missing);
            return m;
        }
        m.put("kind", "build");
        m.put("summary", Text.clip(paths.relString(rootMessage(cause)), limits.message));
        m.put("exception", root(cause).getClass().getName());
        return m;
    }

    // ---- dependency resolution -------------------------------------------------------------

    private List<Map<String, Object>> resolutionFailures(Throwable t) {
        // artifact coordinates -> exceptions from each repository that was tried
        Map<String, List<Exception>> unresolved = new LinkedHashMap<>();
        Map<String, List<String>> via = new HashMap<>();
        collectUnresolved(t, unresolved, via, Collections.newSetFromMap(new IdentityHashMap<>()));
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map.Entry<String, List<Exception>> u : unresolved.entrySet()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("artifact", u.getKey());
            if (via.containsKey(u.getKey())) {
                entry.put("via", via.get(u.getKey())); // direct dependency first, parent of the missing one last
            }
            List<Map<String, Object>> tries = new ArrayList<>();
            String reason = null;
            for (Exception e : u.getValue()) {
                String r = reason(e);
                reason = mostSpecific(reason, r);
                Map<String, Object> attempt = new LinkedHashMap<>();
                if (e instanceof ArtifactTransferException) {
                    RemoteRepository repo = ((ArtifactTransferException) e).getRepository();
                    if (repo != null) {
                        attempt.put("repo", repo.getId());
                        attempt.put("url", repo.getUrl());
                    }
                }
                attempt.put("reason", r);
                if ("transfer".equals(r) || "unauthorized".equals(r) || "forbidden".equals(r)) {
                    attempt.put("detail", Text.clip(rootMessage(e), limits.message));
                }
                if (attempt.containsKey("repo") && !tries.contains(attempt)) {
                    tries.add(attempt); // attempts without a repository add nothing beyond "reason"
                }
            }
            entry.put("reason", reason != null ? reason : "unresolved");
            if (!tries.isEmpty()) {
                entry.put("tried", tries);
            }
            out.add(entry);
        }
        return out;
    }

    private static void collectUnresolved(
            Throwable t, Map<String, List<Exception>> out, Map<String, List<String>> via, Set<Throwable> seen) {
        for (Throwable c = t; c != null && seen.add(c); c = c.getCause()) {
            if (c instanceof org.apache.maven.project.DependencyResolutionException) {
                // Maven's own exception carries the per-dependency errors in its result.
                org.apache.maven.project.DependencyResolutionResult res =
                        ((org.apache.maven.project.DependencyResolutionException) c).getResult();
                if (res != null) {
                    for (org.eclipse.aether.graph.Dependency d : res.getUnresolvedDependencies()) {
                        List<Exception> errors = res.getResolutionErrors(d);
                        if (errors != null && !errors.isEmpty()) {
                            add(out, d.getArtifact().toString(), errors);
                            addVia(
                                    via,
                                    res.getDependencyGraph(),
                                    d.getArtifact().toString());
                        }
                    }
                    for (Exception e : res.getCollectionErrors()) {
                        collectUnresolved(e, out, via, seen);
                    }
                }
            } else if (c instanceof ArtifactResolutionException) {
                for (ArtifactResult r : ((ArtifactResolutionException) c).getResults()) {
                    if (!r.isResolved() && !r.getExceptions().isEmpty()) {
                        add(out, r.getRequest().getArtifact().toString(), r.getExceptions());
                    }
                }
            } else if (c instanceof DependencyResolutionException) {
                DependencyResolutionException dre = (DependencyResolutionException) c;
                if (dre.getResult() != null) {
                    for (Exception e : dre.getResult().getCollectExceptions()) {
                        collectUnresolved(e, out, via, seen);
                    }
                    for (ArtifactResult r : dre.getResult().getArtifactResults()) {
                        if (!r.isResolved() && !r.getExceptions().isEmpty()) {
                            add(out, r.getRequest().getArtifact().toString(), r.getExceptions());
                            addVia(
                                    via,
                                    dre.getResult().getRoot(),
                                    r.getRequest().getArtifact().toString());
                        }
                    }
                }
            } else if (c instanceof DependencyCollectionException) {
                DependencyCollectionException dce = (DependencyCollectionException) c;
                if (dce.getResult() != null) {
                    for (Exception e : dce.getResult().getExceptions()) {
                        collectUnresolved(e, out, via, seen);
                    }
                }
            } else if (c instanceof ArtifactTransferException
                    && ((ArtifactTransferException) c).getArtifact() != null) {
                add(out, ((ArtifactTransferException) c).getArtifact().toString(), Collections.singletonList((Exception)
                        c));
            }
        }
    }

    /** Records the chain of dependencies leading to {@code coords}; nothing for direct dependencies. */
    private static void addVia(Map<String, List<String>> via, DependencyNode root, String coords) {
        List<String> path = pathTo(root, coords, new ArrayList<>());
        if (path != null && !path.isEmpty()) {
            via.put(coords, path);
        }
    }

    private static List<String> pathTo(DependencyNode node, String coords, List<String> parents) {
        if (node == null) {
            return null;
        }
        for (DependencyNode child : node.getChildren()) {
            if (child.getArtifact() != null && coords.equals(child.getArtifact().toString())) {
                return parents;
            }
            parents.add(String.valueOf(child.getArtifact()));
            List<String> found = pathTo(child, coords, parents);
            if (found != null) {
                return found;
            }
            parents.remove(parents.size() - 1);
        }
        return null;
    }

    private static void add(Map<String, List<Exception>> out, String coords, List<Exception> errors) {
        List<Exception> list = out.computeIfAbsent(coords, k -> new ArrayList<>());
        for (Exception e : errors) {
            if (!list.contains(e)) {
                list.add(e);
            }
        }
    }

    private String reason(Exception e) {
        String msg = String.valueOf(rootMessage(e)) + " " + e.getMessage();
        if (msg.contains("was cached in the local repository")) {
            return "cached_failure";
        }
        if (offline && (msg.contains("offline") || msg.contains("Offline"))) {
            return "offline_not_cached";
        }
        if (e instanceof ArtifactNotFoundException) {
            return "not_found";
        }
        if (msg.contains(" 401") || msg.contains("Unauthorized")) {
            return "unauthorized";
        }
        if (msg.contains(" 403") || msg.contains("Forbidden")) {
            return "forbidden";
        }
        if (msg.toLowerCase().contains("checksum")) {
            return "checksum";
        }
        return "transfer";
    }

    /** Prefers the reason an agent can act on over the generic "not found". */
    private static String mostSpecific(String current, String next) {
        List<String> order = java.util.Arrays.asList(
                "unresolved",
                "not_found",
                "transfer",
                "checksum",
                "forbidden",
                "unauthorized",
                "offline_not_cached",
                "cached_failure");
        if (current == null) {
            return next;
        }
        return order.indexOf(next) > order.indexOf(current) ? next : current;
    }

    private static boolean anyReason(List<Map<String, Object>> missing, String reason) {
        for (Map<String, Object> m : missing) {
            if (reason.equals(m.get("reason"))) {
                return true;
            }
        }
        return false;
    }

    // ---- compiler ---------------------------------------------------------------------------

    private List<Map<String, Object>> compilerDiagnostics(String longMessage) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (longMessage == null) {
            return out;
        }
        Map<String, Object> current = null;
        List<String> detail = null;
        for (String line : longMessage.split("\\R")) {
            Matcher m = COMPILER_LINE.matcher(line.trim());
            if (m.matches()) {
                flushDetail(current, detail);
                current = new LinkedHashMap<>();
                current.put("file", paths.relString(m.group(1)));
                current.put("line", Integer.parseInt(m.group(2)));
                current.put("col", Integer.parseInt(m.group(3)));
                current.put("message", Text.clip(m.group(4), limits.message));
                detail = new ArrayList<>();
                if (!out.contains(current)) {
                    out.add(current);
                }
            } else if (current != null && !line.trim().isEmpty() && line.startsWith(" ")) {
                detail.add(line.trim().replaceAll("\\s+", " "));
            }
        }
        flushDetail(current, detail);
        // javac reports the same error once per round in some setups; drop exact repeats.
        List<Map<String, Object>> unique = new ArrayList<>();
        for (Map<String, Object> d : out) {
            if (!unique.contains(d)) {
                unique.add(d);
            }
        }
        return unique;
    }

    private void flushDetail(Map<String, Object> current, List<String> detail) {
        if (current != null && detail != null && !detail.isEmpty()) {
            current.put("detail", Text.clip(String.join("; ", detail), limits.message));
        }
    }

    private static boolean isCompilationFailure(Throwable t) {
        return t.getClass().getSimpleName().equals("CompilationFailureException")
                || (t.getMessage() != null && t.getMessage().startsWith("Compilation failure"));
    }

    // ---- javadoc ----------------------------------------------------------------------------

    private static boolean isJavadocFailure(Throwable t, MojoExecution mojo) {
        return (mojo != null
                        && mojo.getArtifactId() != null
                        && mojo.getArtifactId().contains("javadoc"))
                || (t.getMessage() != null && t.getMessage().contains("Error while generating Javadoc"));
    }

    /**
     * Errors and warnings come from the tool output embedded in the plugin's exception, so
     * warnings are only visible when the build failed (errors, or {@code failOnWarnings}).
     */
    private void javadoc(Map<String, Object> m, String longMessage, Throwable cause) {
        List<Map<String, Object>> errors = new ArrayList<>();
        List<Map<String, Object>> warnings = new ArrayList<>();
        Map<String, Object> current = null;
        for (String line : String.valueOf(longMessage).split("\\R")) {
            Matcher d = JAVADOC_LINE.matcher(line.trim());
            if (d.matches()) {
                current = new LinkedHashMap<>();
                current.put("file", paths.relString(d.group(1)));
                current.put("line", Integer.parseInt(d.group(2)));
                current.put("message", Text.clip(d.group(4), limits.message));
                List<Map<String, Object>> target = "error".equals(d.group(3)) ? errors : warnings;
                if (!target.contains(current)) {
                    target.add(current);
                }
            } else if (current != null && line.trim().equals("^")) {
                current.put("col", line.indexOf('^') + 1); // caret under the source line javadoc echoes
                current = null;
            }
        }
        m.put("kind", "javadoc");
        if (errors.isEmpty() && warnings.isEmpty()) {
            m.put("summary", Text.clip(paths.relString(rootMessage(cause)), limits.message));
            return;
        }
        m.put("summary", errors.size() + " javadoc error(s), " + warnings.size() + " warning(s)");
        if (!errors.isEmpty()) {
            m.put("errors", cap(errors, limits.diagnostics));
        }
        if (!warnings.isEmpty()) {
            m.put("warnings", cap(warnings, limits.diagnostics));
        }
        int omitted =
                Math.max(0, errors.size() - limits.diagnostics) + Math.max(0, warnings.size() - limits.diagnostics);
        if (omitted > 0) {
            m.put("omitted", omitted);
        }
    }

    // ---- tests ------------------------------------------------------------------------------

    private static boolean isTestFailure(Throwable t, MojoExecution mojo) {
        String msg = t.getMessage() == null ? "" : t.getMessage();
        if (msg.startsWith("There are test failures")
                || msg.startsWith("There was a timeout")
                || msg.contains("The forked VM terminated")) {
            return true;
        }
        if (mojo == null || mojo.getArtifactId() == null) {
            return false;
        }
        String a = mojo.getArtifactId();
        String goal = mojo.getGoal();
        boolean surefire = a.contains("surefire") && "test".equals(goal);
        boolean failsafe = a.contains("failsafe") && ("integration-test".equals(goal) || "verify".equals(goal));
        return surefire || failsafe;
    }

    private static Path reportsDir(String message, MavenProject project, MojoExecution mojo) {
        if (message != null) {
            Matcher m = REPORTS_DIR.matcher(message);
            if (m.find()) {
                return Paths.get(m.group(1));
            }
        }
        String name =
                mojo != null && mojo.getArtifactId().contains("failsafe") ? "failsafe-reports" : "surefire-reports";
        return Paths.get(project.getBuild().getDirectory(), name);
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

    // ---- exception helpers --------------------------------------------------------------------

    static String mojoId(MojoExecution mojo) {
        String prefix =
                mojo.getMojoDescriptor() != null && mojo.getMojoDescriptor().getPluginDescriptor() != null
                        ? mojo.getMojoDescriptor().getPluginDescriptor().getGoalPrefix()
                        : mojo.getArtifactId();
        return prefix + ":" + mojo.getGoal() + "@" + mojo.getExecutionId();
    }

    private static Throwable unwrap(Throwable t) {
        Throwable c = t;
        while (c instanceof LifecycleExecutionException && c.getCause() != null) {
            c = c.getCause();
        }
        return c;
    }

    private static String longMessage(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (c instanceof AbstractMojoExecutionException) {
                String lm = ((AbstractMojoExecutionException) c).getLongMessage();
                if (lm != null && !lm.isEmpty()) {
                    return lm;
                }
            }
        }
        return t.getMessage();
    }

    private static <T> T find(Throwable t, Class<T> type) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (type.isInstance(c)) {
                return type.cast(c);
            }
        }
        return null;
    }

    private static Throwable root(Throwable t) {
        Throwable c = t;
        while (c.getCause() != null && c.getCause() != c) {
            c = c.getCause();
        }
        return c;
    }

    /** Deepest non-empty message: Maven wrappers repeat context, the root says what broke. */
    private static String rootMessage(Throwable t) {
        String best = null;
        for (Throwable c = t; c != null; c = c.getCause() == c ? null : c.getCause()) {
            if (c.getMessage() != null && !c.getMessage().trim().isEmpty()) {
                best = c.getMessage();
            }
        }
        return Text.firstLine(best != null ? best : t.getClass().getName());
    }

    private static <T> List<T> cap(List<T> list, int max) {
        return list.size() <= max ? list : new ArrayList<>(list.subList(0, max));
    }

    /** Output budgets. */
    static final class Limits {
        int diagnostics = 10;
        int tests = 10;
        int frames = 5;
        int message = 300;
    }
}
