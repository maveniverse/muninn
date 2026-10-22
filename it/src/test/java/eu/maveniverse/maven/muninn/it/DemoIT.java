/*
 * Copyright (c) 2026 Maveniverse Org.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-v20.html
 */
package eu.maveniverse.maven.muninn.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.apache.maven.executor.Executor;
import org.apache.maven.executor.ExecutorRequest;
import org.apache.maven.executor.ExecutorResult;
import org.apache.maven.executor.forked.ForkedMavenExecutor;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Runs the demo reactor (real compiler and surefire plugins) with the freshly built extension on
 * Maven's extension classpath, and asserts on the JSON report. Needs Maven Central on first run.
 */
class DemoIT {
    private static final String TOTALS_API = "{\"run\":3,\"failed\":0,\"errors\":0,\"skipped\":1}";

    private static Path work;
    private static Path demo;
    private static Path settings;
    private static Path home;
    private static Path extension;
    private static int runs;
    private static JsonNode report; // the full demo reactor with -fae

    /** One Maven invocation: everything it printed, and the JSON report if it printed one. */
    private static final class Run {
        final String out;
        final List<String> lines;
        final JsonNode report;

        Run(String out) throws IOException {
            this.out = out;
            this.lines = Arrays.asList(out.split("\\R"));
            String json = null;
            for (String l : lines) {
                if (l.startsWith("{\"schema\"") || l.startsWith("{\"event\":\"summary\"")) {
                    json = l;
                }
            }
            this.report = json != null ? new ObjectMapper().readTree(json) : null;
        }

        boolean printedReport() {
            return report != null;
        }
    }

    @BeforeAll
    static void setUp() throws Exception {
        work = Paths.get(System.getProperty("work.dir"));
        Path remote = work.resolve("remote");
        demo = work.resolve("demo");
        // the plugin cache survives between runs; the acme failure cache must not
        deleteTree(work.resolve("repo/com/acme"));
        deleteTree(remote);
        deleteTree(demo);
        copyTree(Paths.get(System.getProperty("demo.dir")), demo);

        // acme-client exists remotely, but its dependency acme-http does not: a transitive miss
        Path client = remote.resolve("com/acme/acme-client/1.0");
        Files.createDirectories(client);
        write(
                client.resolve("acme-client-1.0.pom"),
                "<project><modelVersion>4.0.0</modelVersion><groupId>com.acme</groupId>"
                        + "<artifactId>acme-client</artifactId><version>1.0</version><dependencies><dependency>"
                        + "<groupId>com.acme</groupId><artifactId>acme-http</artifactId><version>2.3.1</version>"
                        + "</dependency></dependencies></project>");
        Files.write(client.resolve("acme-client-1.0.jar"), new byte[0]);

        settings = work.resolve("settings.xml");
        write(
                settings,
                "<settings><localRepository>" + work.resolve("repo") + "</localRepository>"
                        + "<profiles><profile><id>corp</id><repositories><repository><id>corp-nexus</id>"
                        + "<url>" + remote.toUri() + "</url></repository></repositories></profile></profiles>"
                        + "<activeProfiles><activeProfile>corp</activeProfile></activeProfiles></settings>");

        // private user.home: ~/.m2/extensions.xml and ~/.m2/settings.xml must not leak into the runs
        home = Files.createDirectories(work.resolve("home"));
        // the extension jar (or classes directory) the reactor put on our classpath; the class itself
        // needs maven-core, so only its resource is looked up
        String res = "eu/maveniverse/maven/muninn/extension3/AgentOutputSpy.class";
        String url = DemoIT.class.getClassLoader().getResource(res).toString();
        extension = url.startsWith("jar:")
                ? Paths.get(URI.create(url.substring(4, url.indexOf('!'))))
                : Paths.get(URI.create(url.substring(0, url.length() - res.length())));

        report = mvn(demo, noAgent(), "-fae", "verify", "-Dmuninn.output=json").report;
        assertNotNull(report, "no JSON report printed, see " + work);
    }

    private static Map<String, String> noAgent() {
        Map<String, String> env = new HashMap<>();
        env.put("CLAUDECODE", "0");
        env.put("GEMINI_CLI", "0");
        env.put("AGENT", "");
        return env;
    }

