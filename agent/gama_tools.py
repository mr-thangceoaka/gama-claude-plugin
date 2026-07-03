r"""GAMA headless + runtime-observation tools for the IDE agent.

  validate_gaml_syntax    -> compile-check one model (no full run)
  run_gama_headless       -> run a `type: batch` experiment
  run_experiment_headless -> run ANY experiment via an XML plan and read back
                             its outputs (monitors + snapshots) - the closest
                             thing to "watch the simulation" without the GUI
  read_ide_console        -> tail of the GAMA IDE console (the plugin dumps
                             it to %TEMP%\gama_claude_console.txt)

GAMA_HEADLESS_DIR comes from the plugin: the Java side auto-detects
<GAMA install>/headless and passes it in the environment (override with
headless_dir= in ~/.gama-claude.properties).

Baked-in gotchas (learned the hard way):
- Windows: gama-headless.bat only runs via an ABSOLUTE path with cwd on the
  headless folder. Bare name -> "not recognized".
- Use -xml to compile-check a model, NOT -validate. -validate only checks
  GAMA's built-in library and ignores your file. exit 0 + xml written = PASS.
- subprocess runs in a worker thread (asyncio.to_thread): the IDE dispatcher
  must keep handling Stop / permission clicks while GAMA compiles or runs.
"""

import asyncio
import glob as globmod
import os
import re
import shutil
import subprocess
import tempfile
import xml.etree.ElementTree as ET

from claude_agent_sdk import create_sdk_mcp_server, tool

import scope

GAMA_HEADLESS_DIR = os.environ.get("GAMA_HEADLESS_DIR", "").strip()

IS_WINDOWS = os.name == "nt"
_SCRIPT = "gama-headless.bat" if IS_WINDOWS else "gama-headless.sh"

CONSOLE_DUMP = os.path.join(tempfile.gettempdir(), "gama_claude_console.txt")


def _script_path() -> str:
    return os.path.join(GAMA_HEADLESS_DIR, _SCRIPT)


def _config_error() -> "str | None":
    if not GAMA_HEADLESS_DIR:
        return ("GAMA_HEADLESS_DIR is not set. The plugin normally auto-detects "
                "<GAMA>/headless; set headless_dir= in ~/.gama-claude.properties "
                "if your layout differs.")
    if not os.path.isfile(_script_path()):
        return f"Cannot find {_SCRIPT} in GAMA_HEADLESS_DIR = {GAMA_HEADLESS_DIR!r}."
    return None


def _headless_cmd(extra_args):
    script = _script_path()
    if IS_WINDOWS:
        return ["cmd", "/c", script] + extra_args
    return ["bash", script] + extra_args


def _run_blocking(extra_args, timeout):
    result = subprocess.run(
        _headless_cmd(extra_args),
        capture_output=True,
        text=True,
        timeout=timeout,
        cwd=GAMA_HEADLESS_DIR,
    )
    return result.returncode, result.stdout, result.stderr


async def _run(extra_args, timeout):
    return await asyncio.to_thread(_run_blocking, extra_args, timeout)


def _log_tail(n_lines=25):
    log_path = os.path.join(GAMA_HEADLESS_DIR, ".metadata", ".log")
    try:
        with open(log_path, encoding="utf-8", errors="replace") as f:
            return "".join(f.readlines()[-n_lines:])
    except OSError:
        return "(could not read .metadata/.log)"


def _text(t):
    return {"content": [{"type": "text", "text": t}]}


