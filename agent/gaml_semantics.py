"""GAML semantic layer: parse a .gaml file into a structured outline.

Not a full grammar - a brace-aware scanner that recognises the blocks that
matter for navigation: model, import, global, species, grid, experiment,
action, reflex, init, aspect, state, output, display, chart, plus leaf
statements (parameter, monitor, user_command) and attribute declarations.
Every node carries its 1-based start line so the agent can cite/jump.

Comments are blanked before scanning; braces and semicolons inside string
literals are ignored via a parallel mask, but string contents are kept in
the text (chart/display/parameter names live in string literals).
"""

import re

BLOCK_KINDS = ("global", "species", "grid", "experiment", "action", "reflex",
               "init", "aspect", "state", "output", "display", "chart",
               "permanent", "user_command")
LEAF_KINDS = ("parameter", "monitor")

_NAME = r'("([^"]*)"|\'([^\']*)\'|[A-Za-z_]\w*)'

_RE = {
    "model":      re.compile(r'^\s*model\s+([A-Za-z_]\w*)'),
    "import":     re.compile(r'^\s*import\s+"([^"]+)"'),
    "species":    re.compile(r'\bspecies\s+([A-Za-z_]\w*)'),
    "grid":       re.compile(r'\bgrid\s+([A-Za-z_]\w*)'),
    "experiment": re.compile(r'\bexperiment\s+' + _NAME),
    "action":     re.compile(r'\baction\s+([A-Za-z_]\w*)'),
    "reflex":     re.compile(r'\breflex\s+([A-Za-z_]\w*)'),
    "aspect":     re.compile(r'\baspect\s+([A-Za-z_]\w*)'),
    "state":      re.compile(r'\bstate\s+([A-Za-z_]\w*)'),
    "display":    re.compile(r'\bdisplay\s+' + _NAME),
    "chart":      re.compile(r'\bchart\s+' + _NAME),
    "parameter":  re.compile(r'^\s*parameter\s+' + _NAME),
    "monitor":    re.compile(r'^\s*monitor\s+' + _NAME),
    "user_command": re.compile(r'\buser_command\s+' + _NAME),
    "type":       re.compile(r'\btype\s*:\s*([A-Za-z_]\w*)'),
    "when":       re.compile(r'\bwhen\s*:\s*([^{;]{1,60})'),
    "var_of":     re.compile(r'\bvar\s*:\s*([A-Za-z_]\w*)'),
    "skills":     re.compile(r'\bskills\s*:\s*\[([^\]]*)\]'),
    "parent":     re.compile(r'\bparent\s*:\s*([A-Za-z_]\w*)'),
}

# attribute declaration inside global/species: "int n_cars <- 50;" etc.
_RE_ATTR = re.compile(
    r'^\s*(?:const\s+)?(int|float|bool|string|rgb|point|geometry|list(?:<[^>]*>)?|'
    r'map(?:<[^>]*>)?|pair|graph|matrix|file|date|path|topology|agent|'
    r'[A-Za-z_]\w*)\s+([A-Za-z_]\w*)\s*(?:<-|<<|;|update\b|init\b|function\b)')
_ATTR_STOP = {"return", "do", "ask", "create", "if", "else", "loop", "write",
              "save", "put", "add", "remove", "set", "let", "switch", "match",
              "draw", "assert", "using", "error", "warn"}