    private static Run mvn(Path cwd, Map<String, String> env, String... args) throws Exception {
        List<String> all =
                new ArrayList<>(Arrays.asList("-B", "-s", settings.toString(), "-Dmaven.ext.class.path=" + extension));
        all.addAll(Arrays.asList(args));
        ExecutorRequest request = ExecutorRequest.mavenBuilder()
                .cwd(cwd)
                .userHomeDirectory(home)
                .arguments(all)
                .environmentVariables(env)
                .build();
        ExecutorResult result;
        try (Executor mvn = new ForkedMavenExecutor(Paths.get(System.getProperty("maven.home")))) {
            result = mvn.execute(request);
        }
        String out = result.stdOutString().orElse("");
        write(work.resolve("mvn-" + ++runs + ".out"), out);
        return new Run(out);
    }

    private static JsonNode module(JsonNode report, String id) {
        for (JsonNode m : report.get("modules")) {
            if (id.equals(m.get("id").asText())) {
                return m;
            }
        }
        throw new AssertionError("no module " + id);
    }

    private static JsonNode module(String id) {
        return module(report, id);
    }

    @Test
    void overall() {
        assertEquals("FAILURE", report.get("result").asText());
        assertEquals(
                "{\"ok\":2,\"failed\":3,\"skipped\":2}", report.get("counts").toString());
        assertTrue(report.get("resume").asText().endsWith("-rf :core"));
    }

    /** api: surefire in a custom reportsDirectory plus a failsafe IT; core: failing tests. */
    @Test
    void testTotals() {
        assertEquals(
                "{\"run\":7,\"failed\":1,\"errors\":1,\"skipped\":1}",
                report.get("tests").toString());
    }

    @Test
    void testFailures() {
        JsonNode core = module("core");
        assertEquals(
                "{\"run\":4,\"failed\":1,\"errors\":1,\"skipped\":0}",
                core.get("tests").toString());
        assertTrue(core.get("failures").get(0).get("at").asText().matches(".*ResolverTest\\.java:\\d+"));
    }

    @Test
    void compileErrors() {
        assertEquals(3, module("util").get("errors").size());
    }

    @Test
    void transitiveMissingDependency() {
        JsonNode missing = module("net").get("missing").get(0);
        assertEquals("[\"com.acme:acme-client:jar:1.0\"]", missing.get("via").toString());
    }

    @Test
    void skippedModulesNameFailedUpstream() {
        assertEquals("upstream failed: core", module("cli").get("cause").asText());
        assertEquals("upstream failed: util", module("plugins").get("cause").asText());
    }

    @Test
    void logAndReportFilesWritten() {
        assertTrue(Files.isRegularFile(demo.resolve("target/muninn-output/build.log")));
        assertTrue(Files.isRegularFile(demo.resolve("target/muninn-output/report.json")));
    }

    @Test
    void successListsNoModules() throws Exception {
        JsonNode r = mvn(demo, noAgent(), "-pl", "api", "verify", "-Dmuninn.output=json").report;
        assertEquals("SUCCESS", r.get("result").asText());
        assertEquals(TOTALS_API, r.get("tests").toString());
        assertNull(r.get("modules"));
    }

    @Test
    void verboseListsSuccessfulModules() throws Exception {
        JsonNode r = mvn(
                        demo, noAgent(), "-pl", "api", "verify", "-Dmuninn.output=json", "-Dmuninn.output.verbose=true")
                .report;
        assertEquals(TOTALS_API, module(r, "api").get("tests").toString());
    }

    @Test
    void failFastCollapsesModulesNeverStarted() throws Exception {
        JsonNode r = mvn(demo, noAgent(), "verify", "-Dmuninn.output=json").report;
        assertEquals("FAILURE", r.get("result").asText());
        assertTrue(r.get("not_built").get("ids").size() > 0);
        assertTrue(r.get("not_built").get("cause").asText().contains("fail-fast"));
    }

