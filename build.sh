#!/usr/bin/env bash
# Build + install plugin vao GAMA. Chay tu thu muc gama-claude-plugin.
# Sau khi chay: mo lai GAMA la plugin moi co hieu luc.
set -e

G="${GAMA_DIR:-C:/Path/To/Gama}"
P="$G/plugins"
VER="0.1.0"
JAR="gama.ui.claude_${VER}.jar"

jar1() { ls "$P"/$1 2>/dev/null | head -1; }
CP="$(jar1 'org.eclipse.ui_*.jar');$(jar1 'org.eclipse.ui.workbench_*.jar');$(jar1 'org.eclipse.swt_*.jar');$(jar1 'org.eclipse.swt.win32.win32.x86_64_*.jar');$(jar1 'org.eclipse.jface_*.jar');$(jar1 'org.eclipse.core.resources_*.jar');$(jar1 'org.eclipse.core.runtime_*.jar');$(jar1 'org.eclipse.equinox.common_*.jar');$(jar1 'org.eclipse.core.jobs_*.jar');$(jar1 'org.eclipse.osgi_*.jar');$(jar1 'org.eclipse.equinox.registry_*.jar');$(jar1 'org.eclipse.core.commands_*.jar')"

rm -rf bin && mkdir -p bin dist
"$G/jdk/bin/javac.exe" --release 17 -encoding UTF-8 -cp "$CP" -d bin \
    src/gama/ui/claude/views/ChatView.java \
    src/gama/ui/claude/OpenViewAtStartup.java
"$G/jdk/bin/jar.exe" --create --file "dist/$JAR" --manifest META-INF/MANIFEST.MF plugin.xml -C bin .

# cai vao GAMA (bundles.info da co dong dang ky tu lan dau, chi can thay jar)
cp "dist/$JAR" "$P/"
BI="$G/configuration/org.eclipse.equinox.simpleconfigurator/bundles.info"
grep -q "^gama.ui.claude," "$BI" || echo "gama.ui.claude,$VER,plugins/$JAR,4,false" >> "$BI"

echo "OK -> $P/$JAR (mo lai GAMA de nap ban moi)"
