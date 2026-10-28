/*
 * Copyright (c) 2026 Maveniverse Org.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-v20.html
 */
package eu.maveniverse.maven.muninn.extension3;

import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import javax.inject.Named;
import javax.inject.Singleton;
import org.apache.maven.eventspy.AbstractEventSpy;
import org.apache.maven.execution.ExecutionEvent;
import org.apache.maven.execution.MavenExecutionRequest;
import org.apache.maven.execution.MavenExecutionResult;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.execution.ProjectDependencyGraph;
import org.apache.maven.lifecycle.LifecycleExecutionException;
import org.apache.maven.plugin.MojoExecution;
import org.apache.maven.plugin.PluginParameterExpressionEvaluator;
import org.apache.maven.project.MavenProject;
import org.apache.maven.settings.Settings;
import org.apache.maven.settings.building.SettingsBuildingResult;
import org.codehaus.plexus.util.xml.Xpp3Dom;

/**
 * Core extension that replaces Maven's console output with a compact JSON report meant for
 * coding agents. Activate with {@code -Dmuninn.output=json} (one summary object) or
 * {@code -Dmuninn.output=ndjson} (one line per finished module, then the summary), or set
 * {@code MUNINN_OUTPUT}. Without either, it switches to {@code json} when an AI agent is
 * detected ({@code CLAUDECODE=1}, {@code GEMINI_CLI=1} or {@code AGENT} set); otherwise it does nothing.
 * An explicit value, including {@code off}, always wins over detection.
 *
 * <p>The full console log is kept in {@code muninn-output/build.log} and the report in
 * {@code muninn-output/report.json}, both under the top-level project's build directory
 * ({@code target} by default).
 */
@Named
@Singleton
public class AgentOutputSpy extends AbstractEventSpy {

    static final String PROP = "muninn.output";
    static final String ENV = "MUNINN_OUTPUT";

    private enum Mode {
        OFF,
        JSON,
        NDJSON
    }

    private enum Status {
        OK,
        FAILED,
        SKIPPED,
        NOT_BUILT
    }

    private static final class Module {
        final MavenProject project;
        final String id;
        Status status = Status.NOT_BUILT;
        long start;
        long end;
        boolean started;
        long startMillis; // wall clock, to tell this build's test reports from stale ones
        long mojoStartMillis;
        MojoExecution lastMojo;
        MojoExecution failedMojo;
        Throwable failure;
        Map<String, Object> failureInfo;
        int[] tests; // run, failed, errors, skipped; null if no test mojo ran

        Module(MavenProject project, String id) {
            this.project = project;
            this.id = id;
        }
    }

    private Mode mode = Mode.OFF;
    private boolean verbose;
    private ConsoleCapture capture;
    private PrintStream out = System.out;
    private Path workingDirectory = Paths.get(System.getProperty("user.dir")).toAbsolutePath();
    private List<String> settingsProfiles = Collections.emptyList();
    private MavenExecutionRequest request;
    private MavenSession session;
    private MavenExecutionResult result;
    private long startNanos = System.nanoTime();
    private final Map<MavenProject, Module> modules = new LinkedHashMap<>();
    private final List<Map<String, Object>> sessionErrors = new ArrayList<>();
    private ReactorPaths paths = new ReactorPaths(Paths.get("."));
    private FailureAnalyzer analyzer;
    private final FailureAnalyzer.Limits limits = new FailureAnalyzer.Limits();

    @Override
    public synchronized void init(Context context) throws Exception {
        if (System.out == System.err) {
            // -l <file>: Maven points both at the same file stream, and the user chose where logs go.
            // An already active copy of this extension (user-wide plus project) does the same.
            return;
        }
        Map<String, Object> data = context.getData();
        Properties user = (Properties) data.get("userProperties");
        Properties sys = (Properties) data.get("systemProperties");
        String value = user != null ? user.getProperty(PROP) : null;
        if (value == null && sys != null) {
            value = sys.getProperty(PROP);
        }
        if (value == null) {
            value = System.getenv(ENV);
        }
        if (value == null && runByAgent()) {
            value = "json"; // explicit -Dmuninn.output / MUNINN_OUTPUT (even "off") wins over detection
        }
        mode = parseMode(value);
        if (mode == Mode.OFF) {
            return;
        }
        if (data.get("workingDirectory") != null) {
            workingDirectory = Paths.get(data.get("workingDirectory").toString())
                    .toAbsolutePath()
                    .normalize();
        }
        verbose = user != null && Boolean.parseBoolean(user.getProperty(PROP + ".verbose"));
        limits.diagnostics = intProp(user, PROP + ".maxErrors", limits.diagnostics);
        limits.tests = intProp(user, PROP + ".maxTests", limits.tests);
        limits.frames = intProp(user, PROP + ".maxFrames", limits.frames);
        startNanos = System.nanoTime();
        try {
            capture = ConsoleCapture.start();
            out = capture.originalOut();
        } catch (IOException e) {
            // Without capture the report still prints; it just arrives after the normal log.
            capture = null;
        }
    }