@tool(
    "validate_gaml_syntax",
    "Compile-check one GAML model with `gama-headless -xml` (compiles it, does "
    "not run a full sim). Returns PASS if it compiles, or FAIL plus the GAMA "
    "log. Use it to verify a model you just created or edited when the IDE "
    "diagnostics are not available for it yet. Needs the .gaml path and one "
    "experiment name from the model.",
    {"gaml_path": str, "experiment_name": str},
)
async def validate_gaml_syntax(args):
    err = _config_error()
    if err:
        return _text(err)

    out_xml = os.path.join(tempfile.gettempdir(), "gama_validate_check.xml")
    if os.path.exists(out_xml):
        os.remove(out_xml)
    try:
        code, _stdout, _stderr = await _run(
            ["-xml", args["experiment_name"], args["gaml_path"], out_xml], timeout=180
        )
    except subprocess.TimeoutExpired:
        return _text("TIMEOUT after 180s during compile-check.")

    compiled = code == 0 and os.path.exists(out_xml)
    status = "PASS" if compiled else "FAIL"
    detail = f"validate: {status}\nexit_code: {code}\n"
    if not compiled:
        detail += "\n--- compile log (.metadata/.log) ---\n" + _log_tail()
        detail += "\n(GAMA only says it failed, not which line. Read the .gaml to locate it.)"
    if os.path.exists(out_xml):
        os.remove(out_xml)
    return _text(detail)


@tool(
    "run_gama_headless",
    "Run a GAMA experiment for real in batch mode (`gama-headless -batch`). Only "
    "after validate_gaml_syntax PASSes, and only for `experiment ... type: batch`. "
    "For gui experiments use run_experiment_headless instead. Can take minutes.",
    {"gaml_path": str, "experiment_name": str, "verbose": bool},
)
async def run_gama_headless(args):
    err = _config_error()
    if err:
        return _text(err)

    extra = []
    if args.get("verbose"):
        extra.append("-v")
    extra += ["-batch", args["experiment_name"], args["gaml_path"]]
    try:
        code, stdout, stderr = await _run(extra, timeout=900)
        text = f"exit_code: {code}\n\nstdout:\n{stdout}\n\nstderr:\n{stderr}"
    except subprocess.TimeoutExpired:
        text = "TIMEOUT after 900s. Model's probably too heavy or stuck in a loop."
    return _text(text)


# ------------------------------------------------- runtime observation (M7)

def _parse_outputs_xml(out_dir, last_steps=12):
    """simulation-outputs*.xml -> readable table of the last N sampled steps."""
    chunks = []
    for f in sorted(globmod.glob(os.path.join(out_dir, "simulation-outputs*.xml"))):
        try:
            root = ET.parse(f).getroot()
        except ET.ParseError as e:
            chunks.append(f"{os.path.basename(f)}: XML parse error: {e}")
            continue
        steps = root.findall(".//Step")
        if not steps:
            chunks.append(f"{os.path.basename(f)}: no <Step> entries (model has "
                          "no monitors/outputs that headless can record).")
            continue
        chunks.append(f"{os.path.basename(f)}: {len(steps)} recorded steps; "
                      f"last {min(last_steps, len(steps))}:")
        for s in steps[-last_steps:]:
            vals = ", ".join(
                f'{v.get("name", "?")}={(v.text or v.get("value", "")).strip()}'
                for v in s)
            chunks.append(f'  step {s.get("id", "?")}: {vals}')
    return "\n".join(chunks) if chunks else "(no simulation-outputs*.xml produced)"


