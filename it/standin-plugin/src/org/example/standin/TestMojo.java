/*
 * Copyright (c) 2026 Maveniverse Org.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-v20.html
 */
package org.example.standin;

import java.io.File;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.project.MavenProject;

/**
 * Stand-in for surefire: compiles src/test/java, runs public void test*() methods, writes
 * TEST-*.xml in surefire's format, and fails with surefire's message.
 */
public class TestMojo extends AbstractMojo {
    private MavenProject project;

    public void execute() throws MojoExecutionException, MojoFailureException {
        File classes = new File(project.getBuild().getOutputDirectory());
        File testClasses = new File(project.getBuild().getTestOutputDirectory());
        File testSrc = new File(project.getBasedir(), "src/test/java");
        if (!testSrc.isDirectory()) { getLog().info("No tests to run."); return; }
        List<String> cp = new ArrayList<>();
        cp.add(classes.getAbsolutePath());
        Javac.compile(testSrc, testClasses, cp, getLog());
        File reports = new File(project.getBuild().getDirectory(), "surefire-reports");
        reports.mkdirs();
        int totalRun = 0, totalFail = 0, totalErr = 0;
        getLog().info("-------------------------------------------------------");
        getLog().info(" T E S T S");
        getLog().info("-------------------------------------------------------");
        try (URLClassLoader cl = new URLClassLoader(new URL[] {testClasses.toURI().toURL(), classes.toURI().toURL()},
                ClassLoader.getSystemClassLoader().getParent());
             Stream<java.nio.file.Path> walk = Files.walk(testClasses.toPath())) {
            List<String> names = walk.map(p -> testClasses.toPath().relativize(p).toString())
                    .filter(n -> n.endsWith("Test.class") && !n.contains("$"))
                    .map(n -> n.substring(0, n.length() - 6).replace(File.separatorChar, '.'))
                    .sorted().collect(Collectors.toList());
            for (String name : names) {
                Class<?> c = cl.loadClass(name);
                StringBuilder cases = new StringBuilder();
                int run = 0, fail = 0, err = 0;
                long suiteStart = System.nanoTime();
                for (Method m : c.getMethods()) {
                    if (!m.getName().startsWith("test") || m.getParameterCount() != 0 || Modifier.isStatic(m.getModifiers())) continue;
                    run++;
                    long t0 = System.nanoTime();
                    String body = "";
                    try {
                        m.invoke(c.getDeclaredConstructor().newInstance());
                    } catch (InvocationTargetException ite) {
                        Throwable t = ite.getCause();
                        boolean assertion = t instanceof AssertionError;
                        if (assertion) fail++; else err++;
                        StringWriter sw = new StringWriter();
                        t.printStackTrace(new PrintWriter(sw));
                        String tag = assertion ? "failure" : "error";
                        body = "\n      <" + tag + (t.getMessage() != null ? " message=\"" + xml(t.getMessage()) + "\"" : "")
                                + " type=\"" + t.getClass().getName() + "\"><![CDATA[" + sw + "]]></" + tag + ">\n    ";
                        getLog().error(m.getName() + "  Time elapsed: 0.001 s  <<< " + (assertion ? "FAILURE" : "ERROR") + "!");
                        getLog().error(sw.toString());
                    } catch (ReflectiveOperationException e) {
                        throw new MojoExecutionException("cannot run " + name, e);
                    }
                    cases.append("    <testcase name=\"").append(m.getName()).append("\" classname=\"").append(name)
                            .append("\" time=\"").append(String.format("%.3f", (System.nanoTime() - t0) / 1e9)).append("\">")
                            .append(body).append("</testcase>\n");
                }
                String suite = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                        + "<testsuite xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" xsi:noNamespaceSchemaLocation=\"https://maven.apache.org/surefire/maven-surefire-plugin/xsd/surefire-test-report-3.0.xsd\" version=\"3.0\" name=\""
                        + name + "\" time=\"" + String.format("%.3f", (System.nanoTime() - suiteStart) / 1e9) + "\" tests=\"" + run
                        + "\" errors=\"" + err + "\" skipped=\"0\" failures=\"" + fail + "\">\n  <properties/>\n" + cases + "</testsuite>\n";
                Files.write(new File(reports, "TEST-" + name + ".xml").toPath(), suite.getBytes(StandardCharsets.UTF_8));
                getLog().info("Tests run: " + run + ", Failures: " + fail + ", Errors: " + err + ", Skipped: 0 - in " + name);
                totalRun += run; totalFail += fail; totalErr += err;
            }
        } catch (java.io.IOException | ClassNotFoundException e) {
            throw new MojoExecutionException("test run failed", e);
        }
        getLog().info("Results:");
        getLog().info("Tests run: " + totalRun + ", Failures: " + totalFail + ", Errors: " + totalErr + ", Skipped: 0");
        if (totalFail + totalErr > 0) {
            throw new MojoFailureException("There are test failures.\n\nSee " + reports.getAbsolutePath()
                    + " for the individual test results.\nSee dump files (if any exist) [date].dump, [date]-jvmRun[N].dump and [date].dumpstream.");
        }
    }

    private static String xml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
