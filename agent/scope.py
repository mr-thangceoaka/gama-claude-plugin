"""Shared read/edit scope for the IDE agent.

One place both ide_agent.py (permission checks) and the MCP tool modules
(gama_tools, semantic_tools) can see, without circular imports.
"""

import os

# root folder the agent may Read/Grep/Glob/Edit/Write inside (set per turn)
ALLOWED_ROOT = {"path": ""}

# individual files readable outside the root (snapshots, headless outputs)
ALLOWED_READ_EXTRA = set()


def in_root(path: str) -> bool:
    root = ALLOWED_ROOT["path"]
    if not root or not path:
        return False
    try:
        return os.path.commonpath([os.path.abspath(path), root]) == root
    except ValueError:  # different drive on Windows
        return False


def allow_read(path: str) -> None:
    """Whitelist one extra file for the Read tool (e.g. a PNG in %TEMP%)."""
    ALLOWED_READ_EXTRA.add(os.path.abspath(path))