    @Override
    public synchronized void onEvent(Object event) {
        if (mode == Mode.OFF) {
            return;
        }
        if (event instanceof SettingsBuildingResult) {
            Settings s = ((SettingsBuildingResult) event).getEffectiveSettings();
            if (s != null) {
                settingsProfiles = s.getActiveProfiles(); // also in the request, but not typed by the user
            }
        } else if (event instanceof MavenExecutionRequest) {
            request = (MavenExecutionRequest) event;
            // prompts would go to the captured log and the build would wait on input nobody sees
            request.setInteractiveMode(false);
            Path root = request.getMultiModuleProjectDirectory() != null
                    ? request.getMultiModuleProjectDirectory().toPath()
                    : Paths.get(request.getBaseDirectory() != null ? request.getBaseDirectory() : ".");
            paths = new ReactorPaths(root);
            analyzer = new FailureAnalyzer(paths, limits, request.isOffline());
        } else if (event instanceof ExecutionEvent) {
            onExecutionEvent((ExecutionEvent) event);
        } else if (event instanceof MavenExecutionResult) {
            result = (MavenExecutionResult) event;
        }
    }

    private void onExecutionEvent(ExecutionEvent e) {
        switch (e.getType()) {
            case SessionStarted:
                session = e.getSession();
                registerModules(session.getProjects());
                break;
            case ProjectStarted: {
                Module m = module(e);
                m.start = System.nanoTime();
                m.startMillis = System.currentTimeMillis();
                m.started = true;
                break;
            }
            case MojoStarted: {
                Module m = module(e);
                m.lastMojo = e.getMojoExecution();
                m.mojoStartMillis = System.currentTimeMillis();
                break;
            }
            case MojoFailed: {
                Module m = module(e);
                m.failedMojo = e.getMojoExecution();
                m.failure = e.getException();
                countTests(m, e.getMojoExecution());
                break;
            }
            case MojoSucceeded:
                countTests(module(e), e.getMojoExecution());
                break;
            case ProjectSucceeded:
                finish(module(e), Status.OK, null);
                break;
            case ProjectFailed:
                finish(module(e), Status.FAILED, e.getException());
                break;
            case ProjectSkipped:
                finish(module(e), Status.SKIPPED, null);
                break;
            default:
            // forks and per-mojo success events add nothing an agent needs
        }
    }

    /** Adds the totals of the surefire/failsafe reports this mojo execution wrote. */
    private void countTests(Module m, MojoExecution mojo) {
        String a = mojo.getArtifactId();
        boolean fs = a.equals("maven-failsafe-plugin") && mojo.getGoal().equals("integration-test");
        if (!fs && !(a.equals("maven-surefire-plugin") && mojo.getGoal().equals("test"))) {
            return;
        }
        Path dir = reportsDir(m, mojo, fs ? "failsafe-reports" : "surefire-reports");
        SurefireReports.Result r = SurefireReports.read(
                dir, m.mojoStartMillis, Collections.emptyList(), Collections.emptyList(), paths, 0, 0, 0);
        if (r.run == 0) {
            return; // skipped (-DskipTests) or nothing matched: older reports in the directory are not ours
        }
        if (m.tests == null) {
            m.tests = new int[4];
        }
        m.tests[0] += r.run;
        m.tests[1] += r.failed;
        m.tests[2] += r.errors;
        m.tests[3] += r.skipped;
    }

    /** The mojo's {@code reportsDirectory} if configured (expressions evaluated), else the plugin default. */
    private Path reportsDir(Module m, MojoExecution mojo, String defaultName) {
        Xpp3Dom cfg = mojo.getConfiguration();
        Xpp3Dom child = cfg != null ? cfg.getChild("reportsDirectory") : null;
        if (child != null && child.getValue() != null && session != null) {
            try {
                Object v = new PluginParameterExpressionEvaluator(session, mojo).evaluate(child.getValue());
                if (v != null) {
                    return m.project.getBasedir().toPath().resolve(v.toString());
                }
            } catch (Exception ignored) {
                // fall back to the default directory
            }
        }
        return Paths.get(m.project.getBuild().getDirectory(), defaultName);
    }

