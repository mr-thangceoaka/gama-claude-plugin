"""
IDE agent cho plugin gama.ui.claude - noi chuyen voi Java qua stdio, JSON lines.

vao  (stdin) : {"type":"chat","text","active_file","project_root","console",
                "workspace_summary","diagnostics":[...],"snapshot"?}
               {"type":"interrupt"}
               {"type":"permission_reply","id":N,"allow":true/false}
               {"type":"undo","seq":N}        <- M7: hoan tac 1 edit da apply
               {"type":"history"}             <- M7: xin danh sach edit
ra   (stdout): {"type":"text"|"tool"|"done"|"error"|"info"}
               {"type":"permission","id":N,"file","tool","diff"}  <- the duyet Edit
               {"type":"applied","id":N,"seq":M,"file"}           <- edit da ap dung
               {"type":"history","items":[...]}
               {"type":"undo_done","seq":N,"text"}

M3: dispatcher chay song song voi luot agent -> Stop va duyet diff hoat dong
ngay giua chung luot. Edit/Write mac dinh phai duoc user "Ap dung" trong chat
(GAMA_CLAUDE_AUTO_APPROVE=true de bo qua buoc duyet).
M7: workspace index + GAML semantic tools + runtime observation + edit
history/undo (xem gaml_index / semantic_tools / gama_tools / edit_history).
"""

import asyncio
import difflib
import itertools
import json
import os
import sys

sys.stdout.reconfigure(encoding="utf-8")
sys.stderr.reconfigure(encoding="utf-8")

# ── Isolate CLI config: a user-level ~/.claude/settings.json (custom proxy/BASE_URL) must not hijack the agent ──
_cfg = os.path.join(os.path.expanduser("~"), ".gama-claude-config")
os.makedirs(_cfg, exist_ok=True)
os.environ["CLAUDE_CONFIG_DIR"] = _cfg
if not os.environ.get("ANTHROPIC_BASE_URL"):
    os.environ["ANTHROPIC_BASE_URL"] = "https://api.anthropic.com"
for _k in ("ANTHROPIC_AUTH_TOKEN", "ANTHROPIC_API_KEY",
           "ANTHROPIC_DEFAULT_SONNET_MODEL", "ANTHROPIC_DEFAULT_OPUS_MODEL",
           "ANTHROPIC_DEFAULT_HAIKU_MODEL"):
    if not os.environ.get(_k):
        os.environ.pop(_k, None)
# OAuth token is the configured auth: a leftover AUTH_TOKEN/API_KEY from the
# parent shell (stale proxy) would override it inside the CLI -> drop them
if os.environ.get("CLAUDE_CODE_OAUTH_TOKEN"):
    os.environ.pop("ANTHROPIC_AUTH_TOKEN", None)
    os.environ.pop("ANTHROPIC_API_KEY", None)

from claude_agent_sdk import (
    AssistantMessage,
    ClaudeAgentOptions,
    ClaudeSDKClient,
    HookMatcher,
    PermissionResultAllow,
    PermissionResultDeny,
    ResultMessage,
    TextBlock,
    ToolUseBlock,
)

import edit_history
import gaml_index
import scope
from gama_tools import gama_tools_server
from semantic_tools import semantic_tools_server

AUTO_APPROVE = os.environ.get("GAMA_CLAUDE_AUTO_APPROVE", "false").lower() == "true"
MODEL = os.environ.get("GAMA_CLAUDE_MODEL", "").strip() or "claude-opus-4-8"
PENDING = {}          # id -> Future[bool] cho cac the duyet dang cho
_ids = itertools.count(1)


def emit(obj):
    print(json.dumps(obj, ensure_ascii=False), flush=True)