def _clean(text):
    """Blank comments; build a mask marking chars inside string literals ('s')."""
    out, mask = [], []
    i, n, state, q = 0, len(text), 0, ""
    while i < n:
        c = text[i]
        if state == 0:  # code
            if c == "/" and i + 1 < n and text[i + 1] == "/":
                state = 1; out.append("  "); mask.append(".."); i += 2; continue
            if c == "/" and i + 1 < n and text[i + 1] == "*":
                state = 2; out.append("  "); mask.append(".."); i += 2; continue
            if c in ('"', "'"):
                state, q = 3, c
                out.append(c); mask.append("s"); i += 1; continue
            out.append(c); mask.append("."); i += 1
        elif state == 1:  # // comment
            if c == "\n":
                state = 0; out.append("\n"); mask.append(".")
            else:
                out.append(" "); mask.append(".")
            i += 1
        elif state == 2:  # /* comment */
            if c == "*" and i + 1 < n and text[i + 1] == "/":
                state = 0; out.append("  "); mask.append(".."); i += 2; continue
            out.append("\n" if c == "\n" else " "); mask.append("."); i += 1
        else:  # string literal
            if c == "\\" and i + 1 < n:
                out.append(c); mask.append("s"); i += 1
                out.append(text[i]); mask.append("s"); i += 1; continue
            out.append(c); mask.append("s")
            if c == q:
                state = 0
            i += 1
    return "".join(out), "".join(mask)


def _pick_name(m):
    """First non-None capture group = the (possibly quoted) name."""
    for g in m.groups():
        if g is not None:
            return g.strip('"\'')
    return "?"


_RE_HEAD_JUNK = re.compile(r'^\s*(?:model\s+[A-Za-z_]\w*|import\s+"[^"]*")\s*',
                           re.MULTILINE)


def _header_node(stmt, line, is_block):
    # GAML's `model X` / `import "..."` lines carry no ';' - they end up glued
    # to the front of the first block header (usually global). Strip them.
    s = _RE_HEAD_JUNK.sub("", stmt).strip()
    if not s:
        return None
    for kind in ("global", "output", "permanent", "init"):
        if is_block and re.match(r'^\s*' + kind + r'\b', s):
            return {"kind": kind, "name": kind, "line": line}
    order = BLOCK_KINDS if is_block else LEAF_KINDS
    for kind in order:
        rx = _RE.get(kind)
        if not rx:
            continue
        m = rx.search(s)
        if not m:
            continue
        node = {"kind": kind, "name": _pick_name(m), "line": line}
        for extra in ("type", "when", "var_of", "skills", "parent"):
            em = _RE[extra].search(s)
            if em:
                node[extra] = em.group(1).strip()
        return node
    if not is_block:
        am = _RE_ATTR.match(s)
        if am and am.group(2) not in _ATTR_STOP and am.group(1) not in _ATTR_STOP:
            return {"kind": "attr", "name": am.group(2), "line": line,
                    "type": am.group(1)}
    return None


def parse_gaml(text):
    """-> {model, imports:[{name,line}], blocks:[node]} ; node.children nested."""
    clean, mask = _clean(text)
    root = {"kind": "file", "name": "", "line": 0, "children": []}
    stack = [root]
    model, imports = None, []

    buf, buf_line, line = [], 1, 1
    for idx, c in enumerate(clean):
        inside_str = mask[idx] == "s"
        if c == "\n":
            line += 1
        if inside_str:
            buf.append(c)
            continue
        if c == "{":
            stmt = "".join(buf)
            node = _header_node(stmt, buf_line, True)
            if node is not None:
                node["children"] = []
                stack[-1]["children"].append(node)
                stack.append(node)
            else:
                stack.append(stack[-1])  # anonymous block: stay at same level
            buf, buf_line = [], line
        elif c == "}":
            if len(stack) > 1:
                stack.pop()
            buf, buf_line = [], line
        elif c == ";":
            stmt = "".join(buf)
            if model is None:
                mm = _RE["model"].match(stmt)
                if mm:
                    model = mm.group(1)
            im = _RE["import"].match(stmt.strip())
            if im:
                imports.append({"name": im.group(1), "line": buf_line})
            else:
                node = _header_node(stmt, buf_line, False)
                if node is not None:
                    stack[-1]["children"].append(node)
            buf, buf_line = [], line
        else:
            if not buf and not c.isspace():
                buf_line = line
            buf.append(c)

    # model/import lines usually end without ';' inside the leftover buffer of
    # the first block - also scan raw head of file for them
    if model is None:
        mm = re.search(r'^\s*model\s+([A-Za-z_]\w*)', clean, re.MULTILINE)
        model = mm.group(1) if mm else None
    if not imports:
        for m in re.finditer(r'^\s*import\s+"([^"]+)"', clean, re.MULTILINE):
            imports.append({"name": m.group(1),
                            "line": clean.count("\n", 0, m.start()) + 1})
    return {"model": model, "imports": imports, "blocks": root["children"]}


