/*
 * Copyright (c) 2026 Maveniverse Org.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-v20.html
 */
package eu.maveniverse.maven.muninn.extension3;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.UnsupportedEncodingException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Redirects Maven's console output into a log file so the console only carries the report.
 *
 * <p>Two things print to the console: the SLF4J logger and code writing to System.out/err.
 * Maven 3.9+ ships {@code cacheOutputStream=true}, so the logger holds on to the stream it saw
 * at startup and swapping System.out alone is not enough. The logger's output target is
 * therefore re-pointed reflectively (maven-slf4j-provider internals; if that fails, the
 * report still prints, after the normal log). The log starts in a temp file because
 * {@code mvn clean} would delete {@code target/} under us; it moves to its final place
 * once the build is over, and anything Maven prints after that (the error epilogue) is
 * appended there.
 */
final class ConsoleCapture {

    private final PrintStream originalOut;
    private final PrintStream originalErr;
    private final Path tempLog;
    private final SwitchableStream sink;
    private final LineStats stats;
    private final PrintStream capture;
    private boolean loggerRedirected;
    private boolean moved;

    private ConsoleCapture(
            PrintStream out,
            PrintStream err,
            Path tempLog,
            SwitchableStream sink,
            LineStats stats,
            PrintStream capture) {
        this.originalOut = out;
        this.originalErr = err;
        this.tempLog = tempLog;
        this.sink = sink;
        this.stats = stats;
        this.capture = capture;
    }

    static ConsoleCapture start() throws IOException {
        Path temp = Files.createTempFile("muninn-", ".log");
        SwitchableStream sink = new SwitchableStream(Files.newOutputStream(temp));
        LineStats stats = new LineStats(new BufferedOutputStream(sink, 16 * 1024));
        PrintStream capture;
        try {
            capture = new PrintStream(stats, true, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
        ConsoleCapture cc = new ConsoleCapture(System.out, System.err, temp, sink, stats, capture);
        cc.loggerRedirected = redirectLogger(capture);
        System.setOut(capture);
        System.setErr(capture);
        Runtime.getRuntime().addShutdownHook(new Thread(cc::abandon, "muninn-console"));
        return cc;
    }

    /**
     * Maven exited without closing the spy (on 3.9 e.g. invalid settings fail after extensions start):
     * hands the captured log back to the console instead of leaving it in a temp file nobody sees.
     */
    private synchronized void abandon() {
        if (moved) {
            return;
        }
        try {
            capture.flush();
            sink.close();
            Files.copy(tempLog, originalOut);
            originalOut.flush();
            Files.deleteIfExists(tempLog);
        } catch (IOException ignored) {
            // the JVM is going down; nothing else to try
        }
    }

    /**
     * Points maven-slf4j-provider's output at {@code target}. Equivalent to starting Maven with
     * {@code -l file}, but decided after startup.
     */
    private static boolean redirectLogger(PrintStream target) {
        try {
            ClassLoader cl =
                    org.slf4j.LoggerFactory.getILoggerFactory().getClass().getClassLoader();
            Class<?> simpleLogger = loadSlf4j("SimpleLogger", cl);
            Field configField = simpleLogger.getDeclaredField("CONFIG_PARAMS");
            configField.setAccessible(true);
            Object config = configField.get(null);
            if (config == null) {
                return false;
            }
            Class<?> outputChoice = loadSlf4j("OutputChoice", cl);
            Constructor<?> ctor = outputChoice.getDeclaredConstructor(PrintStream.class);
            ctor.setAccessible(true);
            Field choiceField = config.getClass().getDeclaredField("outputChoice");
            choiceField.setAccessible(true);
            choiceField.set(config, ctor.newInstance(target));
            return true;
        } catch (Throwable t) {
            return false; // different logging backend (e.g. Maven 4, logback); System.out swap still applies
        }
    }

    /** maven-slf4j-provider keeps its logger in {@code org.slf4j.simple} on slf4j 2.x and {@code org.slf4j.impl} on 1.x. */
    private static Class<?> loadSlf4j(String name, ClassLoader cl) throws ClassNotFoundException {
        try {
            return Class.forName("org.slf4j.simple." + name, true, cl);
        } catch (ClassNotFoundException e) {
            return Class.forName("org.slf4j.impl." + name, true, cl);
        }
    }

    boolean loggerRedirected() {
        return loggerRedirected;
    }

    /**
     * Moves the log to its final location; later output keeps flowing into it. Returns where the
     * log ended up: {@code target}, or the temp file if the move failed.
     */
    synchronized Path moveTo(Path target) {
        moved = true;
        try {
            capture.flush();
            sink.close(); // closes the temp file only
            Files.createDirectories(target.getParent());
            Files.move(tempLog, target, StandardCopyOption.REPLACE_EXISTING);
            sink.swap(Files.newOutputStream(target, StandardOpenOption.CREATE, StandardOpenOption.APPEND));
            return target;
        } catch (IOException e) {
            // Keep logging to the temp file if the move fails; report that path instead.
            try {
                sink.swap(Files.newOutputStream(tempLog, StandardOpenOption.CREATE, StandardOpenOption.APPEND));
            } catch (IOException ignored) {
                // nothing sensible left to do
            }
            return tempLog;
        }
    }

    PrintStream originalOut() {
        return originalOut;
    }

    PrintStream originalErr() {
        return originalErr;
    }

    int warnings() {
        return stats.warnings.get();
    }

    int errors() {
        return stats.errors.get();
    }

    /** Output stream whose delegate can be replaced. */
    private static final class SwitchableStream extends OutputStream {
        private OutputStream delegate;

        SwitchableStream(OutputStream delegate) {
            this.delegate = delegate;
        }

        synchronized void swap(OutputStream next) {
            this.delegate = next;
        }

        @Override
        public synchronized void write(int b) throws IOException {
            delegate.write(b);
        }

        @Override
        public synchronized void write(byte[] b, int off, int len) throws IOException {
            delegate.write(b, off, len);
        }

        @Override
        public synchronized void flush() throws IOException {
            delegate.flush();
        }

        @Override
        public synchronized void close() throws IOException {
            delegate.close();
        }
    }

    /**
     * Strips ANSI escape sequences and counts [WARNING]/[ERROR] lines on the way to the file.
     */
    private static final class LineStats extends OutputStream {
        private final OutputStream out;
        private final StringBuilder line = new StringBuilder();
        private int escState; // 0 = normal, 1 = saw ESC, 2 = inside CSI
        private final AtomicInteger warnings = new AtomicInteger(0);
        private final AtomicInteger errors = new AtomicInteger();

        LineStats(OutputStream out) {
            this.out = out;
        }

        @Override
        public synchronized void write(int b) throws IOException {
            if (escState == 1) {
                escState = (b == '[') ? 2 : 0;
                return;
            }
            if (escState == 2) {
                if (b >= 0x40 && b <= 0x7e) {
                    escState = 0;
                }
                return;
            }
            if (b == 0x1b) {
                escState = 1;
                return;
            }
            out.write(b);
            if (b == '\n') {
                countLine();
            } else if (line.length() < 16) {
                line.append((char) b);
            }
        }

        @Override
        public synchronized void write(byte[] b, int off, int len) throws IOException {
            for (int i = off; i < off + len; i++) {
                write(b[i]);
            }
        }

        private void countLine() {
            String s = line.toString();
            if (s.startsWith("[WARNING]")) {
                warnings.incrementAndGet();
            } else if (s.startsWith("[ERROR]")) {
                errors.incrementAndGet();
            }
            line.setLength(0);
        }

        @Override
        public void flush() throws IOException {
            out.flush();
        }
    }
}
