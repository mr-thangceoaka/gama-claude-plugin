package gama.ui.claude.views;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IMarker;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IResourceChangeEvent;
import org.eclipse.core.resources.IResourceChangeListener;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.resources.WorkspaceJob;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.swt.SWT;
import org.eclipse.swt.browser.Browser;
import org.eclipse.swt.browser.BrowserFunction;
import org.eclipse.swt.custom.SashForm;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Text;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.part.ViewPart;

/**
 * M2: chat that. JS trong Browser -> claudeSend() -> subprocess Python (JSON lines)
 * -> claudeRecv() day ve JS. Sau moi luot agent: refreshLocal de Xtext re-validate,
 * marker listener tu do JSON diagnostics moi -> luot chat sau co context tuoi.
 *
 * Config: %USERPROFILE%\.gama-claude.properties  (python=..., script=..., key=...)
 */
public class ChatView extends ViewPart {

	public static final String ID = "gama.ui.claude.views.ChatView";

	private static final String OUT_FILE =
			System.getProperty("java.io.tmpdir") + File.separator + "gama_claude_markers.json";
	private static final String ERR_LOG =
			System.getProperty("java.io.tmpdir") + File.separator + "gama_claude_agent.log";

	private Browser browser;
	private Text log;
	private IResourceChangeListener markerListener;

	private Process agentProc;
	private BufferedWriter agentIn;
	/** JSON array cua diagnostics lan quet gan nhat - dinh kem moi message chat. */
	private volatile String lastDiagArray = "[]";
	private volatile String lastActiveFile = null;

	private record Diag(String file, String project, int line, int sev, String msg) {}

	/** Tom tat toan workspace, dinh kem message de agent biet buc tranh lon. */
	private volatile String lastSummary = "";

	// ------------------------------------------------------------------ UI

	@Override
	public void createPartControl(final Composite parent) {
		final SashForm sash = new SashForm(parent, SWT.VERTICAL);

		try {
			browser = new Browser(sash, SWT.EDGE);
		} catch (final Throwable t1) {
			try {
				browser = new Browser(sash, SWT.NONE);
			} catch (final Throwable t2) {
				final Label l = new Label(sash, SWT.WRAP);
				l.setText("Browser khong tao duoc: " + t2);
			}
		}
		if (browser != null) {
			browser.setText(chatHtml());
			// JS goi: claudeSend("noi dung user go")
			new BrowserFunction(browser, "claudeSend") {
				@Override
				public Object function(final Object[] args) {
					if (args != null && args.length > 0 && args[0] instanceof String s) {
						sendChat(s);
					}
					return null;
				}
			};
			// JS goi: claudeStop() -> ngat luot agent dang chay
			new BrowserFunction(browser, "claudeStop") {
				@Override
				public Object function(final Object[] args) {
					sendRaw("{\"type\":\"interrupt\"}", false);
					return null;
				}
			};
			// JS goi: claudePerm(id, true/false) -> tra loi the duyet Edit
			new BrowserFunction(browser, "claudePerm") {
				@Override
				public Object function(final Object[] args) {
					if (args != null && args.length >= 2 && args[0] instanceof Number n) {
						final boolean ok = Boolean.TRUE.equals(args[1]);
						sendRaw("{\"type\":\"permission_reply\",\"id\":" + n.longValue() + ",\"allow\":" + ok + "}", false);
					}
					return null;
				}
			};
		}

		final Composite bottom = new Composite(sash, SWT.NONE);
		bottom.setLayout(new GridLayout(1, false));
		final Button scan = new Button(bottom, SWT.PUSH);
		scan.setText("Scan GAML errors → JSON  (tu chay lai khi marker doi)");
		scan.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
		log = new Text(bottom, SWT.MULTI | SWT.READ_ONLY | SWT.V_SCROLL | SWT.H_SCROLL | SWT.BORDER);
		log.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
		log.setText("Dang cho marker dau tien... (sua/save mot file .gaml la thay)");
		scan.addListener(SWT.Selection, e -> scanMarkers());
		sash.setWeights(new int[] { 70, 30 });

		markerListener = event -> {
			if (event.getType() == IResourceChangeEvent.POST_CHANGE) {
				Display.getDefault().asyncExec(() -> {
					if (log != null && !log.isDisposed()) { scanMarkers(); }
				});
			}
		};
		ResourcesPlugin.getWorkspace().addResourceChangeListener(markerListener, IResourceChangeEvent.POST_CHANGE);
	}

