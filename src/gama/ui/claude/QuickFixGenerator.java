package gama.ui.claude;

import org.eclipse.core.resources.IMarker;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IMarkerResolution;
import org.eclipse.ui.IMarkerResolutionGenerator2;
import org.eclipse.ui.IViewPart;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;

import gama.ui.claude.views.ChatView;

/** M3: them "Ask Claude" vao menu quick-fix (Ctrl+1) cua moi marker tren file .gaml. */
public class QuickFixGenerator implements IMarkerResolutionGenerator2 {

	@Override
	public boolean hasResolutions(final IMarker marker) {
		try {
			return marker.getResource() != null
					&& String.valueOf(marker.getResource().getLocation()).toLowerCase().endsWith(".gaml");
		} catch (final Exception e) {
			return false;
		}
	}

	@Override
	public IMarkerResolution[] getResolutions(final IMarker marker) {
		if (!hasResolutions(marker)) { return new IMarkerResolution[0]; }
		return new IMarkerResolution[] { new IMarkerResolution() {

			@Override
			public String getLabel() {
				return "Ask Claude: fix this error";
			}

			@Override
			public void run(final IMarker m) {
				final String file = String.valueOf(m.getResource().getLocation());
				final int line = m.getAttribute(IMarker.LINE_NUMBER, -1);
				final String msg = m.getAttribute(IMarker.MESSAGE, "");
				Display.getDefault().asyncExec(() -> {
					try {
						final IWorkbenchWindow win = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
						if (win == null || win.getActivePage() == null) { return; }
						final IViewPart v = win.getActivePage().showView(ChatView.ID);
						if (v instanceof ChatView cv) { cv.askFromMarker(file, line, msg); }
					} catch (final Exception ignored) {}
				});
			}
		} };
	}
}
