"""Edit history + undo for the IDE agent (Cursor-style safe edits).

Every approved Edit/Write snapshots the file BEFORE the change into a
per-process backup folder and appends a journal entry. The user can undo any
entry from the chat (Undo button on the applied card, or the History panel).
Undo restores the byte-exact backup; for a Write that created a new file the
"backup" is None and undo deletes the file.

Journal is in-memory (one agent process = one session); backups live in
%TEMP%/gama_claude_history/<pid>/ so they survive nothing but the session -
by design, this is not a VCS, it is a seatbelt.
"""

import os
import shutil
import tempfile
import time

_DIR = os.path.join(tempfile.gettempdir(), "gama_claude_history", str(os.getpid()))
_journal = []  # {"seq","time","file","tool","backup","existed","undone","label"}
_seq = 0


def record(tool, path):
    """Snapshot `path` before an Edit/Write. Returns the journal seq."""
    global _seq
    _seq += 1
    os.makedirs(_DIR, exist_ok=True)
    existed = os.path.isfile(path)
    backup = None
    if existed:
        backup = os.path.join(_DIR, f"{_seq:04d}_{os.path.basename(path)}")
        try:
            shutil.copy2(path, backup)
        except OSError:
            backup = None  # snapshot failed; journal it anyway, undo will refuse
    _journal.append({
        "seq": _seq,
        "time": time.strftime("%H:%M:%S"),
        "file": path,
        "tool": tool,
        "backup": backup,
        "existed": existed,
        "undone": False,
        "label": ("edit" if tool == "Edit" else
                  ("overwrite" if existed else "create")),
    })
    return _seq


def undo(seq):
    """Restore the pre-edit snapshot for entry `seq`. -> (ok, message)."""
    entry = next((e for e in _journal if e["seq"] == seq), None)
    if entry is None:
        return False, f"No edit #{seq} in this session's history."
    if entry["undone"]:
        return False, f"Edit #{seq} was already undone."
    path = entry["file"]
    try:
        if not entry["existed"]:
            if os.path.isfile(path):
                os.remove(path)
            entry["undone"] = True
            return True, f"Undid #{seq}: deleted created file {path}"
        if entry["backup"] is None or not os.path.isfile(entry["backup"]):
            return False, (f"Cannot undo #{seq}: the pre-edit snapshot of "
                           f"{path} is missing.")
        shutil.copy2(entry["backup"], path)
        entry["undone"] = True
        return True, f"Undid #{seq}: restored {path} to its pre-edit content"
    except OSError as e:
        return False, f"Undo #{seq} failed: {e}"


def listing(limit=20):
    """Latest entries, newest first, JSON-safe."""
    items = []
    for e in reversed(_journal[-limit:]):
        items.append({"seq": e["seq"], "time": e["time"], "file": e["file"],
                      "label": e["label"], "undone": e["undone"],
                      "can_undo": (not e["undone"]) and
                                  (not e["existed"] or (e["backup"] is not None and
                                                        os.path.isfile(e["backup"])))})
    return items
