package gama.ui.claude.views;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;

import org.eclipse.core.resources.IMarker;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.swt.SWT;
import org.eclipse.swt.browser.Browser;
import org.eclipse.swt.custom.SashForm;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Text;
import org.eclipse.ui.part.ViewPart;

/**
 * M0/M1: view chat placeholder + nut quet marker GAML ra JSON.
 * M2 se thay placeholder bang chat that noi voi agent Python qua stdio.
 */
public class ChatView extends ViewPart {

	public static final String ID = "gama.ui.claude.views.ChatView";

	private Text log;

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

		// duoi: panel marker -> JSON (nen tang cho vong lap fix cua agent)
		final Composite bottom = new Composite(sash, SWT.NONE);
		bottom.setLayout(new GridLayout(1, false));

		final Button scan = new Button(bottom, SWT.PUSH);
		scan.setText("Scan GAML errors → JSON");
		scan.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

		log = new Text(bottom, SWT.MULTI | SWT.READ_ONLY | SWT.V_SCROLL | SWT.H_SCROLL | SWT.BORDER);
		log.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
		log.setText("Bam nut de quet toan bo marker (gach do) cua cac file .gaml trong workspace.");

		scan.addListener(SWT.Selection, e -> scanMarkers());
		sash.setWeights(new int[] { 55, 45 });
	}

	/** Quet IMarker toan workspace, loc file .gaml, xuat JSON + ghi ra %TEMP%. */
	private void scanMarkers() {
		final StringBuilder json = new StringBuilder("[\n");
		int n = 0;
		try {
			final IMarker[] ms = ResourcesPlugin.getWorkspace().getRoot()
					.findMarkers(IMarker.PROBLEM, true, IResource.DEPTH_INFINITE);
			for (final IMarker m : ms) {
				final String path = m.getResource() == null ? "?" : String.valueOf(m.getResource().getLocation());
				if (!path.toLowerCase().endsWith(".gaml")) { continue; }
				if (n++ > 0) { json.append(",\n"); }
				json.append("  {\"file\":\"").append(esc(path))
					.append("\",\"line\":").append(m.getAttribute(IMarker.LINE_NUMBER, -1))
					.append(",\"severity\":").append(m.getAttribute(IMarker.SEVERITY, -1))
					.append(",\"message\":\"").append(esc(m.getAttribute(IMarker.MESSAGE, "")))
					.append("\"}");
			}
		} catch (final CoreException e) {
			log.setText("findMarkers loi: " + e);
			return;
		}
		json.append("\n]");

		// ghi ra temp de agent CLI ben ngoai doc duoc
		final String out = json.toString();
		final String file = System.getProperty("java.io.tmpdir") + File.separator + "gama_claude_markers.json";
		String note = "";
		try {
			Files.writeString(Paths.get(file), out);
			note = "\nDa ghi: " + file;
		} catch (final IOException io) {
			note = "\nKhong ghi duoc file temp: " + io;
		}
		log.setText(n + " marker .gaml" + note + "\n\n" + out);
	}

	private static String esc(final String s) {
		return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\r", "").replace("\n", "\\n");
	}

	private static String placeholderHtml() {
		return "<!doctype html><html><body style='margin:0;font-family:Segoe UI,sans-serif;"
				+ "background:#1e1e28;color:#d8d8e0;display:flex;flex-direction:column;height:100vh'>"
				+ "<div style='padding:10px 14px;background:#26263a;font-weight:600'>Claude Chat "
				+ "<span style='color:#8a8aa0;font-weight:400'>(placeholder M0 - agent se noi vao o M2)</span></div>"
				+ "<div style='flex:1;padding:14px;color:#9a9ab0'>Plugin da chay trong GAMA."
				+ "<br>Buoc sau: noi agent Python qua stdio, chat truc tiep tai day.</div>"
				+ "</body></html>";
	}

	@Override
	public void setFocus() {
		if (log != null) { log.setFocus(); }
	}
}
