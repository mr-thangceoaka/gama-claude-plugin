"""MCP tools exposing the GAML semantic layer / workspace index to the agent.

All paths are checked against scope.ALLOWED_ROOT - same guardrail as
Read/Edit. Cheap by design: results come from the mtime-cached index, so the
agent should prefer these over Read-ing whole files just to find something.
"""

import os

from claude_agent_sdk import create_sdk_mcp_server, tool

import gaml_index
import scope


def _text(t):
    return {"content": [{"type": "text", "text": t}]}


@tool(
    "gaml_outline",
    "Structured outline of ONE .gaml file: model, imports, global, species, "
    "grids, experiments, and inside them actions/reflexes/aspects/states/"
    "displays/charts/monitors/parameters/attributes - each with its line "
    "number. MUCH cheaper than Read for understanding a file's structure; "
    "Read only the specific line ranges you then care about.",
    {"gaml_path": str},
)
async def gaml_outline(args):
    p = args.get("gaml_path", "")
    if not scope.in_root(p):
        return _text(f"Blocked: {p} is outside the context folder "
                     f"{scope.ALLOWED_ROOT['path']}.")
    return _text(gaml_index.outline(p))


@tool(
    "find_gaml_symbol",
    "Find where a symbol (species, action, reflex, experiment, attribute, "
    "display...) is DEFINED across the whole project, plus per-file "
    "reference counts. Use before renaming/editing something used in "
    "several files.",
    {"name": str},
)
async def find_gaml_symbol(args):
    root = scope.ALLOWED_ROOT["path"]
    return _text(gaml_index.find_symbol(root, args.get("name", "").strip()))


@tool(
    "project_map",
    "Fresh compact map of every .gaml file in the project (files, models, "
    "species, experiments, displays - with line numbers). A shorter version "
    "is already attached to each user message; call this only if you need "
    "it re-scanned mid-turn (e.g. right after creating files).",
    {},
)
async def project_map(args):
    root = scope.ALLOWED_ROOT["path"]
    m = gaml_index.project_map(root, budget=6000)
    return _text(m or f"(no .gaml files found under {root})")


semantic_tools_server = create_sdk_mcp_server(
    name="gaml-tools",
    version="1.0.0",
    tools=[gaml_outline, find_gaml_symbol, project_map],
)
