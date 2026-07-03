"""Workspace index: every .gaml under the context root, parsed and cached.

The cache is keyed by (path -> mtime, size); unchanged files are never
re-parsed, so building the project map on each chat turn costs ~0 after the
first scan. Used two ways:
  - project_map(): compact text attached to every prompt (the agent's mental
    map of the whole project, not just the active file)
  - find_symbol(): definition sites from the parsed outlines + reference
    counts from a plain text scan
"""

import os
import re

from gaml_semantics import parse_gaml, compact_text, outline_text

_SKIP_DIRS = {".git", ".svn", ".metadata", ".settings", "node_modules",
              "__pycache__", ".venv", "venv"}
MAX_FILES = 400
MAX_FILE_BYTES = 2_000_000

_cache = {}  # abspath -> {"mtime": float, "size": int, "parsed": dict, "lines": int}


def gaml_files(root):
    """All .gaml files under root (sorted, capped), skipping junk dirs."""
    found = []
    root = os.path.abspath(root)
    for dirpath, dirnames, filenames in os.walk(root):
        dirnames[:] = [d for d in dirnames
                       if d not in _SKIP_DIRS and not d.startswith(".")]
        for f in filenames:
            if f.lower().endswith(".gaml"):
                found.append(os.path.join(dirpath, f))
                if len(found) >= MAX_FILES:
                    return sorted(found)
    return sorted(found)


def parsed(path):
    """Parse one file through the mtime cache; None if unreadable/too big."""
    path = os.path.abspath(path)
    try:
        st = os.stat(path)
    except OSError:
        _cache.pop(path, None)
        return None
    hit = _cache.get(path)
    if hit and hit["mtime"] == st.st_mtime and hit["size"] == st.st_size:
        return hit
    if st.st_size > MAX_FILE_BYTES:
        return None
    try:
        with open(path, encoding="utf-8", errors="replace") as f:
            text = f.read()
    except OSError:
        return None
    entry = {"mtime": st.st_mtime, "size": st.st_size,
             "parsed": parse_gaml(text), "lines": text.count("\n") + 1}
    _cache[path] = entry
    return entry


def outline(path):
    e = parsed(path)
    if e is None:
        return f"(could not read/parse {path})"
    return outline_text(e["parsed"], path)


def project_map(root, budget=2600):
    """Compact one-line-per-file map of the whole project, capped at budget chars."""
    if not root or not os.path.isdir(root):
        return ""
    files = gaml_files(root)
    if not files:
        return ""
    lines = []
    for p in files:
        e = parsed(p)
        rel = os.path.relpath(p, root)
        if e is None:
            lines.append(f"{rel} (unparsed)")
        else:
            lines.append(f'{rel} ({e["lines"]}L) :: {compact_text(e["parsed"])}')
    out = "\n".join(lines)
    if len(out) <= budget:
        return out
    # over budget: drop the structure, keep file names + model names
    slim = []
    for p in files:
        e = _cache.get(os.path.abspath(p))
        model = e["parsed"]["model"] if e else "?"
        slim.append(f'{os.path.relpath(p, root)} (model {model})')
    out = "\n".join(slim)
    if len(out) > budget:
        out = out[:budget] + f"\n... ({len(files)} files total, list truncated)"
    return out


def find_symbol(root, name, max_refs_files=25):
    """Definitions (from outlines) + per-file reference counts for `name`."""
    defs, refs = [], []
    if not root or not name:
        return "Empty root or symbol name."
    word = re.compile(r'\b' + re.escape(name) + r'\b')
    for p in gaml_files(root):
        rel = os.path.relpath(p, root)
        e = parsed(p)
        if e:
            stack = list(e["parsed"]["blocks"])
            while stack:
                n = stack.pop()
                if n.get("name") == name and n["kind"] != "attr":
                    defs.append(f'{rel}:{n["line"]}  {n["kind"]} {name}')
                elif n.get("name") == name:
                    defs.append(f'{rel}:{n["line"]}  attribute {name} ({n.get("type","?")})')
                stack.extend(n.get("children", []))
        try:
            with open(p, encoding="utf-8", errors="replace") as f:
                cnt = len(word.findall(f.read()))
        except OSError:
            cnt = 0
        if cnt:
            refs.append((cnt, f"{rel}: {cnt} occurrence(s)"))
    refs.sort(reverse=True)
    out = []
    out.append("definitions:" if defs else f"definitions: (none found for '{name}')")
    out += ["  " + d for d in defs]
    out.append("references (word matches, incl. definitions):")
    out += ["  " + r for _, r in refs[:max_refs_files]] or ["  (none)"]
    return "\n".join(out)