	// ------------------------------------------------------------- agent

	private synchronized void ensureAgent() throws IOException {
		if (agentProc != null && agentProc.isAlive()) { return; }

		final Properties p = new Properties();
		final Path cfg = Paths.get(System.getProperty("user.home"), ".gama-claude.properties");
		if (Files.exists(cfg)) {
			try (var r = Files.newBufferedReader(cfg, StandardCharsets.UTF_8)) { p.load(r); }
		} else {
			throw new IOException("Thieu file config: " + cfg
					+ "  (can python=..., script=..., key=sk-ant-...)");
		}
		final String python = p.getProperty("python", "python");
		final String script = p.getProperty("script", "");
		if (script.isBlank() || !Files.exists(Paths.get(script))) {
			throw new IOException("'script' trong .gama-claude.properties khong ton tai: " + script);
		}

		final ProcessBuilder pb = new ProcessBuilder(python, script);
		// Ep subprocess di thang Anthropic, bo qua moi BASE_URL/AUTH_TOKEN
		// trong ~/.claude/settings.json (env process co uu tien cao hon).
		// key rong -> CLI dung OAuth login (chay `claude login` 1 lan la xong).
		final var env = pb.environment();
		env.put("ANTHROPIC_BASE_URL", p.getProperty("base_url", "https://api.anthropic.com").trim());
		env.put("ANTHROPIC_AUTH_TOKEN", "");
		env.put("ANTHROPIC_API_KEY", p.getProperty("key", "").trim());
		env.put("CLAUDE_CODE_OAUTH_TOKEN", p.getProperty("oauth_token", "").trim());
		// auto_approve=true -> agent tu Edit khong hoi; mac dinh false: hien the diff cho user duyet
		env.put("GAMA_CLAUDE_AUTO_APPROVE", p.getProperty("auto_approve", "false").trim());
		env.put("ANTHROPIC_DEFAULT_SONNET_MODEL", "");
		env.put("ANTHROPIC_DEFAULT_OPUS_MODEL", "");
		env.put("ANTHROPIC_DEFAULT_HAIKU_MODEL", "");
		pb.redirectErrorStream(false);
		agentProc = pb.start();
		agentIn = new BufferedWriter(new OutputStreamWriter(agentProc.getOutputStream(), StandardCharsets.UTF_8));

		// stdout -> claudeRecv tung dong
		final BufferedReader out = new BufferedReader(
				new InputStreamReader(agentProc.getInputStream(), StandardCharsets.UTF_8));
		new Thread(() -> {
			try {
				String line;
				while ((line = out.readLine()) != null) {
					final String l = line;
					Display.getDefault().asyncExec(() -> pushToChat(l));
					if (l.contains("\"done\"")) { refreshWorkspace(); }
				}
			} catch (final IOException ignored) {}
			Display.getDefault().asyncExec(() ->
					pushToChat("{\"type\":\"error\",\"text\":\"Agent process da thoat. Xem log: " + esc(ERR_LOG) + "\"}"));
		}, "claude-agent-stdout").start();

		// stderr -> file log de debug
		final BufferedReader err = new BufferedReader(
				new InputStreamReader(agentProc.getErrorStream(), StandardCharsets.UTF_8));
		new Thread(() -> {
			try (var w = Files.newBufferedWriter(Paths.get(ERR_LOG), StandardCharsets.UTF_8)) {
				String line;
				while ((line = err.readLine()) != null) { w.write(line); w.newLine(); w.flush(); }
			} catch (final IOException ignored) {}
		}, "claude-agent-stderr").start();
	}

	private void sendChat(final String text) {
		final String af = lastActiveFile;
		final String msg = "{\"type\":\"chat\",\"text\":\"" + esc(text) + "\",\"active_file\":"
				+ (af == null ? "null" : "\"" + esc(af) + "\"")
				+ ",\"workspace_summary\":\"" + esc(lastSummary) + "\""
				+ ",\"diagnostics\":" + lastDiagArray + "}";
		sendRaw(msg, true);
	}

