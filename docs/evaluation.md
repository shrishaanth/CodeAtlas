# Evaluation

CodeAtlas ranks files and attributes ownership. This page asks whether those outputs are any good,
by comparing them with sources CodeAtlas does not use, and with baselines any beginner could write.

The method below was written and the repository list fixed **before** any results were produced, so the
measures could not be chosen to flatter the tool. Everything here is reproducible: see "Running it".

## What is compared

### Reading order
The reading order ranks the Python files a newcomer should read first
([docs/metrics.md](metrics.md#reading-order-v1)). It is compared with two independent answers.

**1. Files the project's own documentation points at.** Path-like mentions (`src/flask/app.py`,
`cli.py`) are collected from README, CONTRIBUTING, architecture, development, design and internals
files, plus the same names under `docs/`. A mention counts only if exactly one ranked file matches it,
so ambiguous names such as `utils.py` are dropped. Changelogs are excluded: they mention files
because they changed, not because they matter.

**2. Files newcomers actually started with.** For every person in the history, the files touched in
their *first* commit. Commits touching more than 20 files are skipped (an import of an existing
codebase says nothing about where to start). This is behavioural evidence: it says where people did
start, not where they should have.

### Ownership
Blame-based ownership ([docs/metrics.md](metrics.md#ownership)) is compared with `CODEOWNERS`, the
project's own statement of who is responsible for which directory. CODEOWNERS lists GitHub handles and
git history holds names and emails, so a handle is linked to a person when the handle equals an email's
local part, appears in a GitHub noreply address, or matches the person's name with punctuation removed.
Only directories where at least one listed handle can be linked to *someone* in the history are
counted: a directory owned by a team, or by people who never committed, cannot be judged this way.

## Measures
- **Mean percentile**: the average position of the ground-truth files in a ranking, where 1.0 means
  they are all at the very top and 0.0 all at the bottom. **Random ordering scores 0.5**, and that is
  the number to beat. Percentile is used rather than "how many are in the top 10" because repositories
  differ hugely in size; hits in the top 10 are reported as well.
- **Ownership**: the share of comparable directories where the top owner by blame is a person
  CODEOWNERS names, and the share where any of the top three owners is.

## Baselines
The same ground truth is scored against rankings a beginner would write in an afternoon:

| Baseline | Ranking |
|---|---|
| `size` | most lines first |
| `fan-in` | imported by the most non-test files first |
| `commits` | most commits first |
| `random` | shuffled (seed 42); expected percentile 0.5 |

If CodeAtlas does not beat these, that is the result, and it is reported as such.

## Repositories
The 20 Python projects in [eval/repos.txt](../eval/repos.txt), spanning small libraries to large
frameworks and several organisations. They were picked for variety and for having documentation a
newcomer would read, before any measurement was taken.

## Honest limits of this evaluation
- **Documentation mentions are a weak proxy.** Docs mention files for many reasons, and most projects
  mention only a handful. Repositories with too few mentions are excluded from that comparison, and
  the count is shown per repository.
- **First commits are noisy.** Many first commits fix a typo in a docs file or a test, which says
  little about what to read.
- **CODEOWNERS is rare and coarse.** Few projects have one, entries often name teams, and it records
  who is *responsible*, which is not always who wrote the code.
- **These are libraries and frameworks**, mostly Python, mostly well maintained. Results may not carry
  over to private application code, which is what most readers would point CodeAtlas at.
- **One tool, one snapshot.** Each repository is measured at its current `HEAD`, once.
- No statistical significance testing: with 20 repositories, medians and per-repository numbers are
  reported so the spread is visible.

## Running it
```bash
cd backend
./mvnw -q compile dependency:build-classpath -Dmdep.outputFile=cp.txt
java -cp "target/classes:$(cat cp.txt)" io.github.shrishaanth.codeatlas.eval.EvalCommand \
    ../eval/repos.txt ../eval/results.json /tmp/codeatlas-eval
```
(On Windows use `;` in the classpath.) Clones are cached in the last directory, so a rerun is fast.
The command writes `eval/results.json` and prints the tables below.

## Results

*Filled in by the run; see the section below.*
