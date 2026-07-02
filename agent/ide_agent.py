"""
IDE agent cho plugin gama.ui.claude - noi chuyen voi Java qua stdio, JSON lines.

vao  (stdin) : {"type":"chat","text","active_file","workspace_summary","diagnostics":[...]}
               {"type":"interrupt"}
               {"type":"permission_reply","id":N,"allow":true/false}
ra   (stdout): {"type":"text"|"tool"|"done"|"error"|...}
               {"type":"permission","id":N,"file","tool","diff"}  <- cho user duyet Edit

M3: dispatcher chay song song voi luot agent -> Stop va duyet diff hoat dong
ngay giua chung luot. Edit/Write mac dinh phai duoc user "Ap dung" trong chat
(GAMA_CLAUDE_AUTO_APPROVE=true de bo qua buoc duyet).
"""

import asyncio
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

from claude_agent_sdk import (
    AssistantMessage,
    ClaudeAgentOptions,
    ClaudeSDKClient,
    PermissionResultAllow,
    PermissionResultDeny,
    ResultMessage,
    TextBlock,
    ToolUseBlock,
)

AUTO_APPROVE = os.environ.get("GAMA_CLAUDE_AUTO_APPROVE", "false").lower() == "true"
ALLOWED_ROOT = {"path": ""}
PENDING = {}          # id -> Future[bool] cho cac the duyet dang cho
_ids = itertools.count(1)


def emit(obj):
    print(json.dumps(obj, ensure_ascii=False), flush=True)


def _diff_preview(tool, input_data):
    if tool == "Write":
        return f"(create / overwrite whole file, {len(input_data.get('content', ''))} chars)"
    def cut(s):
        lines = (s or "").splitlines() or [""]
        return lines[:25] + (["..."] if len(lines) > 25 else [])
    out = ["- " + l for l in cut(input_data.get("old_string", ""))]
    out += ["+ " + l for l in cut(input_data.get("new_string", ""))]
    return "\n".join(out)[:4000]


async def can_use_tool(tool_name, input_data, context):
    if tool_name in ("Read", "Glob", "Grep"):
        return PermissionResultAllow()
    if tool_name in ("Write", "Edit"):
        fp = os.path.abspath(input_data.get("file_path", ""))
        root = ALLOWED_ROOT["path"]
        if not root or os.path.commonpath([fp, root]) != root:
            return PermissionResultDeny(message=f"Edits are only allowed inside: {root}")
        if AUTO_APPROVE:
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
        return PermissionResultAllow() if ok else PermissionResultDeny(
            message="User rejected this change in chat.")
    return PermissionResultDeny(message=f"Tool '{tool_name}' is blocked in IDE mode.")


SYSTEM_HINT = (
    "You are a GAML/GAMA assistant embedded inside the GAMA IDE via a chat panel. "
    "Each user message may include the active .gaml file path and live compiler "
    "diagnostics (from the IDE's Xtext validation - authoritative, exact lines). "
    "Use Read to inspect code, Edit to fix. Your edits may show an approval card "
    "to the user; if an edit is rejected, respect it and propose an alternative "
    "instead of retrying the same change. After edits the IDE re-validates and "
    "the NEXT message carries fresh diagnostics - don't guess whether a fix "
    "compiled. A message may include a path to a window screenshot - Read it to "
    "visually inspect displays and charts. Reply in the user's language, keep "
    "answers short, cite line numbers."
)


def build_prompt(msg):
    parts = [msg.get("text", "")]
    af = msg.get("active_file")
    if af:
        parts.append(f"\n\n[context] active_file: {af}")
    ws = msg.get("workspace_summary")
    if ws:
        parts.append(f"[context] workspace: {ws}")
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
                        emit({"type": "tool", "name": block.name})
            elif isinstance(m, ResultMessage):
                emit({"type": "done"})
    except Exception as e:
        emit({"type": "error", "text": f"Agent error: {e}"})
        emit({"type": "done"})


async def main():
    options = ClaudeAgentOptions(
        allowed_tools=["Read", "Grep", "Glob", "Edit", "Write"],
        permission_mode="default",
        can_use_tool=can_use_tool,
        system_prompt={"type": "preset", "preset": "claude_code", "append": SYSTEM_HINT},
        max_turns=25,
    )
    queue = asyncio.Queue()
    asyncio.get_event_loop().create_task(stdin_reader(queue))
    turn = None
    try:
        async with ClaudeSDKClient(options=options) as client:
            emit({"type": "text", "text": "Agent ready. Ask me about your GAML model."})
            emit({"type": "done"})
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
                    af = msg.get("active_file") or ""
                    if af:
                        ALLOWED_ROOT["path"] = os.path.dirname(os.path.abspath(af))
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
    except Exception as e:
        emit({"type": "error", "text": f"Agent failed to start (auth?): {e}"})
        emit({"type": "done"})


if __name__ == "__main__":
    asyncio.run(main())
