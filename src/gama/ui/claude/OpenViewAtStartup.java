package gama.ui.claude;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.ui.IPerspectiveDescriptor;
import org.eclipse.ui.IPerspectiveListener;
import org.eclipse.ui.IStartup;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.progress.UIJob;

/** Tu mo Claude Chat sau khi GAMA len xong, de khoi phai tim view trong menu.
 *  M8.1: GAMA doi perspective Modeling <-> Simulation khi chay experiment, va
 *  view chi ton tai trong perspective da mo no -> nghe perspectiveActivated de
 *  trieu hoi view vao MOI perspective (khong cuop focus). Nho vay khung chat
 *  van o do khi mo hinh dang chay - luc can sim_status/sim_eval nhat. */
public class OpenViewAtStartup implements IStartup {

	private static final String VIEW_ID = "gama.ui.claude.views.ChatView";

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
					if (win == null) { return Status.OK_STATUS; }
					showChat(win.getActivePage());
					// theo chan moi lan doi perspective (chay/dong experiment)
					win.addPerspectiveListener(new IPerspectiveListener() {
						@Override
						public void perspectiveActivated(final IWorkbenchPage page,
								final IPerspectiveDescriptor desc) {
							// cho GAMA dan layout simulation xong roi hang chen view
							page.getWorkbenchWindow().getShell().getDisplay()
									.timerExec(600, () -> showChat(page));
						}

						@Override
						public void perspectiveChanged(final IWorkbenchPage page,
								final IPerspectiveDescriptor desc, final String changeId) {}
					});
				} catch (final Exception ignored) {
					// khong mo duoc thi thoi, user van mo tay duoc
				}
				return Status.OK_STATUS;
			}
		};
		job.schedule(4000); // cho GAMA dung xong perspective roi hang mo
	}

	private static void showChat(final IWorkbenchPage page) {
		if (page == null) { return; }
		try {
			// VIEW_VISIBLE: hien ra nhung khong lay focus cua display/editor
			page.showView(VIEW_ID, null, IWorkbenchPage.VIEW_VISIBLE);
		} catch (final Exception ignored) {}
	}
}
