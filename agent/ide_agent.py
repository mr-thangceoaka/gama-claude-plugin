"""
IDE agent cho plugin gama.ui.claude - noi chuyen voi Java qua stdio, JSON lines.

vao  (stdin) : {"type":"chat","text":"...","active_file":"...","diagnostics":[...]}
ra   (stdout): {"type":"text","text":"..."} | {"type":"tool","name":"..."}
               | {"type":"done"} | {"type":"error","text":"..."}

Guardrail nhu agent CLI: Edit/Write chi trong thu muc cua active_file,
Bash chan het (IDE khong can bash). Multi-turn: 1 client song suot phien chat.
"""

import asyncio
import json
import os
import sys

sys.stdout.reconfigure(encoding="utf-8")
sys.stderr.reconfigure(encoding="utf-8")

# ── Cô lập auth/config cho CLI mà SDK spawn ──────────────────────────────
# ~/.claude/settings.json cua user tro BASE_URL sang proxy an external proxy (da het
# han) va CLI bundled cua SDK uu tien settings do hon env. Giai phap: cho CLI
# mot CLAUDE_CONFIG_DIR rieng (sach), ep BASE_URL ve Anthropic, va nho cac
# bien rong/de-lai-tu-proxy de OAuth token (CLAUDE_CODE_OAUTH_TOKEN) co tac dung.
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

# thu muc duoc phep sua - cap nhat theo active_file cua tung message
ALLOWED_ROOT = {"path": ""}


def emit(obj):
    print(json.dumps(obj, ensure_ascii=False), flush=True)


async def can_use_tool(tool_name, input_data, context):
    if tool_name in ("Read", "Glob", "Grep"):
        return PermissionResultAllow()
    if tool_name in ("Write", "Edit"):
        fp = os.path.abspath(input_data.get("file_path", ""))
        root = ALLOWED_ROOT["path"]
        if root and os.path.commonpath([fp, root]) == root:
            return PermissionResultAllow()
        return PermissionResultDeny(message=f"Chi duoc sua file trong: {root}")
    return PermissionResultDeny(message=f"Tool '{tool_name}' bi chan trong IDE mode.")


SYSTEM_HINT = (
    "You are a GAML/GAMA assistant embedded inside the GAMA IDE via a chat panel. "
    "Each user message may include the active .gaml file path and live compiler "
    "diagnostics (from the IDE's Xtext validation - these are authoritative, with "
    "exact line numbers). Use Read to inspect code, Edit to fix. After your edits "
    "the IDE re-validates automatically and the NEXT message will carry fresh "
    "diagnostics - so don't guess whether a fix compiled, just wait for them. "
    "Reply in the user's language, keep answers short, cite line numbers."
)


def build_prompt(msg):
    parts = [msg.get("text", "")]
    af = msg.get("active_file")
    if af:
        parts.append(f"\n\n[context] active_file: {af}")
    diags = msg.get("diagnostics") or []
    if diags:
        parts.append("[context] diagnostics (live tu IDE):")
        for d in diags[:80]:
            parts.append(f"  {d.get('severity','?')} {d.get('file','?')}:{d.get('line','?')} {d.get('message','')}")
    return "\n".join(parts)


async def main():
    options = ClaudeAgentOptions(
        allowed_tools=["Read", "Grep", "Glob", "Edit", "Write"],
        permission_mode="default",
        can_use_tool=can_use_tool,
        system_prompt={"type": "preset", "preset": "claude_code", "append": SYSTEM_HINT},
        max_turns=25,
    )
    loop = asyncio.get_event_loop()
    try:
        async with ClaudeSDKClient(options=options) as client:
            emit({"type": "text", "text": "Agent san sang. Hoi gi ve model GAML di."})
            emit({"type": "done"})
            while True:
                line = await loop.run_in_executor(None, sys.stdin.readline)
                if not line:
                    break
                line = line.strip()
                if not line:
                    continue
                try:
                    msg = json.loads(line)
                except json.JSONDecodeError as e:
                    emit({"type": "error", "text": f"JSON loi tu plugin: {e}"})
                    emit({"type": "done"})
                    continue

                af = msg.get("active_file") or ""
                if af:
                    ALLOWED_ROOT["path"] = os.path.dirname(os.path.abspath(af))

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
                    emit({"type": "error", "text": f"Loi agent: {e}"})
                    emit({"type": "done"})
    except Exception as e:
        emit({"type": "error", "text": f"Khong khoi dong duoc agent (thieu API key?): {e}"})
        emit({"type": "done"})


if __name__ == "__main__":
    asyncio.run(main())
