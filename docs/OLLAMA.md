# Running the plugin with a local model (Ollama) — detailed guide

> This branch is only for people who want to use a **free model running on their
> own machine** (Ollama) instead of a Claude subscription. If you use Claude
> normally, **skip this** — just follow `README.md` on the `main` branch.

Read the **"How far it actually goes"** section at the end before expecting too much.

---

## 0. Why you need a proxy — you can't plug Ollama in directly

The plugin's agent is really the **Claude Code CLI** (via `claude-agent-sdk`).
That CLI only speaks the **Anthropic Messages API** (`/v1/messages`).

Ollama, on the other hand, exposes an **OpenAI-compatible API**
(`/v1/chat/completions`) — a different format, especially for tool calls
(tool-use). So you need a **translation proxy** in the middle to convert
Anthropic ⇄ OpenAI. Here we use
[`claude-code-router`](https://github.com/musistudio/claude-code-router)
because it is built exactly for this.

```
GAMA plugin ──> ide_agent.py ──> Claude Code CLI
                                     │  (Anthropic /v1/messages)
                                     ▼
                          claude-code-router  (127.0.0.1:3456)
                                     │  (OpenAI /v1/chat/completions)
                                     ▼
                               Ollama (127.0.0.1:11434)
                                     │
                                     ▼
                          local model: qwen2.5-coder:7b
```

---

## 1. Install Ollama and pull a model

1. Download Ollama: <https://ollama.com/download> → install it like any app.
2. Open a terminal and pull a model that is **good at tool-calling** (required —
   do not grab a plain chat model):

   ```bash
   ollama pull qwen2.5-coder:7b
   ```

   - Weak machine / little VRAM: stick with `7b`.
   - Strong machine (≥16GB VRAM): `qwen2.5-coder:14b` or `:32b` will make fewer
     tool-calling mistakes.
3. Check that Ollama is running (by default it runs in the background on port
   `11434`):

   ```bash
   ollama list
   ```

---

## 2. Install claude-code-router (the proxy)

You need **Node.js** (get the LTS build at <https://nodejs.org>). Then:

```bash
npm install -g @musistudio/claude-code-router
```

Create the config file `~/.claude-code-router/config.json`
(on Windows: `C:\Users\<name>\.claude-code-router\config.json`):

```json
{
  "Providers": [
    {
      "name": "ollama",
      "api_base_url": "http://127.0.0.1:11434/v1/chat/completions",
      "api_key": "ollama",
      "models": ["qwen2.5-coder:7b"]
    }
  ],
  "Router": {
    "default": "ollama,qwen2.5-coder:7b"
  }
}
```

Start the proxy (keep this terminal window open the whole time you use the plugin):

```bash
ccr start
```

By default it listens on `http://127.0.0.1:3456`. If it uses a different port,
read the log printed by `ccr start` and adjust `base_url` in step 3 to match.

---

## 3. Point the plugin at the proxy

Edit `~/.gama-claude.properties` (the file that configures the plugin). The four
lines that matter:

```properties
# Local model — must match exactly what you pulled in step 1
model=qwen2.5-coder:7b

# Point at claude-code-router instead of api.anthropic.com
base_url=http://127.0.0.1:3456

# A local proxy needs no real token, but the KEY must be non-empty
key=dummy

# IMPORTANT: leave oauth_token EMPTY, otherwise the agent prefers Claude login
oauth_token=
```

Keep `python=` and `script=` as they were.

> **How it works:** `ide_agent.py` reads `base_url=` from this file *before*
> defaulting to `api.anthropic.com`. Leaving `oauth_token` empty makes the plugin
> set `CLAUDE_CODE_OAUTH_TOKEN=""` (empty → treated as absent), so `key=dummy`
> gets used as the `ANTHROPIC_API_KEY` sent to the proxy.

---

## 4. Try it

1. `ccr start` is running (step 2), Ollama is running (step 1).
2. Open GAMA → the **Claude Chat** view → type "hello".
3. On the first message Ollama loads the model into RAM/VRAM, so the **reply
   takes a few dozen seconds** — that's normal.

To **switch back to Claude**: remove the `base_url=` line, put your subscription
token back in `oauth_token=`, set `model=claude-opus-4-8`, then restart GAMA.

---

## 5. Quick troubleshooting

| Symptom | Common cause |
|---|---|
| Chat shows a connection error / 404 | `ccr start` isn't running, or `base_url` has the wrong port |
| "model not found" | `model=` doesn't match `ollama list`; must be identical, including `:7b` |
| Asks you to log in / 401 error | Forgot to leave `oauth_token` empty, or `key=` is empty |
| Replies, but **won't edit files / doesn't call tools** | Model too small — see the section below |
| Very slow | Model bigger than your RAM/VRAM → Ollama spills to CPU; use a smaller model |

Check the agent log at `%TEMP%\gama_claude_agent.log` (Windows) for details.

---

## 6. How far it actually goes (please read)

This plugin is a **working agent**, not just a chatbot: it calls many tools
(validate GAML, run headless, read the console, the live-simulation bridge, edit
files + undo…) in a multi-step loop.

- Local **7–8B models are fairly poor at tool-calling** — they often pass wrong
  arguments or forget to call a tool. So **Q&A / explaining GAML is fine**, but
  **don't expect it to diagnose and fix a model on its own like Claude Opus**.
- Want it better: move up to `qwen2.5-coder:14b`/`32b` if your machine can handle
  it. Still weaker than Opus.
- All the "premium" features of the plugin (auto-fix, live simulation, snapshots)
  were designed and tested against Claude. Ollama is a "when you're offline / have
  no subscription" fallback, not an equal replacement.

If you just want quality, use Claude as described in `README.md`.
