r"""M8: live-simulation tools - observe and steer the experiment RUNNING in the
GAMA IDE right now (not headless re-runs).

Transport: the agent has no direct handle on GAMA, but the Java plugin does.
Each tool emits {"type":"sim_cmd","id",...} on stdout; ChatView executes it on
the GAMA runtime (same mechanism as GAMA's own Interactive Console) and writes
{"type":"sim_reply","id","text"} back on stdin, which ide_agent routes to the
pending future here.

sim_eval accepts full GAML statements, not just expressions: GAML wraps a
statement typed at an agent into a temporary action, so `ask`, `create`,
assignments etc. all work and the displays refresh immediately after.
"""

import asyncio
import itertools
import os

from claude_agent_sdk import create_sdk_mcp_server, tool

import scope

PENDING = {}                 # id -> Future[str], resolved by ide_agent on sim_reply
_ids = itertools.count(1)
_emit = None                 # ide_agent.emit, set via bind() (avoids circular import)


def bind(emit_fn):
    global _emit
    _emit = emit_fn


def resolve(msg):
    """ide_agent calls this for every {"type":"sim_reply"} line from the plugin."""
    fut = PENDING.pop(msg.get("id"), None)
    if fut and not fut.done():
        fut.set_result(str(msg.get("text", "")))


def fail_all(reason):
    """Interrupt: unblock any tool call still waiting on the IDE."""
    for fut in list(PENDING.values()):
        if not fut.done():
            fut.set_result(f"(cancelled: {reason})")
    PENDING.clear()


async def _call(op, timeout=60, **fields):
    if _emit is None:
        return "(sim bridge not initialised)"
    pid = next(_ids)
    fut = asyncio.get_event_loop().create_future()
    PENDING[pid] = fut
    _emit({"type": "sim_cmd", "id": pid, "op": op, **fields})
    try:
        return await asyncio.wait_for(fut, timeout)
    except asyncio.TimeoutError:
        PENDING.pop(pid, None)
        return (f"(no reply from the IDE after {timeout}s - GAMA may be busy "
                "stepping a heavy model; try sim_control pause first)")


def _text(t):
    return {"content": [{"type": "text", "text": t}]}


@tool(
    "sim_status",
    "Snapshot of the experiment RUNNING in the GAMA IDE right now: name, "
    "running/paused, current cycle, parameter values, monitor values, display "
    "names. Call this first whenever the message context mentions a LIVE "
    "simulation. Costs nothing - no need to pause.",
    {},
)
async def sim_status(args):
    return _text(await _call("status"))


@tool(
    "sim_control",
    "Drive the running experiment: action = 'pause' | 'resume' | 'step' "
    "(advance `steps` cycles, max 1000) | 'step_back' | 'reload' (restart from "
    "cycle 0 - destroys current state, only when asked). Typical live-debug "
    "loop: pause -> sim_eval / sim_snapshot -> step a few cycles -> look again "
    "-> resume when done.",
    {"action": str, "steps": int},
)
async def sim_control(args):
    action = str(args.get("action") or "").strip().lower()
    if action not in ("pause", "resume", "step", "step_back", "reload"):
        return _text("action must be one of: pause, resume, step, step_back, reload")
    steps = int(args.get("steps") or 1)
    # stepping N cycles synchronously can be slow on heavy models
    timeout = 120 if action in ("step", "reload") else 30
    return _text(await _call(action, timeout=timeout, steps=steps))


@tool(
    "sim_eval",
    "Evaluate GAML inside the LIVE simulation (same engine as GAMA's "
    "Interactive Console). Works for expressions - `length(prey)`, `prey "
    "collect each.energy`, `agents where (each.speed > 2)` - AND for "
    "statements that CHANGE the running world: `ask prey { energy <- 1.0; }`, "
    "`create predator number: 5;`, `my_global <- 0.3;`. Statements take "
    "effect immediately and the displays refresh. Returns the value in GAML "
    "syntax ('nil' for statements). Pause first for a consistent read on a "
    "fast-running model. Keep result sizes sane: aggregate (mean/count/first "
    "N) instead of dumping thousands of agents.",
    {"code": str},
)
async def sim_eval(args):
    code = str(args.get("code") or "").strip()
    if not code:
        return _text("(empty code)")
    return _text(await _call("eval", timeout=90, code=code))


@tool(
    "sim_snapshot",
    "Capture the LIVE displays of the running experiment as PNG files and "
    "return their paths - then use Read on a path to SEE the display exactly "
    "as the user sees it (agents, charts, map). Optional `display` filters by "
    "display name (substring); empty = all displays. If a display cannot be "
    "captured directly (OpenGL), you get a whole-window capture instead.",
    {"display": str},
)
async def sim_snapshot(args):
    reply = await _call("snapshot", timeout=60,
                        display=str(args.get("display") or ""))
    # whitelist the PNGs so the Read scope-guard lets the agent open them
    for line in reply.splitlines():
        p = line.strip()
        if p.lower().endswith(".png") and os.path.isfile(p):
            scope.allow_read(p)
        elif p.lower().endswith(".png") and ": " in p:
            tail = p.rsplit(": ", 1)[-1].strip()
            if os.path.isfile(tail):
                scope.allow_read(tail)
    return _text(reply)


sim_tools_server = create_sdk_mcp_server(
    name="gama-sim",
    version="1.0.0",
    tools=[sim_status, sim_control, sim_eval, sim_snapshot],
)
