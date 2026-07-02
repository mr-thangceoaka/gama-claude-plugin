package gama.ui.claude;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IMarker;
import org.eclipse.core.resources.IResource;
import org.eclipse.jface.text.ITextSelection;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IViewPart;
import org.eclipse.ui.handlers.HandlerUtil;

import gama.ui.claude.views.ChatView;

/**
 * M3.5: menu chuot phai trong editor -> "Ask Claude: dong nay".
 * Lay file + dong con tro + marker (neu co) roi ban thang vao chat.
 */
public class AskClaudeHandler extends AbstractHandler {

	@Override
	public Object execute(final ExecutionEvent event) {
		try {
			final IEditorPart ed = HandlerUtil.getActiveEditor(event);
			if (ed == null) { return null; }
			final IFile f = ed.getEditorInput().getAdapter(IFile.class);
			if (f == null || !String.valueOf(f.getLocation()).toLowerCase().endsWith(".gaml")) { return null; }

			int line = -1;
			final ISelection sel = ed.getSite().getSelectionProvider() == null ? null
					: ed.getSite().getSelectionProvider().getSelection();
			if (sel instanceof ITextSelection ts) { line = ts.getStartLine() + 1; }

			// tim marker dung dong do (neu co) de agent biet chinh xac loi gi
			String msg = "";
			try {
				for (final IMarker m : f.findMarkers(IMarker.PROBLEM, true, IResource.DEPTH_ZERO)) {
					if (m.getAttribute(IMarker.LINE_NUMBER, -1) == line) {
						msg = m.getAttribute(IMarker.MESSAGE, "");
						break;
					}
				}
			} catch (final Exception ignored) {}

			final String file = String.valueOf(f.getLocation());
			final int fline = line;
			final String fmsg = msg.isEmpty()
					? "(dong nay khong co marker - hay doc code quanh dong va giai thich/sua neu can)"
					: msg;

			final IViewPart v = HandlerUtil.getActiveWorkbenchWindow(event)
					.getActivePage().showView(ChatView.ID);
			if (v instanceof ChatView cv) { cv.askFromMarker(file, fline, fmsg); }
		} catch (final Exception ignored) {}
		return null;
	}
}
