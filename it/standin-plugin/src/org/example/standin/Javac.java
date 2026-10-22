/*
 * Copyright (c) 2026 Maveniverse Org.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-v20.html
 */
package org.example.standin;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import org.apache.maven.plugin.logging.Log;

/** Compiles a source tree and formats errors like plexus-compiler's CompilerMessage.toString(). */
final class Javac {
    static void compile(File srcDir, File outDir, List<String> classpath, Log log) throws CompilationFailureException {
        if (!srcDir.isDirectory()) return;
        List<File> sources;
        try (Stream<java.nio.file.Path> s = java.nio.file.Files.walk(srcDir.toPath())) {
            sources = s.filter(p -> p.toString().endsWith(".java")).map(java.nio.file.Path::toFile).collect(Collectors.toList());
        } catch (java.io.IOException e) { throw new IllegalStateException(e); }
        if (sources.isEmpty()) return;
        outDir.mkdirs();
        log.info("Compiling " + sources.size() + " source file(s) to " + outDir);
        JavaCompiler jc = ToolProvider.getSystemJavaCompiler();
        DiagnosticCollector<JavaFileObject> diags = new DiagnosticCollector<>();
        try (StandardJavaFileManager fm = jc.getStandardFileManager(diags, Locale.ROOT, null)) {
            List<String> opts = new ArrayList<>();
            opts.add("-d"); opts.add(outDir.getAbsolutePath());
            opts.add("-encoding"); opts.add("UTF-8");
            List<String> cp = classpath.stream().filter(e -> new File(e).isDirectory() || e.endsWith(".jar")).collect(Collectors.toList());
            if (!cp.isEmpty()) { opts.add("-cp"); opts.add(String.join(File.pathSeparator, cp)); }
            boolean ok = jc.getTask(null, fm, diags, opts, null, fm.getJavaFileObjectsFromFiles(sources)).call();
            List<String> errors = new ArrayList<>();
            for (Diagnostic<? extends JavaFileObject> d : diags.getDiagnostics()) {
                String file = d.getSource() != null ? new File(d.getSource().toUri()).getPath() : "unknown";
                String msg = file + ":[" + d.getLineNumber() + "," + d.getColumnNumber() + "] " + d.getMessage(Locale.ROOT);
                if (d.getKind() == Diagnostic.Kind.ERROR) {
                    errors.add(msg);
                    log.error(msg);
                } else {
                    log.warn(msg);
                }
            }
            if (!ok) {
                log.info("-------------------------------------------------------------");
                log.error("COMPILATION ERROR : ");
                throw new CompilationFailureException(errors);
            }
        } catch (java.io.IOException e) { throw new IllegalStateException(e); }
    }
}
