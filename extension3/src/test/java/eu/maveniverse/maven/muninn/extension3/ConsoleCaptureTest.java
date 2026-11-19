/*
 * Copyright (c) 2026 Maveniverse Org.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-v20.html
 */
package eu.maveniverse.maven.muninn.extension3;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class ConsoleCaptureTest {

    @Test
    void stripsAnsiAndCountsLinesAcrossChunkBoundaries() throws Exception {
        byte[] in = "\u001b[1;31m[ERROR]\u001b[m boom ü\n[WARNING] w\nplain\n\u001b[1m[ERROR]\u001b[m again\n"
                .getBytes(StandardCharsets.UTF_8);
        for (int split = 0; split <= in.length; split++) {
            ByteArrayOutputStream sink = new ByteArrayOutputStream();
            ConsoleCapture.LineStats stats = new ConsoleCapture.LineStats(sink);
            stats.write(in, 0, split); // a split inside an escape sequence or a UTF-8 character must not matter
            stats.write(in, split, in.length - split);
            assertEquals(
                    "[ERROR] boom ü\n[WARNING] w\nplain\n[ERROR] again\n",
                    new String(sink.toByteArray(), StandardCharsets.UTF_8),
                    "split at " + split);
            assertEquals(2, stats.errors.get());
            assertEquals(1, stats.warnings.get());
        }
    }
}