    private static Map<String, Object> testCounts(int[] t) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("run", t[0]);
        c.put("failed", t[1]);
        c.put("errors", t[2]);
        c.put("skipped", t[3]);
        return c;
    }

    private void registerModules(List<MavenProject> projects) {
        Map<String, Integer> counts = new HashMap<>();
        for (MavenProject p : projects) {
            counts.merge(p.getArtifactId(), 1, Integer::sum);
        }
        for (MavenProject p : projects) {
            String id =
                    counts.get(p.getArtifactId()) > 1 ? p.getGroupId() + ":" + p.getArtifactId() : p.getArtifactId();
            modules.put(p, new Module(p, id));
        }
    }

    private Module module(ExecutionEvent e) {
        MavenProject p = e.getProject();
        Module m = modules.get(p);
        if (m == null) {
            m = new Module(p, p.getArtifactId());
            modules.put(p, m);
        }
        return m;
    }

    private void finish(Module m, Status status, Throwable error) {
        m.status = status;
        m.end = System.nanoTime();
        if (analyzer == null) {
            analyzer = new FailureAnalyzer(paths, limits, false);
        }
        if (status == Status.FAILED) {
            if (m.failure == null) {
                m.failure = error;
            }
            m.failureInfo = analyzer.analyzeModule(
                    m.failure != null ? m.failure : error, m.project, m.failedMojo, m.startMillis);
        }
        if (mode == Mode.NDJSON) {
            Map<String, Object> line = new LinkedHashMap<>();
            line.put("event", "module");
            line.put("id", m.id);
            line.put("status", status.name());
            if (m.started && seconds(m.end - m.start) >= 0.1) {
                line.put("time_s", seconds(m.end - m.start));
            }
            if (m.failureInfo != null) {
                line.put("kind", m.failureInfo.get("kind"));
                line.put("summary", m.failureInfo.get("summary"));
            } else if (status == Status.SKIPPED) {
                line.put("cause", skipCause(m));
            }
            out.println(Json.write(line));
            out.flush();
        }
    }

    @Override
    public synchronized void close() {
        if (mode == Mode.OFF) {
            return;
        }
        Path dir = buildDirectory().resolve("muninn-output");
        Path log = null;
        if (capture != null) {
            log = capture.moveTo(dir.resolve("build.log"));
        }
        String json;
        try {
            json = Json.write(report(log));
        } catch (RuntimeException e) {
            // the console is silenced, so a report is the only output the caller gets
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("schema", 1);
            r.put("result", result != null && !result.hasExceptions() ? "SUCCESS" : "FAILURE");
            r.put("error", "muninn could not build the report: " + e);
            if (log != null) {
                r.put("log", Collections.singletonMap("path", paths.rel(log)));
            }
            json = Json.write(r);
        }
        try {
            Files.createDirectories(dir);
            Files.write(dir.resolve("report.json"), (json + "\n").getBytes(StandardCharsets.UTF_8));
        } catch (IOException ignored) {
            // stdout is the primary channel; the file is a convenience
        }
        out.println(json);
        out.flush();
    }

    /** The top-level project's build directory (customizable); {@code target} if no project was loaded. */
    private Path buildDirectory() {
        MavenProject top = session != null ? session.getTopLevelProject() : null;
        String dir = top != null && top.getBuild() != null ? top.getBuild().getDirectory() : null;
        return paths.root().resolve(dir != null ? dir : "target");
    }

    private Map<String, Object> report(Path log) {
        attachResultExceptions();
        Map<String, Object> r = new LinkedHashMap<>();
        if (mode == Mode.NDJSON) {
            r.put("event", "summary");
        }
        r.put("schema", 1);
        boolean failed = (result != null && result.hasExceptions())
                || modules.values().stream().anyMatch(m -> m.status == Status.FAILED);
        r.put("result", failed ? "FAILURE" : "SUCCESS");
        if (request != null) {
            r.put("goals", String.join(" ", request.getGoals()));
        }
        r.put("time_s", seconds(System.nanoTime() - startNanos));

        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Status s : Status.values()) {
            counts.put(s.name().toLowerCase(Locale.ROOT), 0);
        }
        for (Module m : modules.values()) {
            counts.merge(m.status.name().toLowerCase(Locale.ROOT), 1, Integer::sum);
        }
        counts.values().removeIf(v -> v == 0);
        if (!counts.isEmpty()) {
            r.put("counts", counts);
        }

        int[] total = new int[4];
        boolean anyTests = false;
        for (Module m : modules.values()) {
            if (m.tests != null) {
                anyTests = true;
                for (int i = 0; i < 4; i++) {
                    total[i] += m.tests[i];
                }
            }
        }
        if (anyTests) {
            r.put("tests", testCounts(total));
        }

        List<Map<String, Object>> list = new ArrayList<>();
        List<String> notBuilt = new ArrayList<>();
        Module firstFailed = null;
        for (Module m : modules.values()) {
            if (m.status == Status.FAILED && firstFailed == null) {
                firstFailed = m;
            }
            if (m.status == Status.NOT_BUILT) {
                notBuilt.add(m.id); // one line for all of them; they share the same cause
                continue;
            }
            if (m.status == Status.OK && !verbose) {
                continue;
            }
            list.add(moduleEntry(m));
        }
        if (!list.isEmpty()) {
            r.put("modules", list);
        }
        if (!notBuilt.isEmpty() && (failed || verbose)) {
            Map<String, Object> nb = new LinkedHashMap<>();
            nb.put("ids", notBuilt);
            nb.put(
                    "cause",
                    firstFailed != null
                            ? "build stopped after " + firstFailed.id
                                    + " failed (fail-fast); add -fae to also build independent modules"
                            : "build failed before these modules started");
            r.put("not_built", nb);
        }
        if (!sessionErrors.isEmpty()) {
            r.put("errors", sessionErrors);
        }
        if (firstFailed != null) {
            r.put("resume", resumeCommand(firstFailed));
        }
        if (log != null) {
            Map<String, Object> l = new LinkedHashMap<>();
            l.put("path", paths.rel(log));
            l.put("warningsLogged", capture.warnings());
            l.put("errorsLogged", capture.errors());
            if (!capture.loggerRedirected()) {
                l.put("captured", false); // console log was not intercepted; it went to stdout as usual
            }
            r.put("log", l);
        }
        return r;
    }

    private Map<String, Object> moduleEntry(Module m) {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("id", m.id);
        e.put("path", paths.rel(m.project.getBasedir().toPath()));
        e.put("status", m.status.name());
        if (m.started && m.status != Status.NOT_BUILT && seconds(m.end - m.start) >= 0.1) {
            e.put("time_s", seconds(m.end - m.start));
        }
        if (m.status == Status.FAILED && m.failureInfo != null) {
            e.putAll(m.failureInfo);
        } else if (m.status == Status.SKIPPED) {
            e.put("cause", skipCause(m));
        } else if (m.tests != null) {
            e.put("tests", testCounts(m.tests));
        }
        return e;
    }

    /** Names the failed upstream module, which is what the agent has to fix first. */
    private String skipCause(Module m) {
        ProjectDependencyGraph graph = session != null ? session.getProjectDependencyGraph() : null;
        if (graph != null) {
            List<String> failedUpstream = new ArrayList<>();
            for (MavenProject up : graph.getUpstreamProjects(m.project, true)) {
                Module u = modules.get(up);
                if (u != null && u.status == Status.FAILED) {
                    failedUpstream.add(u.id);
                }
            }
            if (!failedUpstream.isEmpty()) {
                return "upstream failed: " + String.join(", ", failedUpstream);
            }
        }
        return "not built because the build stopped early";
    }

    /** Exceptions Maven reports only in the result: invalid POMs, missing extensions, etc. */
    private void attachResultExceptions() {
        if (result == null || analyzer == null && request == null) {
            return;
        }
        if (analyzer == null) {
            analyzer = new FailureAnalyzer(paths, limits, false);
        }
        for (Throwable t : result.getExceptions()) {
            if (t instanceof LifecycleExecutionException && ((LifecycleExecutionException) t).getProject() != null) {
                Module m = modules.get(((LifecycleExecutionException) t).getProject());
                if (m != null) {
                    if (m.failureInfo == null) {
                        m.status = Status.FAILED;
                        m.failure = t;
                        m.failureInfo = analyzer.analyzeModule(t, m.project, m.failedMojo, m.startMillis);
                    }
                    continue;
                }
            }
            if (alreadyReported(t)) {
                continue;
            }
            sessionErrors.add(analyzer.analyzeSession(t));
        }
    }

    private boolean alreadyReported(Throwable t) {
        for (Module m : modules.values()) {
            for (Throwable c = m.failure; c != null; c = c.getCause()) {
                if (c == t) {
                    return true;
                }
            }
            for (Throwable c = t; c != null; c = c.getCause()) {
                if (c == m.failure) {
                    return true;
                }
            }
        }
        return false;
    }

    private String resumeCommand(Module failed) {
        StringBuilder sb = new StringBuilder("mvn");
        if (request != null) {
            for (String g : request.getGoals()) {
                sb.append(' ').append(quote(g));
            }
            File pom = request.getPom();
            if (pom != null && !pom.toPath().toAbsolutePath().normalize().equals(workingDirectory.resolve("pom.xml"))) {
                sb.append(" -f ").append(quote(relToWorkingDirectory(pom.toPath())));
            }
            File userSettings = request.getUserSettingsFile();
            if (userSettings != null
                    && !userSettings
                            .toPath()
                            .toAbsolutePath()
                            .normalize()
                            .equals(Paths.get(System.getProperty("user.home"), ".m2", "settings.xml")
                                    .toAbsolutePath()
                                    .normalize())) {
                sb.append(" -s ").append(quote(relToWorkingDirectory(userSettings.toPath())));
            }
            List<String> profiles = new ArrayList<>(request.getActiveProfiles());
            profiles.removeAll(settingsProfiles);
            if (!profiles.isEmpty()) {
                sb.append(" -P").append(quote(String.join(",", profiles)));
            }
            if (!request.getSelectedProjects().isEmpty()) {
                sb.append(" -pl ").append(quote(String.join(",", request.getSelectedProjects())));
            }
            String make = request.getMakeBehavior();
            if (MavenExecutionRequest.REACTOR_MAKE_UPSTREAM.equals(make)) {
                sb.append(" -am");
            } else if (MavenExecutionRequest.REACTOR_MAKE_DOWNSTREAM.equals(make)) {
                sb.append(" -amd");
            } else if (MavenExecutionRequest.REACTOR_MAKE_BOTH.equals(make)) {
                sb.append(" -am -amd");
            }
            if (MavenExecutionRequest.REACTOR_FAIL_AT_END.equals(request.getReactorFailureBehavior())) {
                sb.append(" -fae");
            }
            if (request.isOffline()) {
                sb.append(" -o");
            }
            if (request.isUpdateSnapshots()) {
                sb.append(" -U");
            }
            if (request.getDegreeOfConcurrency() > 1) {
                sb.append(" -T ").append(request.getDegreeOfConcurrency());
            }
            String builder = request.getBuilderId();
            if (builder != null && !builder.equals("singlethreaded") && !builder.equals("multithreaded")) {
                sb.append(" -b ").append(quote(builder));
            }
            Properties up = request.getUserProperties();
            for (String k : new java.util.TreeSet<>(up.stringPropertyNames())) {
                if (!k.startsWith(PROP)) {
                    sb.append(" -D").append(quote(k + "=" + up.getProperty(k)));
                }
            }
        }
        // the module id is groupId:artifactId exactly when the artifactId alone is ambiguous
        sb.append(" -rf ").append(failed.id.contains(":") ? failed.id : ":" + failed.id);
        return sb.toString();
    }

    private String relToWorkingDirectory(Path p) {
        Path abs = p.toAbsolutePath().normalize();
        return abs.startsWith(workingDirectory)
                ? workingDirectory.relativize(abs).toString().replace(File.separatorChar, '/')
                : abs.toString();
    }

    private static String quote(String s) {
        return s.matches("[A-Za-z0-9_.,:=@/+-]+") ? s : "'" + s.replace("'", "'\\''") + "'";
    }

    private static double seconds(long nanos) {
        return Math.round(nanos / 1e8) / 10.0;
    }

    /** Env markers set by agent harnesses; a hint only, trivially spoofed or unset. */
    private static boolean runByAgent() {
        return "1".equals(System.getenv("CLAUDECODE"))
                || "1".equals(System.getenv("GEMINI_CLI"))
                || System.getenv("AGENT") != null && !System.getenv("AGENT").isEmpty();
    }

    private static Mode parseMode(String v) {
        if (v == null) {
            return Mode.OFF;
        }
        switch (v.trim().toLowerCase(Locale.ROOT)) {
            case "json":
            case "true":
            case "":
                return Mode.JSON;
            case "ndjson":
            case "stream":
                return Mode.NDJSON;
            default:
                return Mode.OFF;
        }
    }

    private static int intProp(Properties p, String key, int dflt) {
        if (p == null || p.getProperty(key) == null) {
            return dflt;
        }
        try {
            return Math.max(0, Integer.parseInt(p.getProperty(key).trim()));
        } catch (NumberFormatException e) {
            return dflt;
        }
    }
}
