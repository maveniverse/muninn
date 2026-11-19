/*
 * Copyright (c) 2026 Maveniverse Org.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-v20.html
 */
package eu.maveniverse.maven.muninn.extension3;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import org.apache.maven.execution.DefaultMavenExecutionRequest;
import org.apache.maven.execution.MavenExecutionRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AgentOutputSpyTest {

    @TempDir
    Path cwd;

    @Test
    void resumeKeepsExclusionsAndReactorFlags() {
        MavenExecutionRequest r = new DefaultMavenExecutionRequest();
        r.setGoals(Arrays.asList("clean", "verify"));
        r.setActiveProfiles(Arrays.asList("extra", "corp"));
        r.setInactiveProfiles(Collections.singletonList("slow"));
        r.setSelectedProjects(Collections.singletonList("core"));
        r.setExcludedProjects(Collections.singletonList("it"));
        r.setRecursive(false);
        r.setReactorFailureBehavior(MavenExecutionRequest.REACTOR_FAIL_NEVER);
        r.getUserProperties().setProperty("skip it", "a'b");
        r.getUserProperties().setProperty("muninn.output", "json");
        assertEquals(
                "mvn clean verify -P'extra,!slow' -pl 'core,!it' -N -fn -D'skip it=a'\\''b' -rf :core",
                AgentOutputSpy.resumeCommand(r, cwd, Collections.singletonList("corp"), "core"));
    }

    @Test
    void outputGetsAPidSuffixOnlyWhenAnotherBuildWroteTheLogMeanwhile() throws Exception {
        long start = System.currentTimeMillis();
        assertEquals("", AgentOutputSpy.outputSuffix(cwd, start));
        Path log = Files.write(cwd.resolve("build.log"), new byte[0]);
        Files.setLastModifiedTime(log, FileTime.fromMillis(start - 60_000));
        assertEquals("", AgentOutputSpy.outputSuffix(cwd, start));
        Files.setLastModifiedTime(log, FileTime.fromMillis(start + 1000));
        assertTrue(AgentOutputSpy.outputSuffix(cwd, start).matches("-\\d+"));
    }

    private static Map<String, Object> failure(String test) {
        return Collections.singletonMap("test", test);
    }

    @Test
    void rerunSelectsJustTheFailingTests() {
        MavenExecutionRequest r = new DefaultMavenExecutionRequest();
        r.setGoals(Collections.singletonList("install"));
        r.setActiveProfiles(Collections.singletonList("extra"));
        r.getUserProperties().setProperty("test", "Old*");
        r.getUserProperties().setProperty("x", "1");
        assertEquals(
                "mvn test -Pextra -pl :core -am -Dx=1 -D'test=ResolverTest#a+b,OtherTest'"
                        + " -Dsurefire.failIfNoSpecifiedTests=false",
                AgentOutputSpy.rerunCommand(
                        r,
                        cwd,
                        Collections.emptyList(),
                        "core",
                        false,
                        Arrays.asList(
                                failure("com.acme.ResolverTest#a"),
                                failure("com.acme.ResolverTest#b(String)[1]"),
                                failure("com.acme.ResolverTest#b(String)[2]"),
                                failure("com.acme.OtherTest#"))));
        assertEquals(
                "mvn verify -pl g:x -am -D'it.test=FooIT#c' -Dfailsafe.failIfNoSpecifiedTests=false",
                AgentOutputSpy.rerunCommand(
                        null, cwd, Collections.emptyList(), "g:x", true, Arrays.asList(failure("p.FooIT#c"))));
    }

    @Test
    void ambiguousModuleKeepsGroupId() {
        MavenExecutionRequest r = new DefaultMavenExecutionRequest();
        r.setGoals(Collections.singletonList("install"));
        assertEquals("mvn install -rf g:x", AgentOutputSpy.resumeCommand(r, cwd, Collections.emptyList(), "g:x"));
    }
}
