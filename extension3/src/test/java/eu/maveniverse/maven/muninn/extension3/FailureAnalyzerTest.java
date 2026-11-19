/*
 * Copyright (c) 2026 Maveniverse Org.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-v20.html
 */
package eu.maveniverse.maven.muninn.extension3;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.apache.maven.plugin.MojoExecution;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugin.descriptor.MojoDescriptor;
import org.apache.maven.plugin.descriptor.PluginDescriptor;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.resolution.ArtifactRequest;
import org.eclipse.aether.resolution.ArtifactResolutionException;
import org.eclipse.aether.resolution.ArtifactResult;
import org.eclipse.aether.transfer.ArtifactNotFoundException;
import org.eclipse.aether.transfer.ArtifactTransferException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Parsers fed with message text captured from real plugin runs. */
class FailureAnalyzerTest {

    @TempDir
    Path root;

    private FailureAnalyzer analyzer(boolean offline) {
        return new FailureAnalyzer(new ReactorPaths(root), new FailureAnalyzer.Limits(), offline);
    }

    private String abs(String rel) {
        return root.resolve(rel).toString();
    }

    static MojoExecution mojo(String artifactId, String goal) {
        PluginDescriptor pd = new PluginDescriptor();
        pd.setGroupId("org.apache.maven.plugins");
        pd.setArtifactId(artifactId);
        pd.setGoalPrefix(artifactId.replace("maven-", "").replace("-plugin", ""));
        MojoDescriptor md = new MojoDescriptor();
        md.setGoal(goal);
        md.setPluginDescriptor(pd);
        return new MojoExecution(md, "default-" + goal);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> list(Map<String, Object> m, String key) {
        return (List<Map<String, Object>>) m.get(key);
    }

    @Test
    void compilerDiagnosticsWithDetailAreRelativizedAndDeduplicated() {
        String file = abs("util/src/main/java/com/acme/util/Strings.java");
        String longMessage = "Compilation failure: \n"
                + file + ":[7,16] cannot find symbol\n"
                + "  symbol:   variable Joiner\n"
                + "  location: class com.acme.util.Strings\n"
                + file + ":[11,28] incompatible types: int cannot be converted to java.lang.String\n"
                + file + ":[7,16] cannot find symbol\n"
                + "  symbol:   variable Joiner\n"
                + "  location: class com.acme.util.Strings\n";
        Map<String, Object> m = analyzer(false)
                .analyzeModule(new MojoFailureException(this, "Compilation failure", longMessage), null, null, null);
        assertEquals("compile", m.get("kind"));
        assertEquals("2 compilation error(s)", m.get("summary"));
        Map<String, Object> first = list(m, "errors").get(0);
        assertEquals("util/src/main/java/com/acme/util/Strings.java", first.get("file"));
        assertEquals(7, first.get("line"));
        assertEquals(16, first.get("col"));
        assertEquals("cannot find symbol", first.get("message"));
        assertEquals("symbol: variable Joiner; location: class com.acme.util.Strings", first.get("detail"));
    }

    @Test
    void compileFailureWithoutLocationKeepsTheMessage() {
        String longMessage = "Compilation failure\nwarnings found and -Werror specified";
        Map<String, Object> m = analyzer(false)
                .analyzeModule(new MojoFailureException(this, "Compilation failure", longMessage), null, null, null);
        assertEquals("compile", m.get("kind"));
        assertEquals("warnings found and -Werror specified", m.get("summary"));
        assertNull(m.get("errors"));
    }

    /** maven-javadoc-plugin 3.12.0 on JDK 25, as printed in Maven's error epilogue. */
    @Test
    void javadocErrorsAndWarningsWithColumns() {
        String file = abs("src/main/java/p/Foo.java");
        String message = "An error has occurred in Javadoc report generation: \n"
                + "Exit code: 1\n"
                + file + ":7: error: unknown tag. Unregistered custom tag?\n"
                + "     * @foo nope\n"
                + "       ^\n"
                + file + ":8: error: @param name not found\n"
                + "     * @param y missing\n"
                + "              ^\n"
                + file + ":10: warning: no @param for x\n"
                + "    public void bar(int x) {}\n"
                + "                ^\n"
                + "2 errors\n"
                + "1 warning\n"
                + "Command line was: /usr/bin/javadoc @options @packages\n";
        Map<String, Object> m = analyzer(false)
                .analyzeModule(new MojoFailureException(message), mojo("maven-javadoc-plugin", "javadoc"), null, null);
        assertEquals("javadoc", m.get("kind"));
        assertEquals("2 javadoc error(s), 1 warning(s)", m.get("summary"));
        Map<String, Object> first = list(m, "errors").get(0);
        assertEquals("src/main/java/p/Foo.java", first.get("file"));
        assertEquals(7, first.get("line"));
        assertEquals(8, first.get("col"));
        assertEquals("unknown tag. Unregistered custom tag?", first.get("message"));
        assertEquals(17, list(m, "warnings").get(0).get("col"));
    }

    @Test
    void resolutionFailureReasons() {
        RemoteRepository central =
                new RemoteRepository.Builder("central", "default", "https://repo.example/maven2").build();
        RemoteRepository corp = new RemoteRepository.Builder("corp", "default", "https://corp.example/repo").build();
        DefaultArtifact missing = new DefaultArtifact("g:missing:1");
        DefaultArtifact secret = new DefaultArtifact("g:secret:1");
        ArtifactResult r1 = new ArtifactResult(new ArtifactRequest(missing, Arrays.asList(central), null));
        r1.addException(new ArtifactNotFoundException(missing, central));
        ArtifactResult r2 = new ArtifactResult(new ArtifactRequest(secret, Arrays.asList(corp), null));
        r2.addException(
                new ArtifactTransferException(secret, corp, "status code: 401, reason phrase: Unauthorized (401)"));
        Map<String, Object> m =
                analyzer(false).analyzeModule(new ArtifactResolutionException(Arrays.asList(r1, r2)), null, null, null);
        assertEquals("dependency", m.get("kind"));
        List<Map<String, Object>> entries = list(m, "missing");
        assertEquals("g:missing:jar:1", entries.get(0).get("artifact"));
        assertEquals("not_found", entries.get(0).get("reason"));
        assertEquals("central", list(entries.get(0), "tried").get(0).get("repo"));
        assertEquals("unauthorized", entries.get(1).get("reason"));
    }
}
