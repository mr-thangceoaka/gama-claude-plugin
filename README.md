# Claude in GAMA

**A full Claude coding agent that lives inside the [GAMA Platform](https://gama-platform.org/) IDE — and inside your running simulation.**

This is not a chat window bolted onto an editor. The agent sees what the IDE
sees (compiler errors with exact lines, the structure of every model in your
project, the console) **and what the simulation sees**: while your experiment
is running it can read agent state, change parameters, create or kill agents,
step the scheduler cycle by cycle, and *look at* the displays — the same live
world you are watching.

> ⚠️ If you tried an early version: this is a different tool now. The first
> release was "chat + error markers". The current release is a project-aware
> agent with semantic navigation, a run-and-verify loop, undo-able edits, and
> a live bridge into the running simulation. Full history in the
> [changelog](#changelog) below.

![Fix session inside GAMA](docs/screenshot-fix-session.png)

## Why not just use Cursor?

GAMA is a *simulation* platform. The work loop is not
`write code -> compile -> ship`; it is
`write model -> compile -> run -> watch emergent behaviour -> adjust`.
A generic AI IDE stops at the file level: it can never tell you *why the
epidemic dies out at cycle 300*, because it can't see cycle 300.

This plugin closes the whole loop, inside GAMA:

| A generic AI IDE | Claude in GAMA |
|---|---|
| Sees files | Sees files **+ live Xtext diagnostics + project semantic index** |
| Greps for symbols | `find_gaml_symbol` over a cached index of every species/action/reflex/experiment |
| Runs shell commands | **No shell.** Purpose-built tools: compile-check, headless runs, live-sim bridge |
| Can't run your GUI model | Runs *any* experiment headless, reads monitors per step, **reads the display PNGs** |
| Can't touch a running program | **Pauses, steps, inspects, and mutates the live simulation, and screenshots its displays** |
| Free-form edits | Every edit shows a real diff card; every applied edit is snapshotted and undo-able |

## What it does

### 🔴 Live simulation copilot (v0.5)
Launch any experiment and just talk about it. While it runs, the agent can:

- **`sim_status`** — cycle, paused/running, every parameter value, every
  monitor value, list of displays. A one-line live status is also attached to
  each message you send, so the agent always knows a sim is up.
- **`sim_eval`** — evaluate *any* GAML against the live world, through the
  same engine as GAMA's Interactive Console. Expressions read state
  (`length(prey)`, `prey mean_of each.energy`); statements **change** it
  (`ask prey { energy <- 1.0; }`, `create predator number: 5;`) and the
  displays refresh instantly.
- **`sim_control`** — pause, resume, step N cycles, step back, reload.
- **`sim_snapshot`** — capture each display of the running experiment to PNG
  and *read the image*, seeing exactly what you see.

So you can ask things like:

> *"Why did all the predators die around cycle 200? Pause it and check."*
> *"Drop infection_rate to 0.1 and step 50 cycles — does the curve flatten?"*
> *"Look at the map display. Why are agents clustering bottom-left?"*

### 🧭 Project intelligence
- Every `.gaml` in the project is parsed into a **semantic index** (cached by
  mtime). Each message carries a compact **project map**: files, species,
  experiments, displays — with line numbers.
- `gaml_outline` returns one file's structure far cheaper than reading it;
  `find_gaml_symbol` finds definitions and reference counts across files.

### 🩺 Live diagnostics
Every save triggers GAMA's own Xtext validation; the plugin turns the markers
into JSON and attaches the active project's errors to your next message.
Exact files, exact lines, zero copy-pasting. "Ask Claude" on any error line
(right-click, toolbar, or Ctrl+Alt+C).

### ▶️ Run-and-verify loop
The agent doesn't guess whether its fix works:
- `validate_gaml_syntax` compile-checks a model headlessly.
- `run_experiment_headless` runs **any** experiment (gui or batch) with a step
  cap, returns recorded monitor values per step **plus display snapshot PNGs
  it can look at**.
- `read_ide_console` tails the IDE console (refreshed live during a turn).

Edit → validate → run small → look at outputs → fix → repeat. Cursor-style,
but the "test" is a simulation.

### 🛡️ Safe edits, by construction
- Guardrails live in **code, not prompts**: read/edit hard-limited to the
  project of the open model (or a folder you pick). No shell access, ever.
- Every proposed edit shows an approval card with a real unified diff
  (`auto_approve=true` to skip).
- Every applied edit is snapshotted first: **Undo** on the card, **History**
  in the header with per-entry undo. The IDE re-validates after each undo.

### 🧰 IDE conveniences
Window snapshot button (attach a screenshot of GAMA to your message), new
projects auto-imported into the navigator, context-folder override, one-click
fresh session, markdown chat with tool chips.

## Architecture

```
GAMA (Eclipse RCP + Xtext)
└─ gama.ui.claude (this plugin, Java)
   ├─ ChatView       SWT Browser chat UI + JSON-lines stdio to the agent
   ├─ MarkerBridge   IMarker -> JSON, auto-rescan on save/tab-switch
   ├─ ConsoleBridge  read-only console mirror, refreshed every 2s in a turn
   ├─ SimBridge      live bridge into the RUNNING experiment (gama.core):
   │                   status / pause / resume / step / reload,
   │                   GAML eval (Interactive-Console engine, statements too),
   │                   per-display PNG capture
   └─ AgentHost      spawns agent/ide_agent.py
        └─ Claude Agent SDK -> Claude API
           ├─ gaml-tools   gaml_outline / find_gaml_symbol / project_map
           │                 (mtime-cached workspace index)
           ├─ gama-tools   validate / run headless (any experiment) /
           │                 read outputs + snapshots / read IDE console
           ├─ gama-sim     sim_status / sim_eval / sim_control / sim_snapshot
           │                 (RPC over stdio -> SimBridge in the IDE process)
           └─ edit_history pre-edit snapshots, diff cards, undo journal
```

The sim tools are an RPC channel: the Python agent emits
`{"type":"sim_cmd",...}`, the Java plugin executes it on the GAMA runtime and
answers with `sim_reply`. Same trusted process, no extra ports, no server.

## Install

Requirements: GAMA 2025.x (its bundled JDK is used to compile), Python ≥ 3.10
with `claude-agent-sdk`, Node.js ≥ 20, bash (Git Bash on Windows).

```bash
git clone https://github.com/mr-thangceoaka/gama-claude-plugin
cd gama-claude-plugin

# 1. build + install into GAMA (auto-detects the install; else set GAMA_DIR)
#    GAMA must be closed, or the old jar can't be replaced
bash build.sh

# 2. python side
python -m venv .venv && . .venv/Scripts/activate   # or bin/activate
pip install claude-agent-sdk

# 3. config: copy gama-claude.properties.example to ~/.gama-claude.properties
#    fill python=, script=, and ONE of oauth_token= / key=

# 4. start GAMA -> the "Claude Chat" view opens by itself
```

Auth: `oauth_token=` (from `claude setup-token`, uses your Claude
subscription) or `key=` (API credits). The agent runs with an isolated
`CLAUDE_CONFIG_DIR`, so proxies/model overrides in your own
`~/.claude/settings.json` cannot hijack it.

Uninstall: delete the `gama.ui.claude,...` line from
`<GAMA>/configuration/org.eclipse.equinox.simpleconfigurator/bundles.info` and
remove the jar from `<GAMA>/plugins/` (a bundles.info backup is written on
first install).

## Why not headless-only?

GAMA's `gama-headless -validate` doesn't check *your* file (built-in library
only); `-xml` does but costs a full JVM start per check and reports no line
numbers. The IDE's Xtext markers are instant and exact — the plugin hands them
over. Same story at runtime: headless re-runs are great for reproducible
verification (the agent uses them), but only the live bridge lets you ask
about *the* simulation you're watching, with its exact random seed and
history.

## Changelog

<details>
<summary>Milestones M0 → M8 (v0.5.0)</summary>

- **M0–M2** view + marker scan; live diagnostics JSON with library-noise
  filter; chat wired to the agent; auto refresh + revalidate after edits;
  diagnostics scoped to the active project.
- **M3/M3.5** "Ask Claude" on error lines; approval cards with diff preview;
  Stop button; auto-refresh diagnostics; editor context menu + Ctrl+Alt+C;
  chat UI overhaul (markdown, tool chips, typing indicator).
- **M4** window snapshots the agent can Read to see displays and charts.
- **M5 (v0.2.0)** project-wide context: project_root scope, Glob/Grep-first
  prompting, "Context folder" override, Clear-conversation, installer removes
  stale plugin versions.
- **M6 (v0.3.0)** agent-driven verify loop: headless validate/run tools,
  auto-import of new projects into the navigator, workspace-root fallback so
  "create a new project" works from an empty chat.
- **M7 (v0.4.0)** the grand overhaul: workspace semantic index + project map
  on every message; `gaml_outline` / `find_gaml_symbol`; console mirror +
  `read_ide_console`; `run_experiment_headless` for *any* experiment with
  monitors-per-step + snapshot PNGs; unified-diff approval cards; edit
  history with per-entry Undo. Read-scope enforced via PreToolUse hooks.
- **M8 (v0.5.0)** live simulation bridge: `sim_status` / `sim_eval`
  (expressions *and* world-mutating statements, Interactive-Console engine) /
  `sim_control` (pause/resume/step/reload) / `sim_snapshot` (per-display PNG);
  live sim status attached to every message; hardened JSON escaping on the
  stdio protocol.

</details>

## License

MIT