def _diff_preview(tool, input_data):
    """M7: unified diff that vs noi dung file hien tai (difflib), khong con
    dan '-old/+new' tho. Fallback ve preview don gian khi khong doc duoc."""
    fp = input_data.get("file_path", "")
    old_text = None
    try:
        if fp and os.path.isfile(fp):
            with open(fp, encoding="utf-8", errors="replace") as f:
                old_text = f.read()
    except OSError:
        old_text = None

    if tool == "Write":
        new_text = input_data.get("content", "")
        if old_text is None:  # file moi
            lines = new_text.splitlines()
            head = [f"(new file, {len(lines)} lines)"]
            head += ["+ " + l for l in lines[:40]]
            if len(lines) > 40:
                head.append(f"... +{len(lines) - 40} more lines")
            return "\n".join(head)[:4000]
    else:  # Edit
        old_s = input_data.get("old_string", "")
        new_s = input_data.get("new_string", "")
        if old_text is None or (old_s and old_s not in old_text):
            out = ["- " + l for l in (old_s or "").splitlines()[:25]]
            out += ["+ " + l for l in (new_s or "").splitlines()[:25]]
            return "\n".join(out)[:4000]
        if input_data.get("replace_all"):
            new_text = old_text.replace(old_s, new_s)
        else:
            new_text = old_text.replace(old_s, new_s, 1)

    diff = difflib.unified_diff(
        old_text.splitlines(), new_text.splitlines(),
        fromfile="before", tofile="after", lineterm="", n=3)
    out = list(diff)[2:]  # bo 2 dong header ---/+++
    if not out:
        return "(no textual change)"
    return "\n".join(out)[:4000]


def _read_denial(tool_name, input_data):
    """Ly do chan neu tool doc cham ra ngoai context root, None = cho qua.
    Read-only tools KHONG di qua can_use_tool o permission_mode default,
    nen phai chan bang PreToolUse hook."""
    p = input_data.get("file_path") or input_data.get("path") or ""
    if tool_name == "Read" and p and os.path.abspath(p) in scope.ALLOWED_READ_EXTRA:
        return None  # snapshot PNG / headless output da duoc whitelist
    if scope.in_root(p):
        return None
    root = scope.ALLOWED_ROOT["path"] or "(no folder in scope yet)"
    if not p:
        return f"Pass an explicit path inside the context folder: {root}"
    return (f"Reading outside the context folder is blocked: {root}. "
            "Ask the user to change it via the view's 'Context folder' button if needed.")


async def read_scope_hook(input_data, tool_use_id, context):
    reason = _read_denial(input_data.get("tool_name", ""),
                          input_data.get("tool_input") or {})
    if reason is None:
        return {}
    return {"hookSpecificOutput": {"hookEventName": "PreToolUse",
                                   "permissionDecision": "deny",
                                   "permissionDecisionReason": reason}}


async def can_use_tool(tool_name, input_data, context):
    if tool_name in ("Read", "Glob", "Grep"):
        # phong ho: neu co di qua day thi ap cung mot luat voi hook
        reason = _read_denial(tool_name, input_data)
        return PermissionResultAllow() if reason is None else PermissionResultDeny(message=reason)
    if tool_name.startswith("mcp__gama-tools__") or tool_name.startswith("mcp__gaml-tools__"):
        return PermissionResultAllow()
    if tool_name in ("Write", "Edit"):
        fp = os.path.abspath(input_data.get("file_path", ""))
        root = scope.ALLOWED_ROOT["path"]
        if not root or os.path.commonpath([fp, root]) != root:
            return PermissionResultDeny(message=f"Edits are only allowed inside: {root}")
        if AUTO_APPROVE:
            seq = edit_history.record(tool_name, fp)
            emit({"type": "applied", "id": 0, "seq": seq, "file": fp})
            return PermissionResultAllow()
        # hien the diff trong chat, cho user bam Ap dung / Tu choi
        pid = next(_ids)
        fut = asyncio.get_event_loop().create_future()
        PENDING[pid] = fut
        emit({"type": "permission", "id": pid, "file": fp, "tool": tool_name,
              "diff": _diff_preview(tool_name, input_data)})
        try:
            ok = await asyncio.wait_for(fut, timeout=600)
        except asyncio.TimeoutError:
            ok = False
        finally:
            PENDING.pop(pid, None)
        if not ok:
            return PermissionResultDeny(message="User rejected this change in chat.")
        # M7: chup snapshot TRUOC khi SDK ap dung edit -> Undo/History dung duoc
        seq = edit_history.record(tool_name, fp)
        emit({"type": "applied", "id": pid, "seq": seq, "file": fp})
        return PermissionResultAllow()
    return PermissionResultDeny(message=f"Tool '{tool_name}' is blocked in IDE mode.")


