#!/usr/bin/env bash
# Build + install the plugin into GAMA. Run from the gama-claude-plugin folder.
# Set GAMA_DIR to your GAMA install folder if it's not auto-detected.
# After it finishes: restart GAMA to load the new build.
set -e

# --- locate GAMA ---
if [ -z "$GAMA_DIR" ]; then
  for c in "$LOCALAPPDATA/Programs/Gama" "$HOME/AppData/Local/Programs/Gama" \
           "/Applications/Gama.app/Contents" "$HOME/GAMA"; do
    if [ -d "$c/plugins" ]; then GAMA_DIR="$c"; break; fi
  done
fi
G="${GAMA_DIR:?Set GAMA_DIR to your GAMA install folder (the one containing plugins/)}"
P="$G/plugins"
VER="0.5.5"
JAR="gama.ui.claude_${VER}.jar"

# --- toolchain: prefer GAMA's bundled JDK, else system javac ---
if [ -x "$G/jdk/bin/javac.exe" ]; then JAVAC="$G/jdk/bin/javac.exe"; JARTOOL="$G/jdk/bin/jar.exe";
elif [ -x "$G/jdk/bin/javac" ]; then JAVAC="$G/jdk/bin/javac"; JARTOOL="$G/jdk/bin/jar";
else JAVAC="javac"; JARTOOL="jar"; fi

# --- classpath from GAMA's own Eclipse jars (any OS: pick whatever SWT fragment exists) ---
jar1() { ls "$P"/$1 2>/dev/null | head -1; }
SWT_FRAG="$(jar1 'org.eclipse.swt.win32.*.jar')"
[ -z "$SWT_FRAG" ] && SWT_FRAG="$(jar1 'org.eclipse.swt.cocoa.*.jar')"
[ -z "$SWT_FRAG" ] && SWT_FRAG="$(jar1 'org.eclipse.swt.gtk.*.jar')"
CP="$(jar1 'org.eclipse.ui_*.jar');$(jar1 'org.eclipse.ui.workbench_*.jar');$(jar1 'org.eclipse.ui.ide_*.jar');$(jar1 'org.eclipse.swt_*.jar');$SWT_FRAG;$(jar1 'org.eclipse.jface_*.jar');$(jar1 'org.eclipse.jface.text_*.jar');$(jar1 'org.eclipse.text_*.jar');$(jar1 'org.eclipse.core.resources_*.jar');$(jar1 'org.eclipse.core.runtime_*.jar');$(jar1 'org.eclipse.equinox.common_*.jar');$(jar1 'org.eclipse.core.jobs_*.jar');$(jar1 'org.eclipse.osgi_*.jar');$(jar1 'org.eclipse.equinox.registry_*.jar');$(jar1 'org.eclipse.core.commands_*.jar');$(jar1 'gama.core_*.jar');$(jar1 'org.eclipse.e4.ui.workbench_*.jar');$(jar1 'org.eclipse.e4.ui.model.workbench_*.jar');$(jar1 'org.eclipse.emf.common_*.jar');$(jar1 'org.eclipse.emf.ecore_*.jar')"
# on mac/linux javac wants ':' separators
case "$(uname -s)" in Darwin|Linux) CP="${CP//;/:}";; esac

rm -rf bin && mkdir -p bin dist
"$JAVAC" --release 17 -encoding UTF-8 -cp "$CP" -d bin $(find src -name '*.java')
"$JARTOOL" --create --file "dist/$JAR" --manifest META-INF/MANIFEST.MF plugin.xml -C bin .

# --- install: copy jar + register with simpleconfigurator (backup once) ---
# older versions must go away, or OSGi keeps loading the stale bundle
for old in "$P"/gama.ui.claude_*.jar; do
  [ -e "$old" ] && [ "$(basename "$old")" != "$JAR" ] && rm -f "$old"
done
cp "dist/$JAR" "$P/"
BI="$G/configuration/org.eclipse.equinox.simpleconfigurator/bundles.info"
cp -n "$BI" "$BI.bak-before-claude" 2>/dev/null || true
grep -v "^gama.ui.claude," "$BI" > "$BI.tmp" && mv "$BI.tmp" "$BI"
echo "gama.ui.claude,$VER,plugins/$JAR,4,false" >> "$BI"

echo "OK -> $P/$JAR (restart GAMA to load it)"
