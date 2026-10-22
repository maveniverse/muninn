/*
 * Copyright (c) 2026 Maveniverse Org.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-v20.html
 */
package eu.maveniverse.maven.muninn.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Runs the demo reactor (real compiler and surefire plugins) with the freshly built extension on
 * Maven's extension classpath, and asserts on the JSON report. Needs Maven Central on first run.
 */
class DemoIT {
    private static JsonNode report;
    private static Path demo;

    @BeforeAll
    static void run() throws Exception {
        Path work = Paths.get(System.getProperty("work.dir"));
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
        Files.write(
                client.resolve("acme-client-1.0.pom"),
                ("<project><modelVersion>4.0.0</modelVersion><groupId>com.acme</groupId>"
                                + "<artifactId>acme-client</artifactId><version>1.0</version><dependencies><dependency>"
                                + "<groupId>com.acme</groupId><artifactId>acme-http</artifactId><version>2.3.1</version>"
                                + "</dependency></dependencies></project>")
                        .getBytes(StandardCharsets.UTF_8));
        Files.write(client.resolve("acme-client-1.0.jar"), new byte[0]);

        Path settings = work.resolve("settings.xml");
        Files.write(
                settings,
                ("<settings><localRepository>" + work.resolve("repo") + "</localRepository>"
                                + "<profiles><profile><id>corp</id><repositories><repository><id>corp-nexus</id>"
                                + "<url>" + remote.toUri() + "</url></repository></repositories></profile></profiles>"
                                + "<activeProfiles><activeProfile>corp</activeProfile></activeProfiles></settings>")
                        .getBytes(StandardCharsets.UTF_8));

        Path home = Files.createDirectories(work.resolve("home"));
        // the extension jar (or classes directory) the reactor put on our classpath; the class itself
        // needs maven-core, so only its resource is looked up
        String res = "eu/maveniverse/maven/muninn/extension3/AgentOutputSpy.class";
        String url = DemoIT.class.getClassLoader().getResource(res).toString();
        Path extension = url.startsWith("jar:")
                ? Paths.get(URI.create(url.substring(4, url.indexOf('!'))))
                : Paths.get(URI.create(url.substring(0, url.length() - res.length())));
        ProcessBuilder pb = new ProcessBuilder(
                Paths.get(System.getProperty("maven.home"), "bin", "mvn").toString(),
                "-B",
                "-s",
                settings.toString(),
                "-fae",
                "verify",
                "-Dmaven.ext.class.path=" + extension,
                "-Dmuninn.output=json");
        pb.directory(demo.toFile());
        // private user.home: ~/.m2/extensions.xml and ~/.m2/settings.xml must not leak into the run
        pb.environment().put("MAVEN_OPTS", "-Duser.home=" + home);
        pb.redirectErrorStream(true);
        Path out = work.resolve("mvn.out");
        pb.redirectOutput(out.toFile());
        pb.start().waitFor();
        List<String> lines = Files.readAllLines(out, StandardCharsets.UTF_8);
        String json = lines.stream()
                .filter(l -> l.startsWith("{\"schema\""))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no JSON report printed, see " + out));
        report = new ObjectMapper().readTree(json);
    }

    private static JsonNode module(String id) {
        for (JsonNode m : report.get("modules")) {
            if (id.equals(m.get("id").asText())) {
                return m;
            }
        }
        throw new AssertionError("no module " + id);
    }

    @Test
    void overall() {
        assertEquals("FAILURE", report.get("result").asText());
        assertEquals(
                "{\"ok\":2,\"failed\":3,\"skipped\":2}", report.get("counts").toString());
        assertTrue(report.get("resume").asText().endsWith("-rf :core"));
    }

    @Test
    void testTotals() {
        assertEquals(
                "{\"run\":6,\"failed\":1,\"errors\":1,\"skipped\":1}",
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