SYSTEM_HINT = (
    "You are a GAML/GAMA assistant embedded inside the GAMA IDE via a chat panel. "
    "Each user message may include the active .gaml file path, the project_root "
    "folder, a PROJECT MAP (every .gaml in the project with its species/"
    "experiments/displays and line numbers, from the plugin's semantic index), "
    "the tail of the IDE console, and live compiler diagnostics (from the IDE's "
    "Xtext validation - authoritative, exact lines). "
    "Navigate structure cheaply: the map is already in the message; for one "
    "file's full structure call gaml_outline (much cheaper than Read), to find "
    "where something is defined/used across files call find_gaml_symbol, and "
    "only Read the line ranges you actually need. Read/Glob/Grep are "
    "HARD-LIMITED to project_root - do not try to browse other projects or the "
    "GAMA install; if you genuinely need something outside, ask the user to "
    "widen the scope via the 'Context folder' toolbar button. "
    "Use Edit to fix code. Edits show an approval card; if one is rejected, "
    "respect it and propose an alternative instead of retrying the same "
    "change. Every applied edit is snapshotted - the user can Undo it from "
    "the card or the History button, so never re-apply an edit the user "
    "undid without asking. If a Write/Edit is DENIED by policy, say so "
    "plainly - NEVER claim you changed a file when the call failed. After "
    "edits the IDE re-validates and the NEXT message carries fresh "
    "diagnostics - don't guess whether a fix compiled. "
    "You have NO shell, but you can OBSERVE runtime behaviour: "
    "validate_gaml_syntax(gaml, experiment) compile-checks a model; "
    "run_experiment_headless(gaml, experiment, final_step) actually runs it "
    "(gui or batch) and returns monitor values per step plus display-snapshot "
    "PNG paths you can Read to SEE the result - use it to verify behaviour, "
    "with a modest final_step (50-500); run_gama_headless runs type:batch "
    "experiments; read_ide_console tails the IDE console (what the user's own "
    "GUI run printed). The verify loop is: edit -> validate -> run small -> "
    "look at outputs -> fix -> repeat. "
    "A message may include a path to a window screenshot - Read it to "
    "visually inspect displays and charts. New project folders you Write "
    "inside the workspace are auto-imported into the navigator after your "
    "turn. Reply in the user's language, keep answers short, cite line numbers."
)


def build_prompt(msg):
    parts = [msg.get("text", "")]
    af = msg.get("active_file")
    if af:
        parts.append(f"\n\n[context] active_file: {af}")
    pr = msg.get("project_root")
    if pr:
        parts.append(f"[context] project_root: {pr}")
        pmap = gaml_index.project_map(pr)
        if pmap:
            parts.append("[context] project map (semantic index, cached; "
                         "L = line numbers):\n" + pmap)
    ws = msg.get("workspace_summary")
    if ws:
        parts.append(f"[context] workspace: {ws}")
    con = (msg.get("console") or "").strip()
    if con:
        parts.append("[context] GAMA console tail (runtime output of the "
                     "user's last run):\n" + con)
    snap = msg.get("snapshot")
    if snap:
        parts.append(f"[context] fresh screenshot of the whole GAMA window: {snap}"
                     " - use the Read tool on this image file to SEE the running"
                     " simulation (displays, charts, map)")
    diags = msg.get("diagnostics") or []
    if diags:
        parts.append("[context] diagnostics of the ACTIVE PROJECT only (live from IDE):")
        for d in diags[:80]:
            parts.append(f"  {d.get('severity','?')} {d.get('file','?')}:{d.get('line','?')} {d.get('message','')}")
    return "\n".join(parts)


async def stdin_reader(queue):
    loop = asyncio.get_event_loop()
    while True:
        line = await loop.run_in_executor(None, sys.stdin.readline)
        if not line:
            await queue.put(None)
            return
        line = line.strip()
        if line:
            await queue.put(line)


