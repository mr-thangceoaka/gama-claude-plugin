package gama.ui.claude.views;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.core.resources.IMarker;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IResourceChangeEvent;
import org.eclipse.core.resources.IResourceChangeListener;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.swt.SWT;
import org.eclipse.swt.browser.Browser;
import org.eclipse.swt.custom.SashForm;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Text;
import org.eclipse.core.resources.IFile;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.part.ViewPart;

/**
 * M1: quet marker GAML ra JSON, loc rac thu vien built-in, uu tien file dang mo,
 * tu re-scan khi marker doi. Day la "nhip tim" cho vong auto-fix cua agent o M3.
 * M2 se thay placeholder browser bang chat that noi voi agent Python.
 */
public class ChatView extends ViewPart {

	public static final String ID = "gama.ui.claude.views.ChatView";

	/** File JSON cho agent CLI ben ngoai doc. */
	private static final String OUT_FILE =
			System.getProperty("java.io.tmpdir") + File.separator + "gama_claude_markers.json";

	private Text log;
	private IResourceChangeListener markerListener;

	private record Diag(String file, int line, int sev, String msg) {}

	@Override
	public void createPartControl(final Composite parent) {
		final SashForm sash = new SashForm(parent, SWT.VERTICAL);

		// tren: browser lam khung chat (WebView2 tren Win11)
		try {
			final Browser b = new Browser(sash, SWT.EDGE);
			b.setText(placeholderHtml());
		} catch (final Throwable t1) {
			try {
				final Browser b = new Browser(sash, SWT.NONE);
				b.setText(placeholderHtml());
			} catch (final Throwable t2) {
				final Label l = new Label(sash, SWT.WRAP);
				l.setText("Browser khong tao duoc: " + t2);
			}
		}

		// duoi: panel diagnostics
		final Composite bottom = new Composite(sash, SWT.NONE);
		bottom.setLayout(new GridLayout(1, false));

		final Button scan = new Button(bottom, SWT.PUSH);
		scan.setText("Scan GAML errors → JSON  (tu chay lai khi marker doi)");
		scan.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

		log = new Text(bottom, SWT.MULTI | SWT.READ_ONLY | SWT.V_SCROLL | SWT.H_SCROLL | SWT.BORDER);
		log.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
		log.setText("Dang cho marker dau tien... (sua/save mot file .gaml la thay)");

		scan.addListener(SWT.Selection, e -> scanMarkers());
		sash.setWeights(new int[] { 55, 45 });

		// tu re-scan moi khi markers trong workspace thay doi (save file -> Xtext validate lai)
		markerListener = event -> {
			if (event.getType() == IResourceChangeEvent.POST_CHANGE) {
				Display.getDefault().asyncExec(() -> {
					if (log != null && !log.isDisposed()) { scanMarkers(); }
				});
			}
		};
		ResourcesPlugin.getWorkspace().addResourceChangeListener(markerListener, IResourceChangeEvent.POST_CHANGE);
	}

	/** Duong dan file .gaml dang mo trong editor, hoac null. */
	private String activeEditorFile() {
		try {
			final IEditorPart ed = getSite().getPage().getActiveEditor();
			if (ed != null) {
				final IFile f = ed.getEditorInput().getAdapter(IFile.class);
				if (f != null) { return String.valueOf(f.getLocation()); }
			}
		} catch (final Exception ignored) {}
		return null;
	}

	/** Model cua thu vien built-in GAMA (link tu vung cache .eclipse) -> bo qua. */
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
				final String path = m.getResource() == null ? "?" : String.valueOf(m.getResource().getLocation());
				if (!path.toLowerCase().endsWith(".gaml") || isLibraryNoise(path)) { continue; }
				diags.add(new Diag(path,
						m.getAttribute(IMarker.LINE_NUMBER, -1),
						m.getAttribute(IMarker.SEVERITY, -1),
						m.getAttribute(IMarker.MESSAGE, "")));
			}
		} catch (final CoreException e) {
			log.setText("findMarkers loi: " + e);
			return;
		}

		// file dang mo len dau, roi error truoc warning, roi theo file/dong
		final String active = activeEditorFile();
		diags.sort((a, b) -> {
			final boolean aa = a.file().equals(active), bb = b.file().equals(active);
			if (aa != bb) { return aa ? -1 : 1; }
			if (a.sev() != b.sev()) { return b.sev() - a.sev(); }
			final int f = a.file().compareTo(b.file());
			return f != 0 ? f : a.line() - b.line();
		});

		int errs = 0, warns = 0;
		final StringBuilder json = new StringBuilder();
		json.append("{\n  \"active_file\": ").append(active == null ? "null" : "\"" + esc(active) + "\"")
			.append(",\n  \"diagnostics\": [\n");
		for (int i = 0; i < diags.size(); i++) {
			final Diag d = diags.get(i);
			if (d.sev() == IMarker.SEVERITY_ERROR) { errs++; } else if (d.sev() == IMarker.SEVERITY_WARNING) { warns++; }
			json.append("    {\"file\":\"").append(esc(d.file()))
				.append("\",\"line\":").append(d.line())
				.append(",\"severity\":\"").append(sevText(d.sev()))
				.append("\",\"message\":\"").append(esc(d.msg())).append("\"}")
				.append(i < diags.size() - 1 ? ",\n" : "\n");
		}
		json.append("  ]\n}");

		final String out = json.toString();
		String note;
		try {
			Files.writeString(Paths.get(OUT_FILE), out);
			note = "ghi: " + OUT_FILE;
		} catch (final IOException io) {
			note = "KHONG ghi duoc temp: " + io;
		}
		log.setText(errs + " error, " + warns + " warning (da loc thu vien built-in) | " + note + "\n\n" + out);
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

	private static String placeholderHtml() {
		return "<!doctype html><html><body style='margin:0;font-family:Segoe UI,sans-serif;"
				+ "background:#1e1e28;color:#d8d8e0;display:flex;flex-direction:column;height:100vh'>"
				+ "<div style='padding:10px 14px;background:#26263a;font-weight:600'>Claude Chat "
				+ "<span style='color:#8a8aa0;font-weight:400'>(M1 - diagnostics live, chat vao o M2)</span></div>"
				+ "<div style='flex:1;padding:14px;color:#9a9ab0'>Panel duoi dang theo doi gach do theo thoi gian thuc."
				+ "<br>Sua mot file .gaml va save de thay JSON tu cap nhat.</div>"
				+ "</body></html>";
	}

	@Override
	public void dispose() {
		if (markerListener != null) {
			ResourcesPlugin.getWorkspace().removeResourceChangeListener(markerListener);
		}
		super.dispose();
	}

	@Override
	public void setFocus() {
		if (log != null) { log.setFocus(); }
	}
}
