# Evaluation

CodeAtlas ranks files and attributes ownership. This page asks whether those outputs are any good,
by comparing them with sources CodeAtlas does not use, and with baselines any beginner could write.

The method below was written and the repository list fixed **before** any results were produced, so the
measures could not be chosen to flatter the tool. Everything here is reproducible: see "Running it".

## What is compared

### Reading order
The reading order ranks the Python files a newcomer should read first
([docs/metrics.md](metrics.md#reading-order-v2)). It is compared with two independent answers.

**1. Files the project's own documentation points at.** Mentions are collected from README,
CONTRIBUTING, architecture, development, design and internals files, plus every prose page under
`docs/`. Both forms are matched: paths (`src/flask/app.py`, `cli.py`) and dotted module names as
Python docs write them (`flask.cli`, `:mod:`flask.app``). A mention counts only if exactly one ranked
file matches it, so ambiguous names such as `utils.py` are dropped. Changelogs and API reference
pages are excluded: the first mentions files because they changed, the second lists every module.
A repository needs at least 3 mentioned files to take part in this comparison.

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

To search for a better reading-order formula on the training half and check it on the held-out half:
```bash
java -cp "target/classes:$(cat cp.txt)" io.github.shrishaanth.codeatlas.eval.Tuner ../eval/features.json
```

To measure question-answering retrieval against an analysis already stored in the database:
```bash
java -cp "target/classes:$(cat cp.txt)" io.github.shrishaanth.codeatlas.eval.RetrievalEvalCommand \
    jdbc:postgresql://localhost:5432/codeatlas codeatlas codeatlas <analysis-id>
```

## Results

Run on 2026-09-20 over the 20 repositories in [eval/repos.txt](../eval/repos.txt);
raw numbers in [eval/results.json](../eval/results.json). Medians across repositories.

### What the first run said about reading order v1

v1 scored `0.5 * centrality + 0.3 * fanIn + 0.2 * churn`, where centrality was ordinary PageRank.

| Ground truth | v1 | size | fan-in | commits | random |
|---|---:|---:|---:|---:|---:|
| Documentation mentions (17 repos) | 0.64 | **0.64** | 0.56 | 0.61 | 0.52 |
| Newcomers' first commits (20 repos) | 0.57 | 0.57 | 0.54 | **0.62** | 0.49 |

**v1 was no better than sorting by file size, and worse than sorting by commit count.** It beat every
baseline in only 3 of 17 repositories on documentation and 2 of 20 on first commits. Qualitatively it
also put low-level shims first: `_compat.py` and `_winconsole.py` topped the list for click.

(The very first run found documentation mentions in only 5 of 20 repositories, because the extractor
looked at a few file names and only at paths ending in `.py`. It was widened to all narrative docs
and to dotted module names, which Python documentation actually uses, before any tuning. The v1
numbers above are from the widened extractor, so v1 and v2 are measured the same way.)

### Choosing v2: search on one half, measure on the other

Repositories alternate between a **training** half (10) and a **held-out** half (10), so both halves
mix sizes and organisations. About 3,700 weight combinations over churn, reach (reversed PageRank),
fan-in, size and a private-module penalty were scored on the training half only. The best was then
measured on the held-out half, which took no part in the search.

Held-out repositories, mean percentile:

| Formula | documentation | first commits |
|---|---:|---:|
| v1 (centrality 0.5, fan-in 0.3, churn 0.2) | 0.655 | 0.570 |
| **v2 (churn 0.55, reach 0.30, fan-in 0.15, private -0.20)** | **0.694** | 0.595 |
| baseline: size only | 0.671 | 0.567 |
| baseline: commits only | 0.668 | **0.600** |
| baseline: fan-in only | 0.620 | 0.538 |
| baseline: centrality only | 0.611 | 0.537 |
| baseline: reach only | 0.682 | 0.546 |

Two things stand out. **Ordinary PageRank centrality got zero weight in every good variant** and was
dropped: the most complicated signal was the least useful. And **the win over "sort by commit count"
is small** (0.694 vs 0.668 on documentation, and on first commits v2 is slightly behind). The first
commits measure favours churn by construction, since a file can only appear in a first commit if it
has commits, so the documentation measure is the more meaningful of the two.

Rounded, readable weights scored the same as the raw search result (0.694 / 0.595), so the readable
ones are what ship.

### Reading order v2, all 20 repositories

These include the repositories used for the search, so they flatter v2; the held-out table above is
the honest comparison.

| Ground truth | v2 | size | fan-in | commits | random |
|---|---:|---:|---:|---:|---:|
| Documentation mentions (17 repos) | **0.68** | 0.64 | 0.56 | 0.61 | 0.50 |
| Newcomers' first commits (20 repos) | 0.61 | 0.57 | 0.54 | **0.62** | 0.51 |

Qualitatively the top of the list changed for the better: click now leads with `core.py` and the
public `__init__.py` instead of `_compat.py`, and requests with `models.py`, `__init__.py` and
`sessions.py`.

### Ownership against CODEOWNERS

Only 2 of the 20 repositories have a CODEOWNERS file, so this says very little.

| Repo | Directories compared | Top owner matches | A top-3 owner matches |
|---|---:|---:|---:|
| psf/requests | 9 | 6 | 8 |
| tqdm/tqdm | 7 | 0 | 2 |
| **total** | 16 | **6 (38%)** | **10 (63%)** |

requests matches well; tqdm does not match at all at the top. Looking at why would be the next step:
CODEOWNERS records who is *responsible*, which for tqdm may be a maintainer who reviews rather than
writes. With two repositories, no conclusion is available either way.

### Speed
Analysis of a cached clone, on a laptop: 0.8 s for a 600-commit repository, 4-10 s for most,
29 s for the largest in the set (fastapi, 7,713 commits and 1,138 Python files). Cloning dominates
for anything over a few hundred commits.

### Question answering: can the search find a definition?

Retrieval is the part of question answering that can be measured without a language model. For symbols
the repository really contains, the harness asks "where is X defined?" and checks whether the chunk
holding that definition comes back. Symbol-name questions are the easy case, so read this as a floor.

| Repo | Symbols sampled | Definition in the top 8 | Definition ranked first |
|---|---:|---:|---:|
| pallets/itsdangerous | 59 | 59 (100%) | 40 (68%) |
| pallets/flask | 60 of 385 | 59 (98%) | 28 (47%) |

Both numbers come from `RetrievalEvalCommand` against a stored analysis, and both improved during this
milestone because the measurement found real faults:

- itsdangerous started at 93%: the "at most 3 chunks per file" rule, which spreads answers across
  files, was dropping the definition when sibling methods in the same file matched first. A chunk
  whose symbol the question names is now exempt from that rule.
- flask started at 83%: in a large repository the definition never reached the ranking step, because
  the database returned the 60 best text matches and the definition was not among them. Chunks whose
  symbol the question names are now fetched first.

"Ranked first" stays near half for flask and two thirds for itsdangerous, and that is fair: several
classes define `save_session` or `dumps`, so "where is dumps defined" has no single right answer
without naming the class. The one remaining miss on flask is `EnvironBuilder.__init__`, one of dozens
of `__init__` methods.

**Not measured:** whether the prose a model writes is *correct*. What is checked automatically is
where each citation points (`exact`, `inside`, `unsupported`), not whether the sentence fairly
summarises the code it cites. One observation worth recording: given an excerpt of 190 lines, the
model invented narrower line numbers inside it that pointed at the wrong code, which is why classes
are now split into methods and why citations that are merely "inside" an excerpt are labelled as
such instead of being called verified.

## What this evaluation does and does not show

**It shows:**
- Reading order v2 beats every simple baseline on the documentation measure on repositories that took
  no part in choosing it. The margin is small.
- The PageRank centrality idea, which was the most complex part of v1, did not work and was removed.
- Ownership matched CODEOWNERS for the top owner in 6 of 16 directories, across only 2 repositories.
- Search finds the definition of a named symbol in the top 8 results in 98-100% of cases on two
  repositories.

**It does not show:**
- That the ranking is *useful*. No one was asked to learn a codebase with and without it. Mean
  percentile against documentation mentions is a proxy, not a measure of onboarding.
- That it works outside well-maintained open-source Python libraries.
- Anything about the findings, the change coupling or the hotspots: those are not evaluated here.
- Whether answers written by a language model are correct or useful: only their citations are checked,
  and only for where they point.