async def run_turn(client, msg):
    try:
        await client.query(build_prompt(msg))
        async for m in client.receive_response():
            if isinstance(m, AssistantMessage):
                for block in m.content:
                    if isinstance(block, TextBlock):
                        emit({"type": "text", "text": block.text})
                    elif isinstance(block, ToolUseBlock):
                        # "mcp__gama-tools__validate_gaml_syntax" -> "validate_gaml_syntax"
                        emit({"type": "tool", "name": block.name.split("__")[-1]})
            elif isinstance(m, ResultMessage):
                emit({"type": "done"})
    except Exception as e:
        emit({"type": "error", "text": f"Agent error: {e}"})
        emit({"type": "done"})


async def main():
    options = ClaudeAgentOptions(
        model=MODEL,
        mcp_servers={"gama-tools": gama_tools_server,
                     "gaml-tools": semantic_tools_server},
        allowed_tools=["Read", "Grep", "Glob", "Edit", "Write",
                       "mcp__gama-tools__validate_gaml_syntax",
                       "mcp__gama-tools__run_gama_headless",
                       "mcp__gama-tools__run_experiment_headless",
                       "mcp__gama-tools__read_ide_console",
                       "mcp__gaml-tools__gaml_outline",
                       "mcp__gaml-tools__find_gaml_symbol",
                       "mcp__gaml-tools__project_map"],
        permission_mode="default",
        can_use_tool=can_use_tool,
        hooks={"PreToolUse": [HookMatcher(matcher="Read", hooks=[read_scope_hook]),
                              HookMatcher(matcher="Glob", hooks=[read_scope_hook]),
                              HookMatcher(matcher="Grep", hooks=[read_scope_hook])]},
        system_prompt={"type": "preset", "preset": "claude_code", "append": SYSTEM_HINT},
        max_turns=40,
    )
    queue = asyncio.Queue()
    asyncio.get_event_loop().create_task(stdin_reader(queue))
    turn = None
    try:
        async with ClaudeSDKClient(options=options) as client:
            # info, KHONG kem "done": message dau cua user den truoc loi chao nay,
            # mot "done" o day se tat nham nut Stop + typing indicator cua luot dang chay
            emit({"type": "info", "text": "Agent ready - ask about your GAML model."})
            while True:
                item = await queue.get()
                if item is None:
                    # stdin dong: cho luot dang chay xong roi moi thoat
                    if turn and not turn.done():
                        await turn
                    break
                try:
                    msg = json.loads(item)
                except json.JSONDecodeError as e:
                    emit({"type": "error", "text": f"Bad JSON from plugin: {e}"})
                    emit({"type": "done"})
                    continue
                t = msg.get("type")
                if t == "chat":
                    if turn and not turn.done():
                        emit({"type": "error", "text": "Previous turn still running - press Stop to interrupt."})
                        continue
                    # pham vi Edit/Write: ca project (hoac folder user chon);
                    # khong co thi lui ve folder cua file dang mo nhu cu
                    pr = msg.get("project_root") or ""
                    af = msg.get("active_file") or ""
                    if pr:
                        scope.ALLOWED_ROOT["path"] = os.path.abspath(pr)
                    elif af:
                        scope.ALLOWED_ROOT["path"] = os.path.dirname(os.path.abspath(af))
                    snap = msg.get("snapshot")
                    if snap:
                        scope.allow_read(snap)
                    turn = asyncio.create_task(run_turn(client, msg))
                elif t == "interrupt":
                    if turn and not turn.done():
                        try:
                            await client.interrupt()
                        except Exception as e:
                            emit({"type": "error", "text": f"Could not interrupt: {e}"})
                    # tu choi het cac the duyet dang treo
                    for fut in list(PENDING.values()):
                        if not fut.done():
                            fut.set_result(False)
                elif t == "permission_reply":
                    fut = PENDING.get(msg.get("id"))
                    if fut and not fut.done():
                        fut.set_result(bool(msg.get("allow")))
                elif t == "undo":
                    ok, text = edit_history.undo(int(msg.get("seq", -1)))
                    # "undo_done" trong ten -> phia Java bat va refresh workspace
                    emit({"type": "undo_done", "seq": msg.get("seq"),
                          "ok": ok, "text": text})
                elif t == "history":
                    emit({"type": "history", "items": edit_history.listing()})
    except Exception as e:
        emit({"type": "error", "text": f"Agent failed to start (auth?): {e}"})
        emit({"type": "done"})


if __name__ == "__main__":
    asyncio.run(main())