# ---------------------------------------------------------------- rendering

def _fmt_node(n, depth, out, max_attrs=12):
    pad = "  " * depth
    label = n["kind"] if n["name"] == n["kind"] else f'{n["kind"]} {n["name"]}'
    bits = [f'{pad}{label} (L{n["line"]})']
    for k in ("type", "parent", "skills", "var_of", "when"):
        if n.get(k):
            bits.append(f'{k}:{n[k]}')
    out.append(" ".join(bits))
    attrs = [c for c in n.get("children", []) if c["kind"] == "attr"]
    if attrs:
        names = ", ".join(a["name"] for a in attrs[:max_attrs])
        if len(attrs) > max_attrs:
            names += f", ... +{len(attrs) - max_attrs}"
        out.append(f'{pad}  attrs: {names}')
    for c in n.get("children", []):
        if c["kind"] != "attr":
            _fmt_node(c, depth + 1, out, max_attrs)


def outline_text(parsed, path=""):
    """Full human/agent-readable outline of one parsed file."""
    out = []
    head = f'model {parsed["model"]}' if parsed["model"] else "(no model header)"
    if path:
        head = f'{path} - {head}'
    out.append(head)
    for im in parsed["imports"]:
        out.append(f'  import "{im["name"]}" (L{im["line"]})')
    for b in parsed["blocks"]:
        _fmt_node(b, 1, out)
    return "\n".join(out)


def compact_text(parsed):
    """One/few-line summary of a file for the project map."""

    def kids(n, kind):
        return [c for c in n.get("children", []) if c["kind"] == kind]

    def deep(n, kind, acc):
        for c in n.get("children", []):
            if c["kind"] == kind:
                acc.append(c)
            deep(c, kind, acc)
        return acc

    parts = []
    for b in parsed["blocks"]:
        if b["kind"] == "global":
            inner = []
            for k in ("action", "reflex"):
                names = [f'{c["name"]}@L{c["line"]}' for c in deep(b, k, [])]
                if names:
                    inner.append(f'{k}s: ' + ", ".join(names))
            n_attr = len(kids(b, "attr"))
            if n_attr:
                inner.append(f'{n_attr} attrs')
            parts.append(f'global@L{b["line"]}'
                         + (f' ({"; ".join(inner)})' if inner else ""))
        elif b["kind"] in ("species", "grid"):
            inner = []
            for k in ("action", "reflex", "aspect", "state"):
                names = [f'{c["name"]}@L{c["line"]}' for c in deep(b, k, [])]
                if names:
                    inner.append(f'{k}: ' + ", ".join(names))
            parts.append(f'{b["kind"]} {b["name"]}@L{b["line"]}'
                         + (f' [{"; ".join(inner)}]' if inner else ""))
        elif b["kind"] == "experiment":
            inner = []
            disp = deep(b, "display", [])
            charts = deep(b, "chart", [])
            mons = deep(b, "monitor", [])
            pars = deep(b, "parameter", [])
            if pars:
                inner.append(f'{len(pars)} params')
            if disp:
                inner.append('displays: ' + ", ".join(
                    f'{d["name"]}@L{d["line"]}' for d in disp))
            if charts:
                inner.append('charts: ' + ", ".join(
                    f'{c["name"]}@L{c["line"]}' for c in charts))
            if mons:
                inner.append('monitors: ' + ", ".join(
                    f'{m["name"]}@L{m["line"]}' for m in mons))
            t = f' type:{b["type"]}' if b.get("type") else ""
            parts.append(f'experiment {b["name"]}@L{b["line"]}{t}'
                         + (f' {{{"; ".join(inner)}}}' if inner else ""))
    head = parsed["model"] or "?"
    return f'model {head}: ' + "  |  ".join(parts) if parts else f'model {head}'
