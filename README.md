# Maveniverse Muninn

A Maven core extension that replaces console output with one compact JSON report, for coding agents. The full log is still written to disk, and the report points to it.

Works on Maven 3.9 (slf4j 1.x) and 3.10 (slf4j 2.x); the extension is compiled against 3.9.16 / slf4j 1.7.36 and handles both logger layouts by reflection.

## Use

Register it in `.mvn/extensions.xml` or, **as recommended user-wide extension in** `~/.m2/extensions.xml` (with Maven 3.10.x):

```xml
<extensions>
  <extension>
    <groupId>eu.maveniverse.maven.muninn</groupId>
    <artifactId>extension3</artifactId>
    <version>0.1.1</version>
  </extension>
</extensions>
```

Then turn it on per run. Without the switch the extension does nothing, so human builds are unchanged.

| Switch | Effect |
|---|---|
| `-Dmuninn.output=json` | Silent build, one JSON object on stdout at the end |
| `-Dmuninn.output=ndjson` | One line per finished module, then the summary line |
| `MUNINN_OUTPUT=json` | Same, via environment (handy in `AGENTS.md` / agent config) |
| *(none)* | Auto-enables `json` when an agent is detected (`CLAUDECODE=1`, `GEMINI_CLI=1`, or `AGENT` set). `-Dmuninn.output=off` or `MUNINN_OUTPUT=off` opts out |
| `-Dmuninn.output.verbose=true` | Also list successful modules |
| `-Dmuninn.output.maxErrors=N`, `.maxTests=N`, `.maxFrames=N` | Budgets (defaults 10, 10, 5) |
| `-l <file>` | The extension stays inactive whatever the switches say: Maven's own log file is left as is |

Exit codes are Maven's own; with `-fn` the report still says `FAILURE` when a module failed, though Maven exits 0. Two files are written under the top-level project's build directory (`target` unless customized):

- `muninn-output/build.log`: the complete console log, ANSI stripped, including Maven's error epilogue
- `muninn-output/report.json`: the same report as stdout

## Example

From the demo in `it/`: six modules with a test failure, a compile error, a missing dependency and two downstream modules, built with `-fae`. Shown pretty-printed and lightly trimmed; the real output is one line.

```json
{
  "schema": 1, "result": "FAILURE", "goals": "verify", "time_s": 0.8,
  "counts": {"ok": 2, "failed": 3, "skipped": 2},
  "tests": {"run": 6, "failed": 1, "errors": 1, "skipped": 1},
  "modules": [
    {"id": "core", "path": "core", "status": "FAILED", "phase": "test", "mojo": "surefire:test@default-test",
     "kind": "test", "tests": {"run": 4, "failed": 1, "errors": 1, "skipped": 0},
     "summary": "2 of 4 test(s) failed",
     "failures": [
       {"test": "com.acme.core.ResolverTest#testRangeIncludesUpper", "kind": "failure",
        "type": "java.lang.AssertionError", "message": "expected:<1.2> but was:<1.1>",
        "at": "core/src/test/java/com/acme/core/ResolverTest.java:27",
        "stack": ["com.acme.core.ResolverTest.check(ResolverTest.java:27)",
                  "com.acme.core.ResolverTest.testRangeIncludesUpper(ResolverTest.java:14)"]},
       {"test": "com.acme.core.ResolverTest#testMissingNode", "kind": "error",
        "type": "java.lang.NullPointerException", "message": "Cannot invoke \"String.length()\" because ...",
        "at": "core/src/test/java/com/acme/core/ResolverTest.java:22",
        "stack": ["com.acme.core.Resolver.depth(Resolver.java:22)",
                  "com.acme.core.ResolverTest.testMissingNode(ResolverTest.java:22)"]}],
     "reports": "core/target/surefire-reports"},
    {"id": "util", "path": "util", "status": "FAILED", "phase": "compile", "mojo": "compiler:compile@default-compile",
     "kind": "compile", "summary": "3 compilation error(s)",
     "errors": [
       {"file": "util/src/main/java/com/acme/util/Strings.java", "line": 7, "col": 16,
        "message": "cannot find symbol", "detail": "symbol: variable Joiner; location: class com.acme.util.Strings"},
       {"file": "util/src/main/java/com/acme/util/Strings.java", "line": 11, "col": 28,
        "message": "incompatible types: int cannot be converted to java.lang.String"}]},
    {"id": "cli", "path": "cli", "status": "SKIPPED", "cause": "upstream failed: core"},
    {"id": "plugins", "path": "plugins", "status": "SKIPPED", "cause": "upstream failed: util"},
    {"id": "net", "path": "net", "status": "FAILED", "kind": "dependency",
     "summary": "could not resolve 1 artifact(s)",
     "missing": [{"artifact": "com.acme:acme-http:jar:2.3.1", "via": ["com.acme:acme-client:jar:1.0"], "reason": "not_found",
                  "tried": [{"repo": "corp-nexus", "url": "file:///.../corp-repo", "reason": "not_found"}]}]}
  ],
  "resume": "mvn verify -fae -rf :core",
  "log": {"path": "target/muninn-output/build.log", "warningsLogged": 1, "errorsLogged": 0}
}
```

On this run the console output drops from 13.4 KB (~3,300 tokens) to 2.4 KB (~600 tokens). The demo plugins are quiet; real builds with download progress, surefire output and full stack traces save considerably more. A successful build is a single short line:

```json
{"schema":1,"result":"SUCCESS","goals":"verify","time_s":0.5,"counts":{"ok":1},"log":{"path":"target/muninn-output/build.log","warningsLogged":0,"errorsLogged":0}}
```

## What it recognizes

