# Demo video script (about 80 seconds)

For a recruiter or an interviewer who will watch without sound as often as with it. Record at
1500×1000, dark theme, one browser tab, no terminal. Keep the cursor still while talking.

| Time | On screen | Said |
|---|---|---|
| 0:00–0:08 | Start page, paste `https://github.com/pallets/flask`, press Analyze | "CodeAtlas takes a repository you have never seen and tells you how to start reading it." |
| 0:08–0:15 | Progress bar running through parse, history, blame | "It clones it, parses every Python file with Tree-sitter, and walks the whole git history — five and a half thousand commits here." |
| 0:15–0:30 | Reading order tab; hover the top entry so the score parts show | "This is the reading order: files ranked by how often they change, how much of the codebase they pull in, and how many files import them. The weights were not guessed — they were tuned on ten repositories and checked on ten others it never saw." |
| 0:30–0:40 | Click "How this is computed and how well it works", land on evaluation.md | "Against sorting by file size or by commit count, it wins by a small margin: 0.69 against 0.67. The first version lost, and measuring it is why it was replaced." |
| 0:40–0:52 | Findings tab; open the import-cycle finding | "Findings are concrete and come with evidence: this import cycle lists the exact three imports that close it, each linked to the line on GitHub." |
| 0:52–1:02 | Ownership tab; point at a bus-factor-1 row | "Ownership comes from git blame with whitespace ignored, and one person's several email addresses merged. Bus factor one means one person holds most of that directory." |
| 1:02–1:20 | Ask tab; ask "How does a signed value get verified when it is loaded?"; point at the citation line | "And you can ask questions. The model only ever sees the retrieved excerpts, and every line number it cites is checked against them — exact, inside an excerpt, or unsupported. Here it says outright that the code it would need is not in the excerpts, instead of inventing it." |
| 1:20–1:25 | Scroll to the matched code, then the URL bar | "Live at the link below. Everything you saw is computed from the code and the history, so every number traces back to a file, a line or a commit." |

Fallback if the deployed backend is asleep: open a demo report instead
(`/demo/flask`), and say the analysis was pre-computed. The Ask tab needs a live analysis, so record
that part locally.