    @Test
    void ndjsonEmitsOneLinePerModuleThenSummary() throws Exception {
        Run run = mvn(demo, noAgent(), "-fae", "verify", "-Dmuninn.output=ndjson");
        ObjectMapper om = new ObjectMapper();
        JsonNode core = null;
        for (String l : run.lines) {
            if (l.startsWith("{\"event\":\"module\"")) {
                JsonNode n = om.readTree(l);
                if ("core".equals(n.get("id").asText())) {
                    core = n;
                }
            }
        }
        assertNotNull(core);
        assertEquals("FAILED", core.get("status").asText());
        assertEquals("test", core.get("kind").asText());
        assertEquals("summary", run.report.get("event").asText());
    }

    @Test
    void inactiveWithoutSwitch() throws Exception {
        Run run = mvn(demo, noAgent(), "-pl", "api", "verify");
        assertFalse(run.printedReport());
        assertTrue(run.out.contains("BUILD SUCCESS"));
    }

    @Test
    void agentDetectionEnablesJson() throws Exception {
        Map<String, String> env = noAgent();
        env.put("CLAUDECODE", "1");
        assertTrue(mvn(demo, env, "-pl", "api", "verify").printedReport());
    }

    @Test
    void explicitOffBeatsAgentDetection() throws Exception {
        Map<String, String> env = noAgent();
        env.put("CLAUDECODE", "1");
        assertFalse(
                mvn(demo, env, "-pl", "api", "verify", "-Dmuninn.output=off").printedReport());
    }

    @Test
    void cachedResolutionFailureHintsAtForcedUpdate() throws Exception {
        mvn(demo, noAgent(), "-pl", "net", "verify", "-Dmuninn.output=json");
        JsonNode r = mvn(demo, noAgent(), "-pl", "net", "verify", "-Dmuninn.output=json").report;
        JsonNode net = module(r, "net");
        assertEquals("cached_failure", net.get("missing").get(0).get("reason").asText());
        assertTrue(net.get("hint").asText().contains("-U"));
    }

    @Test
    void offlineResolutionFailure() throws Exception {
        JsonNode r = mvn(demo, noAgent(), "-o", "-pl", "net", "verify", "-Dmuninn.output=json").report;
        String reason = module(r, "net").get("missing").get(0).get("reason").asText();
        assertTrue(reason.equals("offline_not_cached") || reason.equals("cached_failure"), reason);
    }

    @Test
    void missingDependencyVersionIsAPomProblem() throws Exception {
        Path dir = Files.createDirectories(work.resolve("bad-model"));
        write(
                dir.resolve("pom.xml"),
                "<project><modelVersion>4.0.0</modelVersion><groupId>g</groupId><artifactId>a</artifactId>"
                        + "<version>1</version><dependencies><dependency><groupId>x</groupId><artifactId>y</artifactId>"
                        + "</dependency></dependencies></project>");
        JsonNode r = mvn(dir, noAgent(), "verify", "-Dmuninn.output=json").report;
        assertEquals("pom", r.get("errors").get(0).get("kind").asText());
    }

    @Test
    void unparseablePomIsAPomProblem() throws Exception {
        Path dir = Files.createDirectories(work.resolve("broken-xml"));
        write(dir.resolve("pom.xml"), "<project><modelVersion>4.0.0</modelVersion><groupId>g</project>");
        JsonNode r = mvn(dir, noAgent(), "verify", "-Dmuninn.output=json").report;
        assertEquals("pom", r.get("errors").get(0).get("kind").asText());
    }

    private static void write(Path file, String content) throws IOException {
        Files.write(file, content.getBytes(StandardCharsets.UTF_8));
    }

    private static void copyTree(Path from, Path to) throws IOException {
        try (Stream<Path> s = Files.walk(from)) {
            for (Path src : (Iterable<Path>) s::iterator) {
                Path dst = to.resolve(from.relativize(src).toString());
                if (Files.isDirectory(src)) {
                    Files.createDirectories(dst);
                } else {
                    Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    private static void deleteTree(Path dir) throws IOException {
        if (Files.exists(dir)) {
            try (Stream<Path> s = Files.walk(dir)) {
                for (Path p : (Iterable<Path>) s.sorted(Comparator.reverseOrder())::iterator) {
                    Files.delete(p);
                }
            }
        }
    }
}
