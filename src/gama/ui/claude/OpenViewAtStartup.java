package gama.ui.claude;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.ui.IStartup;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.progress.UIJob;

/** Tu mo Claude Chat sau khi GAMA len xong, de khoi phai tim view trong menu. */
public class OpenViewAtStartup implements IStartup {

	@Override
	public void earlyStartup() {
		final UIJob job = new UIJob("Open Claude Chat") {
			@Override
			public IStatus runInUIThread(final IProgressMonitor monitor) {
				try {
					final IWorkbench wb = PlatformUI.getWorkbench();
					IWorkbenchWindow win = wb.getActiveWorkbenchWindow();
					if (win == null && wb.getWorkbenchWindowCount() > 0) {
						win = wb.getWorkbenchWindows()[0];
					}
					if (win != null && win.getActivePage() != null) {
						win.getActivePage().showView("gama.ui.claude.views.ChatView");
					}
				} catch (final Exception ignored) {
					// khong mo duoc thi thoi, user van mo tay duoc
				}
				return Status.OK_STATUS;
			}
		};
		job.schedule(4000); // cho GAMA dung xong perspective roi hang mo
	}
}