| `kind` | Source | Content |
|---|---|---|
| `compile` | Compiler plugin's exception long message (`file:[line,col] message` plus indented detail lines) | File relative to the reactor root, line, column, message, detail; deduplicated and capped |
| `test` | Surefire/failsafe `TEST-*.xml` reports, not console output | Counts, failing test, type, message, location of the failing line in the test, stack trimmed to the module's own classes |
| `dependency` | Maven's `DependencyResolutionResult` and Resolver's `ArtifactResolutionException` | Coordinates, `via` (the chain from the direct dependency, for transitive ones), each repository tried, and a reason: `not_found`, `cached_failure` (with a hint to use `-U`), `offline_not_cached`, `unauthorized`, `forbidden`, `checksum` or `transfer` |
| `javadoc` | maven-javadoc-plugin's exception message, which embeds the javadoc tool output | Errors and warnings with file, line, column and message; warnings appear only when the build failed (errors, or `failOnWarnings`) |
| `pom` | `ProjectBuildingException` model problems | File and line, message (session-level `errors`, no module) |
| `mojo` / `build` | Anything else | Deepest non-empty exception message, plus the exception class |

Other behaviour:

- **Test totals:** the top-level `tests` object (`run`, `failed`, `errors`, `skipped`) sums the surefire and failsafe reports of all modules, passing ones included; each module entry carries its own (passing modules only with `verbose`). Only `surefire:test` and `failsafe:integration-test` count (a configured `reportsDirectory` is honored), and only report files written during this build: leftovers from earlier runs (e.g. with `-DskipTests`) are ignored.
- **Downstream modules:** skipped modules name the failed upstream module.
- **Fail-fast:** modules never started are collapsed into one `not_built` entry, with a note to add `-fae`.
- **Resume command:** `resume` rebuilds the command from the request, including goals, `-f`, `-s`, command-line profiles (not those activated by `settings.xml`), `-pl`, `-am`/`-amd`, `-fae`, `-o`, `-U`, `-T`, `-b` and `-D` properties. `-rf` names the module as `groupId:artifactId` when its artifactId alone is ambiguous.
- **Batch mode:** while active, the build runs non-interactively even without `-B`, since a prompt would go to the log and the build would wait for input nobody sees.
- **One copy at a time:** if the extension is registered twice (user-wide and per project), the first copy to start does the work and the other stays inactive.

## How it works

- **Lifecycle hook:** an `EventSpy` registered through the Sisu index. It reads the request, `ExecutionEvent`s and the `MavenExecutionResult`, and does all its work in `close()`.
- **Console capture:** Maven 3.9 ships `cacheOutputStream=true`, so the logger keeps the stdout it saw at startup and swapping `System.out` is not enough. The extension re-points maven-slf4j-provider's output target by reflection, which has the same effect as `-l <file>` decided after startup. It also swaps `System.out`/`System.err` for direct writers.
- **Fallback:** if the reflection fails (different logging backend), the build log stays on the console, the report still prints at the end, and the report says `"captured": false`. If Maven exits without closing the spy (on 3.9, e.g. an invalid `settings.xml`), the captured log is printed to the console at exit instead of the report.
- **Log placement:** the log goes to a temp file first, because `mvn clean` deletes `target/` during the build. It moves to `target/muninn-output/build.log` at the end, and Maven's error epilogue, printed after the spy closes, is appended there.
- **No dependencies:** only Maven-provided APIs and a hand-written JSON writer, compiled for Java 8.

The `it` module runs the six-module reactor in `it/src/test/projects/demo` with the real compiler and surefire plugins and the just-built extension (`-Dmaven.ext.class.path`), and asserts on the report. `DemoIT` runs under failsafe only with `-Prun-its`.

## Tested

- **Maven versions:** 3.9.16 (slf4j 1.7) and 3.10.0 (slf4j 2.0), single-threaded; `-T4` on 3.10 only.
- **Scenarios:**
  - fail-fast, `-fae` and `ndjson`
  - success
  - compile errors and test failures/errors
  - not found, cached failure (`-U` hint) and offline resolution failures
  - unparseable POM and missing dependency version
  - extension inactive without the switch, agent auto-detection and `off`
  - test totals from surefire (custom `reportsDirectory`) and failsafe

`DemoIT` automates all of these on whichever Maven runs the build (CI runs it on 3.9.16 and 3.10.0); `-T` is not covered.

The demo reactor uses the real maven-compiler-plugin, maven-surefire-plugin and maven-failsafe-plugin (JUnit 5), so compile and test failures and the test totals come from real plugin output. Maven Central is needed on the first run; plugins are then cached in `it/target/it-work/repo`.

## Not done yet

- **Other compilers:** not run with the Kotlin, Groovy or Scala compilers.
- **Maven 4:** the logging backend differs, so expect `"captured": false` until a Maven 4 redirect is added.
- **Compiler warnings:** only counted today; a capped list could be useful.
- **Javadoc warnings on success:** reported only when the build fails (errors or `failOnWarnings`); on a passing build they are just counted.
- **`via` coverage:** only for resolution failures; not for collection failures (missing parent or descriptor pom) or plugin resolution, and only one path is shown when several exist.
- **Fixtures for javadoc and `via`:** the javadoc parser has not been run against real maven-javadoc-plugin output, and the demo has no javadoc case.
- **Automated tests:** there is no `src/test`; verification is the `it` module (`DemoIT`, 18 tests) only.
- **Parallel builds on 3.9:** `-T` is tested on 3.10 only.

## Build

```
./mvnw clean verify              # build the extension
./mvnw clean verify -Prun-its    # ... and run the ITs (Central on first run)
```

Licensed under [EPL-2.0](LICENSE).
