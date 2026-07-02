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
import org.eclipse.jface.action.Action;
import org.eclipse.jface.text.ITextSelection;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.swt.SWT;
import org.eclipse.swt.browser.Browser;
import org.eclipse.swt.browser.BrowserFunction;
import org.eclipse.swt.custom.SashForm;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.graphics.ImageLoader;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.DirectoryDialog;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IPartListener2;
import org.eclipse.ui.IWorkbenchPartReference;
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
	private IPartListener2 partListener;

	private Process agentProc;
	private BufferedWriter agentIn;
	/** JSON array cua diagnostics lan quet gan nhat - dinh kem moi message chat. */
	private volatile String lastDiagArray = "[]";
	private volatile String lastActiveFile = null;
	/** M4: duong dan snapshot cho message ke tiep (chup bang nut camera). */
	private volatile String pendingSnapshot = null;
	/** M5: root cua project chua file dang mo - pham vi doc/sua cua agent. */
	private volatile String lastProjectRoot = null;
	/** M5: folder do user tu chon (toolbar "Context folder"); null = theo project. */
	private volatile String customRoot = null;
	/** M5: user vua bam Clear -> dung bao "Agent process exited" khi process chet. */
	private volatile boolean agentKilledByUser = false;

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
			// JS goi: claudeSnap() -> M4: chup cua so GAMA, dinh vao message ke tiep
			new BrowserFunction(browser, "claudeSnap") {
				@Override
				public Object function(final Object[] args) {
					takeSnapshot();
					return null;
				}
			};
			// JS goi: claudeClear() -> M5: xoa hoi thoai + kill agent, phien sau sach context
			new BrowserFunction(browser, "claudeClear") {
				@Override
				public Object function(final Object[] args) {
					clearConversation();
					return null;
				}
			};
		}

		final Composite bottom = new Composite(sash, SWT.NONE);
		bottom.setLayout(new GridLayout(1, false));
		final Button scan = new Button(bottom, SWT.PUSH);
		scan.setText("↻ Refresh diagnostics (auto: view open / tab switch / save)");
		scan.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
		log = new Text(bottom, SWT.MULTI | SWT.READ_ONLY | SWT.V_SCROLL | SWT.H_SCROLL | SWT.BORDER);
		log.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
		log.setText("Scanning...");
		scan.addListener(SWT.Selection, e -> scanMarkers());
		sash.setWeights(new int[] { 75, 25 });

		// toolbar cua view: duong tat khi menu chuot phai bi editor Xtext nuot
		final var tb = getViewSite().getActionBars().getToolBarManager();
		tb.add(new Action("Ask line") {
			@Override
			public void run() { askCurrentLine(); }
		});
		tb.add(new Action("Snapshot") {
			@Override
			public void run() { takeSnapshot(); }
		});
		tb.add(new Action("Context folder") {
			@Override
			public void run() { pickContextFolder(); }
		});
		getViewSite().getActionBars().updateActionBars();

		// tu quet khi: (1) marker doi (save -> Xtext validate lai)
		markerListener = event -> {
			if (event.getType() == IResourceChangeEvent.POST_CHANGE) {
				Display.getDefault().asyncExec(this::scanIfAlive);
			}
		};
		ResourcesPlugin.getWorkspace().addResourceChangeListener(markerListener, IResourceChangeEvent.POST_CHANGE);

		// (2) doi tab editor -> active_file doi
		partListener = new IPartListener2() {
			@Override
			public void partActivated(final IWorkbenchPartReference ref) {
				if (ref.getPart(false) instanceof IEditorPart) {
					Display.getDefault().asyncExec(ChatView.this::scanIfAlive);
				}
			}
		};
		getSite().getPage().addPartListener(partListener);

		// (3) vua mo view: quet lan dau (marker da ton tai tu truoc do)
		Display.getDefault().timerExec(1500, this::scanIfAlive);
	}

	private void scanIfAlive() {
		if (log != null && !log.isDisposed()) { scanMarkers(); }
	}

	// ------------------------------------------------------------- agent

	private synchronized void ensureAgent() throws IOException {
		if (agentProc != null && agentProc.isAlive()) { return; }

		final Properties p = new Properties();
		final Path cfg = Paths.get(System.getProperty("user.home"), ".gama-claude.properties");
		if (Files.exists(cfg)) {
			try (var r = Files.newBufferedReader(cfg, StandardCharsets.UTF_8)) { p.load(r); }
		} else {
			throw new IOException("Missing config file: " + cfg
					+ "  (needs python=..., script=..., oauth_token= or key=)");
		}
		final String python = p.getProperty("python", "python");
		final String script = p.getProperty("script", "");
		if (script.isBlank() || !Files.exists(Paths.get(script))) {
			throw new IOException("'script' in .gama-claude.properties does not exist: " + script);
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
		// model= trong properties; de trong -> ide_agent mac dinh claude-opus-4-8
		env.put("GAMA_CLAUDE_MODEL", p.getProperty("model", "").trim());
		// M6: cho agent goi gama-headless (validate/run). headless_dir= de override.
		env.put("GAMA_HEADLESS_DIR", headlessDir(p));
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
			if (agentKilledByUser) {
				agentKilledByUser = false;
				return;
			}
			Display.getDefault().asyncExec(() ->
					pushToChat("{\"type\":\"error\",\"text\":\"Agent process exited. See log: " + esc(ERR_LOG) + "\"}"));
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

	/** M6: thu muc headless cua GAMA - tu do theo vi tri cai dat, properties de override. */
	private static String headlessDir(final Properties p) {
		final String cfg = p.getProperty("headless_dir", "").trim();
		if (!cfg.isEmpty()) { return cfg; }
		try {
			final var url = org.eclipse.core.runtime.Platform.getInstallLocation().getURL();
			final File h = new File(new File(url.getPath()), "headless");
			if (h.isDirectory()) { return h.getAbsolutePath(); }
		} catch (final Exception ignored) {}
		return "";
	}

	private void sendChat(final String text) {
		final String af = lastActiveFile;
		final String snap = pendingSnapshot;
		pendingSnapshot = null;
		// M5/M6: pham vi context = folder user chon > project cua file dang mo
		// > root workspace (de "tao project moi" chay duoc khi chua mo file nao)
		String root = customRoot != null ? customRoot : lastProjectRoot;
		if (root == null) {
			final var loc = ResourcesPlugin.getWorkspace().getRoot().getLocation();
			if (loc != null) { root = String.valueOf(loc); }
		}
		final String msg = "{\"type\":\"chat\",\"text\":\"" + esc(text) + "\",\"active_file\":"
				+ (af == null ? "null" : "\"" + esc(af) + "\"")
				+ ",\"workspace_summary\":\"" + esc(lastSummary) + "\""
				+ (snap == null ? "" : ",\"snapshot\":\"" + esc(snap) + "\"")
				+ (root == null ? "" : ",\"project_root\":\"" + esc(root) + "\"")
				+ ",\"diagnostics\":" + lastDiagArray + "}";
		sendRaw(msg, true);
	}

	/** M5: kill agent de phien ke tiep bat dau voi context sach (JS da tu xoa UI). */
	private synchronized void clearConversation() {
		pendingSnapshot = null;
		if (agentProc != null && agentProc.isAlive()) {
			agentKilledByUser = true;
			agentProc.destroy();
		}
		agentProc = null;
		agentIn = null;
	}

	/** M5: cho user tu chon folder lam pham vi doc/sua; Cancel = quay ve theo project. */
	private void pickContextFolder() {
		final Shell shell = browser != null ? browser.getShell() : Display.getDefault().getActiveShell();
		if (shell == null) { return; }
		final DirectoryDialog dlg = new DirectoryDialog(shell);
		dlg.setText("Claude context folder");
		dlg.setMessage("Choose the folder Claude may read and edit."
				+ "\nCancel = follow the active file's project automatically.");
		if (customRoot != null) { dlg.setFilterPath(customRoot); }
		final String dir = dlg.open();
		if (dir == null) {
			customRoot = null;
			pushToChat("{\"type\":\"info\",\"text\":\"Context folder reset - following the active file's project.\"}");
		} else {
			customRoot = dir;
			pushToChat("{\"type\":\"info\",\"text\":\"Context folder set to: " + esc(dir)
					+ " - Claude reads and edits inside this folder from the next message.\"}");
		}
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
		final String prompt = "Fix this for me:\n" + file + ":" + line + "\n" + message;
		if (browser != null && !browser.isDisposed()) {
			browser.execute("window.extSend && window.extSend(\"" + escJs(prompt) + "\");");
		}
		sendChat(prompt);
	}

	/** M3.5: lay file + dong con tro cua editor dang mo, kem marker neu co, roi hoi Claude. */
	public void askCurrentLine() {
		try {
			final IEditorPart ed = getSite().getPage().getActiveEditor();
			if (ed == null) { return; }
			final IFile f = ed.getEditorInput().getAdapter(IFile.class);
			if (f == null || !String.valueOf(f.getLocation()).toLowerCase().endsWith(".gaml")) { return; }
			int line = -1;
			final ISelection sel = ed.getSite().getSelectionProvider() == null ? null
					: ed.getSite().getSelectionProvider().getSelection();
			if (sel instanceof ITextSelection ts) { line = ts.getStartLine() + 1; }
			String msg = "";
			try {
				for (final IMarker m : f.findMarkers(IMarker.PROBLEM, true, IResource.DEPTH_ZERO)) {
					if (m.getAttribute(IMarker.LINE_NUMBER, -1) == line) {
						msg = m.getAttribute(IMarker.MESSAGE, "");
						break;
					}
				}
			} catch (final Exception ignored) {}
			askFromMarker(String.valueOf(f.getLocation()), line, msg.isEmpty()
					? "(no marker on this line - read the surrounding code, explain and fix if needed)"
					: msg);
		} catch (final Exception ignored) {}
	}

	/** M4: chup toan bo cua so GAMA ra PNG, dinh kem vao message ke tiep. */
	private void takeSnapshot() {
		try {
			final Shell shell = browser != null ? browser.getShell() : Display.getDefault().getActiveShell();
			if (shell == null) { return; }
			final Rectangle b = shell.getBounds();
			final Image img = new Image(shell.getDisplay(), b.width, b.height);
			final GC gc = new GC(shell);
			gc.copyArea(img, 0, 0);
			gc.dispose();
			final String path = System.getProperty("java.io.tmpdir") + File.separator
					+ "gama_claude_snap_" + System.currentTimeMillis() + ".png";
			final ImageLoader loader = new ImageLoader();
			loader.data = new ImageData[] { img.getImageData() };
			loader.save(path, SWT.IMAGE_PNG);
			img.dispose();
			pendingSnapshot = path;
			pushToChat("{\"type\":\"info\",\"text\":\"Snapshot captured - it will be attached to your next message.\"}");
		} catch (final Exception e) {
			pushToChat("{\"type\":\"error\",\"text\":\"Snapshot failed: " + esc(String.valueOf(e.getMessage())) + "\"}");
		}
	}

	/** Day 1 dong JSON tu agent sang JS: claudeRecv(<string literal>). */
	private void pushToChat(final String jsonLine) {
		if (browser == null || browser.isDisposed()) { return; }
		browser.execute("window.claudeRecv && window.claudeRecv(\"" + escJs(jsonLine) + "\");");
	}

	/** Sau luot agent: refresh de Eclipse thay file doi tren dia -> Xtext validate lai.
	 *  M6: kem auto-import - folder moi co .project trong workspace (agent vua tao)
	 *  duoc dua vao navigator luon, khoi phai File > Import thu cong. */
	private void refreshWorkspace() {
		final WorkspaceJob job = new WorkspaceJob("Refresh after Claude edits") {
			@Override
			public IStatus runInWorkspace(final IProgressMonitor monitor) {
				importNewProjects(monitor);
				try {
					ResourcesPlugin.getWorkspace().getRoot().refreshLocal(IResource.DEPTH_INFINITE, monitor);
				} catch (final CoreException ignored) {}
				return Status.OK_STATUS;
			}
		};
		job.setSystem(true);
		job.schedule();
	}

	private void importNewProjects(final IProgressMonitor monitor) {
		final var wsRoot = ResourcesPlugin.getWorkspace().getRoot();
		if (wsRoot.getLocation() == null) { return; }
		final File[] kids = wsRoot.getLocation().toFile().listFiles();
		if (kids == null) { return; }
		for (final File k : kids) {
			final File dot = new File(k, ".project");
			if (!k.isDirectory() || !dot.isFile()) { continue; }
			try {
				final var desc = ResourcesPlugin.getWorkspace().loadProjectDescription(
						org.eclipse.core.runtime.Path.fromOSString(dot.getAbsolutePath()));
				final var proj = wsRoot.getProject(desc.getName());
				if (!proj.exists()) {
					proj.create(desc, monitor);
					pushLater("{\"type\":\"info\",\"text\":\"Imported new project into the navigator: "
							+ esc(desc.getName()) + "\"}");
				}
				if (!proj.isOpen()) { proj.open(monitor); }
			} catch (final Exception ignored) {}
		}
	}

	/** pushToChat tu WorkspaceJob (khong phai UI thread). */
	private void pushLater(final String jsonLine) {
		Display.getDefault().asyncExec(() -> pushToChat(jsonLine));
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
			log.setText("findMarkers failed: " + e);
			return;
		}

		final IFile af = activeEditorIFile();
		final String active = af == null ? null : String.valueOf(af.getLocation());
		final String activeProj = af == null || af.getProject() == null ? null : af.getProject().getName();
		lastActiveFile = active;
		lastProjectRoot = af == null || af.getProject() == null || af.getProject().getLocation() == null
				? null : String.valueOf(af.getProject().getLocation());

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

		// human-readable panel, khong JSON
		final StringBuilder t = new StringBuilder();
		t.append("Workspace: ").append(errs).append(" error / ").append(warns).append(" warning");
		if (activeProj != null) {
			t.append("   |   sent to agent (").append(activeProj).append("): ")
			 .append(sErrs).append(" error / ").append(sWarns).append(" warning");
		}
		t.append("\nActive: ").append(active == null ? "(no .gaml file open)" : active).append("\n");
		String lastFile = null;
		int shown = 0;
		for (final Diag d : scoped) {
			if (shown++ >= 50) { t.append("  ... (").append(scoped.size() - 50).append(" more)\n"); break; }
			if (!d.file().equals(lastFile)) {
				lastFile = d.file();
				t.append("\n").append(shortName(d.file())).append(d.file().equals(active) ? "  (open)" : "").append("\n");
			}
			final String m = d.msg().length() > 110 ? d.msg().substring(0, 110) + "..." : d.msg();
			t.append(String.format("  L%-5d %-7s %s%n", d.line(), sevText(d.sev()), m));
		}
		log.setText(t.toString());
	}

	private static String shortName(final String path) {
		final int i = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
		return i < 0 ? path : path.substring(i + 1);
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
 :root{--bg:#16161e;--panel:#1e1e2a;--line:#2c2c3d;--tx:#e2e2ec;--dim:#8b8ba3;
       --acc:#7c7cf0;--user:#2f3d63;--good:#3fb26a;--bad:#c05252;--warnb:#8a6d1f}
 *{box-sizing:border-box}
 body{margin:0;font-family:'Segoe UI Variable Text','Segoe UI',sans-serif;background:var(--bg);
      color:var(--tx);display:flex;flex-direction:column;height:100vh;font-size:13px}
 ::-webkit-scrollbar{width:9px}::-webkit-scrollbar-thumb{background:#33334a;border-radius:5px}
 ::-webkit-scrollbar-track{background:transparent}
 #hd{display:flex;align-items:center;gap:8px;padding:9px 14px;background:var(--panel);
     border-bottom:1px solid var(--line)}
 #dot{width:9px;height:9px;border-radius:50%;background:#4a4a63}
 #dot.on{background:var(--good);box-shadow:0 0 6px var(--good);animation:pl 1.2s ease-in-out infinite}
 @keyframes pl{50%{opacity:.45}}
 #hd b{font-size:13.5px;font-weight:600}
 #hd span{color:var(--dim);font-size:11.5px;margin-left:auto}
 #msgs{flex:1;overflow-y:auto;padding:14px 12px 6px}
 .row{margin:0 0 12px}
 .lbl{font-size:10.5px;color:var(--dim);margin:0 4px 3px;letter-spacing:.4px;text-transform:uppercase}
 .u .bd{background:var(--user);border-radius:12px 12px 3px 12px;padding:8px 11px;
        margin-left:48px;white-space:pre-wrap;line-height:1.45}
 .u .lbl{text-align:right}
 .a .bd{background:var(--panel);border:1px solid var(--line);border-radius:12px 12px 12px 3px;
        padding:9px 12px;margin-right:36px;line-height:1.5;word-wrap:break-word}
 .a .bd pre{background:#12121a;border:1px solid var(--line);border-radius:7px;padding:8px;
        overflow-x:auto;font-size:12px;line-height:1.4;margin:6px 0}
 .a .bd code{background:#12121a;border-radius:4px;padding:1px 5px;font-size:12px}
 .tools{display:flex;flex-wrap:wrap;gap:5px;margin:0 0 10px 4px}
 .chip{background:#232336;border:1px solid var(--line);color:var(--dim);border-radius:20px;
       padding:2px 10px;font-size:11px}
 .e{color:#ff8f8f;font-size:12px;margin:4px 6px 10px;padding:7px 10px;background:#2c1a1a;
    border:1px solid #5c2e2e;border-radius:8px}
 .i{color:var(--dim);font-size:11.5px;margin:2px 6px 10px;padding:5px 10px;background:#20202e;
    border:1px solid var(--line);border-radius:8px}
 .p{background:#26210f;border:1px solid var(--warnb);border-radius:10px;margin:0 8px 12px 0;padding:10px}
 .p .ph{font-size:12px;font-weight:600;margin-bottom:2px}
 .p .pf{font-size:11px;color:var(--dim);word-break:break-all}
 .p pre{background:#12121a;border-radius:7px;padding:7px;overflow:auto;max-height:230px;
        margin:8px 0;font-size:11.5px;line-height:1.4}
 .p .del{color:#ff9c9c}.p .ins{color:#83e0a3}
 .p button{border:0;border-radius:7px;padding:6px 16px;margin-right:8px;cursor:pointer;
        font-size:12px;font-weight:600}
 .ok{background:var(--good);color:#08130c}.no{background:transparent;color:#ff9c9c;
        border:1px solid #5c2e2e !important}
 .th .bd{color:var(--dim)}
 .th .dts:after{content:'';animation:dt 1.4s steps(4) infinite}
 @keyframes dt{0%{content:''}25%{content:'.'}50%{content:'..'}75%{content:'...'}}
 #bar{display:flex;gap:8px;padding:10px 12px;background:var(--panel);border-top:1px solid var(--line)}
 #in{flex:1;background:#13131c;color:var(--tx);border:1px solid var(--line);border-radius:10px;
     padding:9px 12px;font-size:13px;font-family:inherit;resize:none;outline:none;line-height:1.4}
 #in:focus{border-color:var(--acc)}
 #btn,#stop,#snap{border:0;border-radius:10px;width:44px;cursor:pointer;font-size:15px;flex:none}
 #btn{background:var(--acc);color:#fff}
 #btn:disabled{background:#33334a;color:#66667f;cursor:default}
 #stop{background:var(--bad);color:#fff;display:none}
 #snap{background:#232336;border:1px solid var(--line);color:var(--tx)}
 #clr{background:transparent;border:1px solid var(--line);color:var(--dim);border-radius:7px;
      cursor:pointer;padding:2px 9px;font-size:13px;margin-left:8px}
 #clr:hover{color:#ff9c9c;border-color:#5c2e2e}
</style></head><body>
<div id='hd'><div id='dot'></div><b>Claude</b>&nbsp;<span style='color:var(--dim);margin-left:0'>GAMA Copilot</span><span>v0.5</span><button id='clr' title='Clear conversation and start a fresh session'>&#128465;</button></div>
<div id='msgs'></div>
<div id='bar'><textarea id='in' rows='1' placeholder='Ask about the open GAML model...  (Enter to send / Shift+Enter for a new line)'></textarea><button id='snap' title='Attach a window snapshot to your next message'>&#128247;</button><button id='btn' title='Send'>&#10148;</button><button id='stop' title='Interrupt this turn'>&#9632;</button></div>
<script>
 var msgs=document.getElementById('msgs'),inp=document.getElementById('in'),
     btn=document.getElementById('btn'),stop=document.getElementById('stop'),
     snap=document.getElementById('snap'),clr=document.getElementById('clr'),
     dot=document.getElementById('dot'),
     cur=null,think=null,toolRow=null;
 function scr(){msgs.scrollTop=msgs.scrollHeight;}
 function md(t){
   t=t.replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;');
   t=t.replace(/```([\\s\\S]*?)```/g,function(_,c){return '<pre>'+c.replace(/^[a-z]*\\n/,'')+'</pre>';});
   t=t.replace(/`([^`]+)`/g,'<code>$1</code>');
   t=t.replace(/\\*\\*([^*]+)\\*\\*/g,'<b>$1</b>');
   return t.replace(/\\n/g,'<br>');}
 function row(cls,label){var r=document.createElement('div');r.className='row '+cls;
   if(label){var l=document.createElement('div');l.className='lbl';l.textContent=label;r.appendChild(l);}
   var b=document.createElement('div');b.className='bd';r.appendChild(b);
   msgs.appendChild(r);scr();return{r:r,b:b};}
 function addUser(t){var x=row('u','You');x.b.textContent=t;}
 function addErr(t){var d=document.createElement('div');d.className='e';d.textContent=t;msgs.appendChild(d);scr();}
 function addInfo(t){var d=document.createElement('div');d.className='i';d.textContent=t;msgs.appendChild(d);scr();}
 function busy(on){btn.style.display=on?'none':'block';stop.style.display=on?'block':'none';
   dot.className=on?'on':'';if(on){think=row('a th','Claude');think.b.innerHTML="Thinking<span class='dts'></span>";}
   else if(think){think.r.remove();think=null;}toolRow=null;}
 function clearThink(){if(think){think.r.remove();think=null;}}
 function send(){var t=inp.value.trim();if(!t)return;addUser(t);inp.value='';autoh();cur=null;busy(true);
   if(window.claudeSend){claudeSend(t);}else{addErr('Bridge to Java not ready');busy(false);}}
 btn.onclick=send;
 stop.onclick=function(){if(window.claudeStop)claudeStop();};
 snap.onclick=function(){if(window.claudeSnap)claudeSnap();};
 clr.onclick=function(){msgs.innerHTML='';cur=null;think=null;toolRow=null;busy(false);
   if(window.claudeClear)claudeClear();
   addInfo('Conversation cleared - your next message starts a fresh session.');};
 function autoh(){inp.style.height='auto';inp.style.height=Math.min(inp.scrollHeight,110)+'px';}
 inp.addEventListener('input',autoh);
 inp.addEventListener('keydown',function(e){if(e.key==='Enter'&&!e.shiftKey){e.preventDefault();send();}});
 window.extSend=function(t){addUser(t);cur=null;busy(true);};
 function showPerm(m){
   clearThink();
   var d=document.createElement('div');d.className='p';
   var h=document.createElement('div');h.className='ph';h.textContent='Claude proposes an edit';d.appendChild(h);
   var pf=document.createElement('div');pf.className='pf';pf.textContent=m.file;d.appendChild(pf);
   var pre=document.createElement('pre');
   (m.diff||'').split('\\n').forEach(function(l){
     var s=document.createElement('div');s.textContent=l;
     if(l.charAt(0)==='-')s.className='del';else if(l.charAt(0)==='+')s.className='ins';
     pre.appendChild(s);});
   d.appendChild(pre);
   var ok=document.createElement('button');ok.className='ok';ok.textContent='Apply';
   var no=document.createElement('button');no.className='no';no.textContent='Reject';
   function fin(ans){ok.disabled=no.disabled=true;d.style.opacity=.55;
     h.textContent=ans?'Applied':'Rejected';
     if(window.claudePerm)claudePerm(m.id,ans);}
   ok.onclick=function(){fin(true)};no.onclick=function(){fin(false)};
   d.appendChild(ok);d.appendChild(no);
   msgs.appendChild(d);scr();}
 window.claudeRecv=function(raw){
   var m;try{m=JSON.parse(raw);}catch(e){addErr('parse: '+raw);return;}
   if(m.type==='text'){clearThink();toolRow=null;
     if(!cur){cur=row('a','Claude');cur.raw='';}
     cur.raw+=(cur.raw?'\\n':'')+m.text;cur.b.innerHTML=md(cur.raw);scr();}
   else if(m.type==='tool'){clearThink();cur=null;
     if(!toolRow){toolRow=document.createElement('div');toolRow.className='tools';msgs.appendChild(toolRow);}
     var c=document.createElement('span');c.className='chip';c.textContent='* '+m.name;
     toolRow.appendChild(c);scr();}
   else if(m.type==='permission'){showPerm(m);}
   else if(m.type==='info'){addInfo(m.text);}
   else if(m.type==='error'){clearThink();addErr(m.text);busy(false);}
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
		if (partListener != null) {
			try { getSite().getPage().removePartListener(partListener); } catch (final Exception ignored) {}
		}
		if (agentProc != null) { agentProc.destroy(); }
		super.dispose();
	}

	@Override
	public void setFocus() {
		if (browser != null && !browser.isDisposed()) { browser.setFocus(); }
	}
}
