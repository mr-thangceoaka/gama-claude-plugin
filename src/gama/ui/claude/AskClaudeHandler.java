package gama.ui.claude;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.ui.IViewPart;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.handlers.HandlerUtil;

import gama.ui.claude.views.ChatView;

/** Ctrl+Alt+C / menu / toolbar -> hoi Claude ve dong con tro trong editor. */
public class AskClaudeHandler extends AbstractHandler {

	@Override
	public Object execute(final ExecutionEvent event) {
		try {
			final IWorkbenchPage page = HandlerUtil.getActiveWorkbenchWindow(event).getActivePage();
			if (page == null) { return null; }
			final IViewPart v = page.showView(ChatView.ID);
			if (v instanceof ChatView cv) { cv.askCurrentLine(); }
		} catch (final Exception ignored) {}
		return null;
	}
}