	/** Ghi 1 dong JSON sang agent. spawnIfNeeded=false: agent chua chay thi bo qua. */
	private void sendRaw(final String json, final boolean spawnIfNeeded) {
		try {
			if (agentProc == null || !agentProc.isAlive()) {
				if (!spawnIfNeeded) { return; }
				ensureAgent();
			}
			agentIn.write(json);
			agentIn.newLine();
			agentIn.flush();
		} catch (final IOException e) {
			pushToChat("{\"type\":\"error\",\"text\":\"" + esc(String.valueOf(e.getMessage())) + "\"}");
		}
	}

	/** M3: goi tu quick-fix tren dong gach do - nhet cau hoi vao chat va gui luon. */
	public void askFromMarker(final String file, final int line, final String message) {
		final String prompt = "Sua loi nay giup toi:\n" + file + ":" + line + "\n" + message;
		if (browser != null && !browser.isDisposed()) {
			browser.execute("window.extSend && window.extSend(\"" + escJs(prompt) + "\");");
		}
		sendChat(prompt);
	}

	/** Day 1 dong JSON tu agent sang JS: claudeRecv(<string literal>). */
	private void pushToChat(final String jsonLine) {
		if (browser == null || browser.isDisposed()) { return; }
		browser.execute("window.claudeRecv && window.claudeRecv(\"" + escJs(jsonLine) + "\");");
	}

	/** Sau luot agent: refresh de Eclipse thay file doi tren dia -> Xtext validate lai. */
	private void refreshWorkspace() {
		final WorkspaceJob job = new WorkspaceJob("Refresh sau khi Claude sua file") {
			@Override
			public IStatus runInWorkspace(final IProgressMonitor monitor) {
				try {
					ResourcesPlugin.getWorkspace().getRoot().refreshLocal(IResource.DEPTH_INFINITE, monitor);
				} catch (final CoreException ignored) {}
				return Status.OK_STATUS;
			}
		};
		job.setSystem(true);
		job.schedule();
	}

	// ------------------------------------------------------- diagnostics

	private IFile activeEditorIFile() {
		try {
			final IEditorPart ed = getSite().getPage().getActiveEditor();
			if (ed != null) { return ed.getEditorInput().getAdapter(IFile.class); }
		} catch (final Exception ignored) {}
		return null;
	}

	private static boolean isLibraryNoise(final String path) {
		final String p = path.replace('\\', '/');
		return p.contains("/.eclipse/") || p.contains("/configuration/org.eclipse.osgi/");
	}

