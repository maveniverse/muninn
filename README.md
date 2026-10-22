# Maveniverse Muninn

A Maven core extension that replaces console output with one compact JSON report, for coding agents. The full log is still written to disk, and the report points to it.

Works on Maven 3.9 (slf4j 1.x) and 3.10 (slf4j 2.x); the extension is compiled against 3.9.16 / slf4j 1.7.36 and handles both logger layouts by reflection.

## Use

Register it in `.mvn/extensions.xml`:

```xml
<extensions>
  <extension>
    <groupId>eu.maveniverse.maven.muninn</groupId>
    <artifactId>extension3</artifactId>
    <version>0.1.0-SNAPSHOT</version>
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

Exit codes are Maven's own. Two files are written under the reactor root:

- `target/agent-output/build.log`: the complete console log, ANSI stripped, including Maven's error epilogue
- `target/agent-output/report.json`: the same report as stdout

## Example

From the demo in `it/`: six modules with a test failure, a compile error, a missing dependency and two downstream modules, built with `-fae`. Shown pretty-printed and lightly trimmed; the real output is one line.

```json
{
  "schema": 1, "result": "FAILURE", "goals": "verify", "time_s": 0.8,
  "counts": {"ok": 2, "failed": 3, "skipped": 2},
  "modules": [
    {"id": "core", "path": "core", "status": "FAILED", "phase": "test", "mojo": "standin:test@default-test",
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
    {"id": "util", "path": "util", "status": "FAILED", "phase": "compile", "mojo": "standin:compile@default-compile",
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
     "missing": [{"artifact": "com.acme:acme-http:jar:2.3.1", "reason": "not_found",
                  "tried": [{"repo": "corp-nexus", "url": "file:///.../corp-repo", "reason": "not_found"}]}]}
  ],
  "resume": "mvn verify -fae -rf :core",
  "log": {"path": "target/agent-output/build.log", "warningsLogged": 1, "errorsLogged": 0}
}
```

On this run the console output drops from 13.4 KB (~3,300 tokens) to 2.4 KB (~600 tokens). The demo plugins are quiet; real builds with download progress, surefire output and full stack traces save considerably more. A successful build is a single short line:

```json
{"schema":1,"result":"SUCCESS","goals":"verify","time_s":0.5,"counts":{"ok":1},"log":{"path":"target/agent-output/build.log","warningsLogged":0,"errorsLogged":0}}
```

## What it recognizes

| `kind` | Source | Content |
|---|---|---|
| `compile` | Compiler plugin's exception long message (`file:[line,col] message` plus indented detail lines) | File relative to the reactor root, line, column, message, detail; deduplicated and capped |
| `test` | Surefire/failsafe `TEST-*.xml` reports, not console output | Counts, failing test, type, message, location of the failing line in the test, stack trimmed to the module's own classes |
| `dependency` | Maven's `DependencyResolutionResult` and Resolver's `ArtifactResolutionException` | Coordinates, each repository tried, and a reason: `not_found`, `cached_failure` (with a hint to use `-U`), `offline_not_cached`, `unauthorized`, `forbidden`, `checksum` or `transfer` |
| `pom` | `ProjectBuildingException` model problems | File and line, message (session-level `errors`, no module) |
| `mojo` / `build` | Anything else | Deepest non-empty exception message, plus the exception class |

Other behaviour:

- **Downstream modules:** skipped modules name the failed upstream module.
- **Fail-fast:** modules never started are collapsed into one `not_built` entry, with a note to add `-fae`.
- **Resume command:** `resume` rebuilds the command from the request, including goals, profiles, `-pl`, `-am`/`-amd`, `-fae`, `-o` and `-D` properties.

## How it works

- **Lifecycle hook:** an `EventSpy` registered through the Sisu index. It reads the request, `ExecutionEvent`s and the `MavenExecutionResult`, and does all its work in `close()`.
- **Console capture:** Maven 3.9 ships `cacheOutputStream=true`, so the logger keeps the stdout it saw at startup and swapping `System.out` is not enough. The extension re-points maven-slf4j-provider's output target by reflection, which has the same effect as `-l <file>` decided after startup. It also swaps `System.out`/`System.err` for direct writers.
- **Fallback:** if the reflection fails (different logging backend), the build log stays on the console, the report still prints at the end, and the report says `"captured": false`.
- **Log placement:** the log goes to a temp file first, because `mvn clean` deletes `target/` during the build. It moves to `target/agent-output/build.log` at the end, and Maven's error epilogue, printed after the spy closes, is appended there.
- **No dependencies:** only Maven-provided APIs and a hand-written JSON writer, compiled for Java 8.

`it/run-demo.sh` runs the whole demo offline: it builds the extension, installs it into a private repository, and runs the six-module reactor.

## Tested

- **Maven versions:** 3.9.16 (slf4j 1.7) and 3.10.0 (slf4j 2.0), single-threaded; `-T4` on 3.10 only.
- **Scenarios:**
  - fail-fast, `-fae` and `ndjson`
  - success
  - compile errors and test failures/errors
  - not found, cached failure (`-U` hint) and offline resolution failures
  - unparseable POM and missing dependency version
  - extension inactive without the switch

The workspace had no access to Maven Central, so compile and test failures came from a **stand-in plugin** (`it/standin-plugin`). It reproduces maven-compiler-plugin's `CompilationFailureException` message format, and surefire's `TEST-*.xml` format and failure message, but it is not the real plugins. Dependency resolution and POM errors went through real Maven and Resolver.

## Not done yet

- **Real plugins:** not run against the real compiler, surefire and failsafe plugins, or with the Kotlin, Groovy or Scala compilers.
- **`mvn clean`:** the temp-log path exists for it, but there was no clean plugin offline to test with.
- **Maven 4:** the logging backend differs, so expect `"captured": false` until a Maven 4 redirect is added.
- **Dependency path:** a `via` chain for missing transitive dependencies, so the agent knows which direct dependency pulls it in.
- **Compiler warnings:** only counted today; a capped list could be useful.
- **Read-only queries:** a describe command for agents (effective model, dependency graph and modules as JSON) is not implemented.

## Build

```
./mvnw clean verify          # build the extension
./build.sh && it/run-demo.sh # offline demo (javac only, no Maven Central)
```

Licensed under [EPL-2.0](LICENSE).