@tool(
    "run_experiment_headless",
    "Actually RUN an experiment (gui or batch) without the IDE: builds the "
    "headless XML plan, caps it at `final_step` cycles, runs it, and returns "
    "the recorded outputs (monitors/variables per sampled step) plus any "
    "display snapshots as PNG paths you can Read to LOOK at the result. Use "
    "this to check runtime behaviour (values evolving, agents moving) after "
    "the model compiles. Keep final_step modest (e.g. 50-500) - big runs are "
    "slow. The model only records what it declares (monitors, displays).",
    {"gaml_path": str, "experiment_name": str, "final_step": int},
)
async def run_experiment_headless(args):
    err = _config_error()
    if err:
        return _text(err)
    gaml, exp = args["gaml_path"], args["experiment_name"]
    final_step = max(1, min(int(args.get("final_step") or 100), 100_000))

    work = os.path.join(tempfile.gettempdir(),
                        f"gama_claude_run_{os.getpid()}")
    shutil.rmtree(work, ignore_errors=True)
    os.makedirs(work, exist_ok=True)
    plan = os.path.join(work, "plan.xml")

    # 1) let GAMA generate the experiment plan (this also compiles the model)
    try:
        code, stdout, stderr = await _run(["-xml", exp, gaml, plan], timeout=300)
    except subprocess.TimeoutExpired:
        return _text("TIMEOUT after 300s while building the XML plan.")
    if code != 0 or not os.path.isfile(plan):
        return _text("FAILED to build the plan (model may not compile):\n"
                     f"exit_code: {code}\n--- log ---\n{_log_tail()}")

    # 2) cap the run length
    try:
        with open(plan, encoding="utf-8") as f:
            xml_text = f.read()
        if re.search(r'finalStep="\d*"', xml_text):
            xml_text = re.sub(r'finalStep="\d*"', f'finalStep="{final_step}"',
                              xml_text)
        else:
            xml_text = xml_text.replace("<Simulation ",
                                        f'<Simulation finalStep="{final_step}" ', 1)
        with open(plan, "w", encoding="utf-8") as f:
            f.write(xml_text)
    except OSError as e:
        return _text(f"Could not adjust the plan file: {e}")

    # 3) run the plan; outputs land in work/out
    out_dir = os.path.join(work, "out")
    try:
        code, stdout, stderr = await _run([plan, out_dir], timeout=900)
    except subprocess.TimeoutExpired:
        return _text(f"TIMEOUT after 900s running {exp} for {final_step} steps. "
                     "Try a smaller final_step.")

    report = [f"run_experiment_headless: {exp} for {final_step} steps",
              f"exit_code: {code}"]
    report.append("\n--- recorded outputs ---")
    report.append(_parse_outputs_xml(out_dir))

    # console output written by the model (write/save statements)
    for cf in sorted(globmod.glob(os.path.join(out_dir, "console-outputs*.txt"))):
        try:
            with open(cf, encoding="utf-8", errors="replace") as f:
                tail = f.readlines()[-30:]
            if tail:
                report.append(f"\n--- {os.path.basename(cf)} (last 30 lines) ---")
                report.append("".join(tail).rstrip())
        except OSError:
            pass

    # display snapshots: whitelist them so the agent can Read (see) them
    snaps = sorted(globmod.glob(os.path.join(out_dir, "snapshot", "*.png")))
    if snaps:
        shown = snaps[-4:]
        for s in shown:
            scope.allow_read(s)
        report.append("\n--- display snapshots (use Read on these paths to "
                      "SEE the displays) ---")
        report += shown
    if code != 0:
        report.append("\n--- gama log tail ---\n" + _log_tail())
    elif stderr.strip():
        report.append("\nstderr tail:\n" + stderr.strip()[-1500:])
    return _text("\n".join(report))


@tool(
    "read_ide_console",
    "Tail of the GAMA IDE console (simulation write/error output). The plugin "
    "refreshes the dump while your turn runs and when the user sends a "
    "message, so after asking the user to run a GUI experiment, call this in "
    "the NEXT turn to see what the simulation printed.",
    {"lines": int},
)
async def read_ide_console(args):
    n = max(5, min(int(args.get("lines") or 60), 400))
    try:
        with open(CONSOLE_DUMP, encoding="utf-8", errors="replace") as f:
            tail = f.readlines()[-n:]
    except OSError:
        return _text("(no console dump yet - the GAMA console is empty or the "
                     "plugin has not captured it; it refreshes on each user "
                     "message and every ~2s during a turn)")
    return _text("".join(tail).rstrip() or "(console is empty)")


gama_tools_server = create_sdk_mcp_server(
    name="gama-tools",
    version="2.0.0",
    tools=[validate_gaml_syntax, run_gama_headless,
           run_experiment_headless, read_ide_console],
)
