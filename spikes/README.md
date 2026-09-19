# M0 risk spikes

Throwaway code that answered two go/no-go questions before the real backend was written.
Measured on 2026-09-19, Windows 11, JDK 17.0.20, single thread.

## Spike A: Tree-sitter from Java 17 -> GO

| Binding | Result |
|---|---|
| `io.github.tree-sitter:jtreesitter` 0.26.1 (official) | Compiled for Java 23 (class version 67). Does not run on JDK 17. |
| `io.github.bonede:tree-sitter` 0.26.6 + `tree-sitter-python` 0.25.0 (JNI) | Works on JDK 17. Bundles native libs for Windows, Linux (x86_64, aarch64) and macOS. |

Test repos: Flask (83 `.py` files) and Django (2,932 `.py` files, 19.9 MB).

| Extraction method on Django | Parse | Extract | Total |
|---|---|---|---|
| Indexed child walk (`getNamedChild(i)`) | ~7.5 s | ~29 s | 36.6 s |
| Tree cursor walk | 7.7 s | 16.2 s | 24.5 s |
| **Tree-sitter query** (matching in native code) | 7.3 s | 3.4 s | **11.2 s** |

All three found the same 11,920 imports and 43,844 class/function definitions.
Only 1 file had a syntax error: `tests/test_runner_apps/tagged/tests_syntax_error.py`, which is intentionally invalid.

**Decisions**
- Use the bonede binding. Keep the JDK at 17.
- Extract with Tree-sitter queries, not Java-side tree walks (JNI calls per node are the bottleneck).
- Parse in parallel (one parser per thread) if large repos need it.
- Still to verify: the Linux native lib inside the Docker image (done as part of the skeleton).

## Spike B: JGit history and blame speed -> GO

Test repo: Flask, 5,557 commits, 897 author identities, 83 `.py` files, 18,345 lines.

| Task | Time |
|---|---|
| Walk all commits and list changed files (rename detection on) | 6.1 s |
| Blame all 83 `.py` files, JGit | 5.0 s |
| Blame all 83 `.py` files, `git` CLI (`--line-porcelain -w`) | 7.9-8.5 s |

**Finding: ignore whitespace when blaming.** Without it, JGit and the CLI disagreed badly
(one author: 1,247 lines vs 382). The cause was commits that removed test classes, which re-indents
every line inside them and moves ownership to whoever did the re-indent. With
`RawTextComparator.WS_IGNORE_ALL`, JGit and the CLI agree within 1% for every top author.

**Decisions**
- Use JGit for both history and blame, always with whitespace ignored.
- Later: honour `.git-blame-ignore-revs` so bulk reformat commits (e.g. Black) do not steal ownership.
  Check whether JGit supports this; if not, filter those commits ourselves or use the CLI for blame.
- Blame cost grows with file count and history depth: cache results and show progress for big repos.

## Running the spikes
```bash
cd spikes/treesitter-java
mvn -q compile dependency:build-classpath -Dmdep.outputFile=cp.txt
java -cp "target/classes;$(cat cp.txt)" spike.ParseTree <path-to-python-repo>      # add "cursor" to compare
java -cp "target/classes;$(cat cp.txt)" spike.ParsePython samples/sample.py

cd ../jgit-blame
mvn -q compile dependency:build-classpath -Dmdep.outputFile=cp.txt
java -cp "target/classes;$(cat cp.txt)" spike.BlameBench <path-to-git-repo>
```
(On Linux/macOS use `:` instead of `;` in the classpath.)