	private void scanMarkers() {
		final List<Diag> diags = new ArrayList<>();
		try {
			final IMarker[] ms = ResourcesPlugin.getWorkspace().getRoot()
					.findMarkers(IMarker.PROBLEM, true, IResource.DEPTH_INFINITE);
			for (final IMarker m : ms) {
				final IResource r = m.getResource();
				final String path = r == null ? "?" : String.valueOf(r.getLocation());
				if (!path.toLowerCase().endsWith(".gaml") || isLibraryNoise(path)) { continue; }
				final String proj = r == null || r.getProject() == null ? "" : r.getProject().getName();
				diags.add(new Diag(path, proj,
						m.getAttribute(IMarker.LINE_NUMBER, -1),
						m.getAttribute(IMarker.SEVERITY, -1),
						m.getAttribute(IMarker.MESSAGE, "")));
			}
		} catch (final CoreException e) {
			log.setText("findMarkers loi: " + e);
			return;
		}

		final IFile af = activeEditorIFile();
		final String active = af == null ? null : String.valueOf(af.getLocation());
		final String activeProj = af == null || af.getProject() == null ? null : af.getProject().getName();
		lastActiveFile = active;

		diags.sort((a, b) -> {
			final boolean aa = a.file().equals(active), bb = b.file().equals(active);
			if (aa != bb) { return aa ? -1 : 1; }
			if (a.sev() != b.sev()) { return b.sev() - a.sev(); }
			final int f = a.file().compareTo(b.file());
			return f != 0 ? f : a.line() - b.line();
		});

		// dem toan workspace
		int errs = 0, warns = 0;
		for (final Diag d : diags) {
			if (d.sev() == IMarker.SEVERITY_ERROR) { errs++; } else if (d.sev() == IMarker.SEVERITY_WARNING) { warns++; }
		}
		lastSummary = errs + " errors, " + warns + " warnings across whole workspace";

		// CHI gui cho agent: loi cua project dang mo (context gon, do ton token).
		// Khong co file dang mo -> khong gui gi ngoai con so tong.
		final List<Diag> scoped = new ArrayList<>();
		if (activeProj != null) {
			for (final Diag d : diags) {
				if (activeProj.equals(d.project())) { scoped.add(d); }
				if (scoped.size() >= 120) { break; }
			}
		}
		int sErrs = 0, sWarns = 0;
		final StringBuilder arr = new StringBuilder("[");
		for (int i = 0; i < scoped.size(); i++) {
			final Diag d = scoped.get(i);
			if (d.sev() == IMarker.SEVERITY_ERROR) { sErrs++; } else if (d.sev() == IMarker.SEVERITY_WARNING) { sWarns++; }
			arr.append("{\"file\":\"").append(esc(d.file()))
				.append("\",\"line\":").append(d.line())
				.append(",\"severity\":\"").append(sevText(d.sev()))
				.append("\",\"message\":\"").append(esc(d.msg())).append("\"}");
			if (i < scoped.size() - 1) { arr.append(","); }
		}
		arr.append("]");
		lastDiagArray = arr.toString();

		final String full = "{\"active_file\": " + (active == null ? "null" : "\"" + esc(active) + "\"")
				+ ", \"diagnostics\": " + lastDiagArray + "}";
		try { Files.writeString(Paths.get(OUT_FILE), full); } catch (final IOException ignored) {}
		log.setText("Workspace: " + errs + " err/" + warns + " warn"
				+ " | gui agent (project " + activeProj + "): " + sErrs + " err/" + sWarns + " warn"
				+ " | active: " + active + "\n\n" + full);
	}

	private static String sevText(final int sev) {
		return switch (sev) {
			case IMarker.SEVERITY_ERROR -> "error";
			case IMarker.SEVERITY_WARNING -> "warning";
			default -> "info";
		};
	}

	private static String esc(final String s) {
		return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\r", "").replace("\n", "\\n");
	}

	/** Escape de nhet vao string literal JS trong browser.execute. */
	private static String escJs(final String s) {
		return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\r", "").replace("\n", "\\n");
	}

	// --------------------------------------------------------------- html

	private static String chatHtml() {
		return """
<!doctype html><html><head><meta charset='utf-8'><style>
 body{margin:0;font-family:'Segoe UI',sans-serif;background:#1e1e28;color:#d8d8e0;display:flex;flex-direction:column;height:100vh}
 #hd{padding:8px 12px;background:#26263a;font-weight:600;font-size:13px}
 #hd small{color:#8a8aa0;font-weight:400}
 #msgs{flex:1;overflow-y:auto;padding:10px;font-size:13px}
 .u{background:#2d3f5e;border-radius:10px 10px 2px 10px;padding:8px 10px;margin:6px 0 6px 40px;white-space:pre-wrap}
 .a{background:#2a2a3c;border-radius:10px 10px 10px 2px;padding:8px 10px;margin:6px 40px 6px 0;white-space:pre-wrap}
 .t{color:#7a7a95;font-size:11px;margin:2px 0 2px 8px}
 .e{color:#ff8080;font-size:12px;margin:4px 8px}
 .p{background:#332b18;border:1px solid #8a6d1f;border-radius:8px;margin:6px 8px;padding:8px;font-size:12px}
 .p pre{background:#1a1a26;border-radius:6px;padding:6px;overflow-x:auto;max-height:220px;margin:6px 0;font-size:11.5px;line-height:1.35}
 .p .del{color:#ff9090}.p .ins{color:#7fdc9a}
 .p button{border:0;border-radius:6px;padding:5px 14px;margin-right:8px;cursor:pointer;font-size:12px}
 .ok{background:#2f7d4f;color:#fff}.no{background:#7d2f2f;color:#fff}
 #bar{display:flex;gap:6px;padding:8px;background:#26263a}
 #in{flex:1;background:#1a1a26;color:#e0e0ea;border:1px solid #3a3a52;border-radius:8px;padding:8px;font-size:13px;font-family:inherit;resize:none}
 #btn{background:#5a5adf;color:#fff;border:0;border-radius:8px;padding:0 16px;cursor:pointer;font-size:13px}
 #btn:disabled{background:#3a3a52}
 #stop{background:#8a3a3a;color:#fff;border:0;border-radius:8px;padding:0 12px;cursor:pointer;font-size:13px;display:none}
</style></head><body>
<div id='hd'>Claude Chat <small>(M3 - quick-fix, diff duyet tay, stop)</small></div>
<div id='msgs'></div>
<div id='bar'><textarea id='in' rows='2' placeholder='Hoi ve model GAML dang mo... (Enter gui, Shift+Enter xuong dong)'></textarea><button id='btn'>Gui</button><button id='stop'>Stop</button></div>
<script>
 var msgs=document.getElementById('msgs'),inp=document.getElementById('in'),
     btn=document.getElementById('btn'),stop=document.getElementById('stop'),cur=null;
 function add(cls,txt){var d=document.createElement('div');d.className=cls;d.textContent=txt;msgs.appendChild(d);msgs.scrollTop=msgs.scrollHeight;return d;}
 function busy(b){btn.disabled=b;stop.style.display=b?'block':'none';}
 function send(){var t=inp.value.trim();if(!t)return;add('u',t);inp.value='';cur=null;busy(true);
   if(window.claudeSend){claudeSend(t);}else{add('e','claudeSend chua san sang');busy(false);}}
 btn.onclick=send;
 stop.onclick=function(){if(window.claudeStop){claudeStop();add('t','[stop] da gui yeu cau ngat');}};
 inp.addEventListener('keydown',function(e){if(e.key==='Enter'&&!e.shiftKey){e.preventDefault();send();}});
 // quick-fix tu editor nhet cau hoi vao chat (Java goi truoc khi sendChat)
 window.extSend=function(t){add('u',t);cur=null;busy(true);};
 function showPerm(m){
   var d=document.createElement('div');d.className='p';
   var h=document.createElement('div');h.textContent='Claude muon sua: '+m.file;d.appendChild(h);
   var pre=document.createElement('pre');
   (m.diff||'').split('\\n').forEach(function(l){
     var s=document.createElement('div');s.textContent=l;
     if(l.charAt(0)==='-')s.className='del';else if(l.charAt(0)==='+')s.className='ins';
     pre.appendChild(s);});
   d.appendChild(pre);
   var ok=document.createElement('button');ok.className='ok';ok.textContent='Ap dung';
   var no=document.createElement('button');no.className='no';no.textContent='Tu choi';
   function fin(ans){ok.disabled=no.disabled=true;d.style.opacity=0.55;
     h.textContent=(ans?'DA AP DUNG: ':'DA TU CHOI: ')+m.file;
     if(window.claudePerm)claudePerm(m.id,ans);}
   ok.onclick=function(){fin(true)};no.onclick=function(){fin(false)};
   d.appendChild(ok);d.appendChild(no);
   msgs.appendChild(d);msgs.scrollTop=msgs.scrollHeight;}
 window.claudeRecv=function(raw){
   var m;try{m=JSON.parse(raw);}catch(e){add('e','parse: '+raw);return;}
   if(m.type==='text'){if(!cur){cur=add('a','');}cur.textContent+=(cur.textContent?'\\n':'')+m.text;msgs.scrollTop=msgs.scrollHeight;}
   else if(m.type==='tool'){add('t','[tool] '+m.name);}
   else if(m.type==='permission'){showPerm(m);}
   else if(m.type==='error'){add('e',m.text);busy(false);}
   else if(m.type==='done'){cur=null;busy(false);}
 };
</script></body></html>
""";
	}

	// ------------------------------------------------------------ dispose

	@Override
	public void dispose() {
		if (markerListener != null) {
			ResourcesPlugin.getWorkspace().removeResourceChangeListener(markerListener);
		}
		if (agentProc != null) { agentProc.destroy(); }
		super.dispose();
	}

	@Override
	public void setFocus() {
		if (browser != null && !browser.isDisposed()) { browser.setFocus(); }
	}
}
