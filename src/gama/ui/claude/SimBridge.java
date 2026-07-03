package gama.ui.claude;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import javax.imageio.ImageIO;

import gama.core.common.util.StringUtils;
import gama.core.kernel.experiment.IExperimentPlan;
import gama.core.kernel.experiment.IParameter;
import gama.core.kernel.experiment.ITopLevelAgent;
import gama.core.kernel.simulation.SimulationAgent;
import gama.core.outputs.IOutputManager;
import gama.core.outputs.LayeredDisplayOutput;
import gama.core.outputs.MonitorOutput;
import gama.core.runtime.GAMA;
import gama.core.runtime.IScope;
import gama.gaml.compilation.GAML;
import gama.gaml.expressions.IExpression;

/**
 * M8: cau noi vao simulation DANG CHAY trong IDE. Agent goi qua stdio
 * ({"type":"sim_cmd","op":...} -> {"type":"sim_reply"}), ChatView dua ve day.
 *
 * eval dung dung co che cua Interactive Console trong GAMA: compileExpression
 * tren top-level agent (statement nhu `ask`/`create` duoc GAML goi thanh
 * temporary action nen chay duoc), evaluate tren scope copy, toGaml ket qua,
 * xong refreshAllOutputs de user THAY thay doi ngay tren display.
 * Chay o worker thread (khong phai UI thread) - giong console cua GAMA.
 */
public final class SimBridge {

	private SimBridge() {}

	private static final String NO_EXP =
			"(no experiment is running in the IDE - ask the user to launch one,"
			+ " or use run_experiment_headless instead)";

	/** 1 dong mo ta sim dang chay, dinh kem moi message chat; null = khong co. */
	public static String statusBrief() {
		final IExperimentPlan plan = GAMA.getExperiment();
		if (plan == null) { return null; }
		String name = "?";
		try { name = plan.getAgent() == null ? "?" : plan.getAgent().getName(); } catch (final Throwable ignored) {}
		final SimulationAgent sim = plan.getCurrentSimulation();
		final String cyc = sim == null || sim.dead() ? "no live simulation"
				: "cycle " + sim.getClock().getCycle();
		return "experiment '" + name + "' - " + (GAMA.isPaused() ? "PAUSED" : "RUNNING") + ", " + cyc;
	}

	/** Bo may chinh: op = status | pause | resume | step | step_back | reload | eval | snapshot. */
	public static String handle(final String op, final String code, final int steps,
			final String display, final Supplier<String> windowCapture) {
		try {
			return switch (op == null ? "" : op) {
				case "status" -> status();
				case "pause", "resume", "step", "step_back", "reload" -> control(op, steps);
				case "eval" -> eval(code);
				case "snapshot" -> snapshot(display, windowCapture);
				default -> "(unknown sim op: " + op + ")";
			};
		} catch (final Throwable t) {
			return "SIM ERROR (" + op + "): " + t.getClass().getSimpleName() + ": " + t.getMessage();
		}
	}

	// ------------------------------------------------------------- status

	private static String status() {
		final IExperimentPlan plan = GAMA.getExperiment();
		if (plan == null) { return NO_EXP; }
		final StringBuilder b = new StringBuilder();
		b.append(statusBrief()).append('\n');
		final SimulationAgent sim = plan.getCurrentSimulation();
		final IScope scope = GAMA.getRuntimeScope();

		try {
			final var params = plan.getParameters();
			if (params != null && !params.isEmpty()) {
				b.append("parameters:\n");
				for (final var e : params.entrySet()) {
					final IParameter p = e.getValue();
					String v;
					try { v = StringUtils.toGaml(p.value(scope), true); } catch (final Throwable t) { v = "?"; }
					b.append("  ").append(p.getName()).append(" = ").append(v).append('\n');
				}
			}
		} catch (final Throwable ignored) {}

		try {
			final List<String> displays = new ArrayList<>();
			final List<String> monitors = new ArrayList<>();
			for (final IOutputManager om : plan.getActiveOutputManagers()) {
				for (final MonitorOutput m : om.getMonitors()) {
					monitors.add("  " + m.getName() + " = "
							+ StringUtils.toGaml(m.getLastValue(), true));
				}
				om.forEach((id, out) -> {
					if (out instanceof LayeredDisplayOutput d) { displays.add(d.getName()); }
				});
			}
			if (!monitors.isEmpty()) {
				b.append("monitors:\n").append(String.join("\n", monitors)).append('\n');
			}
			if (!displays.isEmpty()) {
				b.append("displays: ").append(String.join(", ", displays)).append('\n');
			}
		} catch (final Throwable ignored) {}

		if (sim != null && !sim.dead()) {
			b.append("(use sim_eval to inspect anything else, e.g. `length(my_species)`)");
		}
		return b.toString();
	}

	// ------------------------------------------------------------ control

	private static String control(final String op, final int steps) {
		if (GAMA.getExperiment() == null) { return NO_EXP; }
		switch (op) {
			case "pause" -> GAMA.pauseFrontmostExperiment(true);
			case "resume" -> GAMA.resumeFrontmostExperiment(true);
			case "reload" -> GAMA.reloadFrontmostExperiment(true);
			case "step" -> {
				final int n = Math.max(1, Math.min(steps, 1000));
				for (int i = 0; i < n; i++) { GAMA.stepFrontmostExperiment(true); }
			}
			case "step_back" -> GAMA.stepBackFrontmostExperiment(true);
			default -> {}
		}
		final String s = statusBrief();
		return op + " done -> " + (s == null ? "(experiment closed)" : s);
	}

	// --------------------------------------------------------------- eval

	/** Danh gia bieu thuc HOAC statement GAML trong sim dang chay (co che
	 *  Interactive Console). Tra ve gia tri dang GAML. */
	private static String eval(final String code) {
		if (code == null || code.isBlank()) { return "(empty expression)"; }
		final ITopLevelAgent agent = GAMA.getCurrentTopLevelAgent();
		if (agent == null || agent.dead()) { return NO_EXP; }
		final IScope scope = agent.getScope().copy("In Claude sim_eval");
		boolean ok = false;
		try {
			final IExpression expr = GAML.compileExpression(code, agent, false);
			if (expr == null) { return "(could not compile: " + code + ")"; }
			final Object value = scope.evaluate(expr, agent).getValue();
			ok = true;
			return StringUtils.toGaml(value, true);
		} catch (final Exception e) {
			return "GAML error: " + e.getMessage();
		} finally {
			try { agent.getSpecies().removeTemporaryAction(); } catch (final Throwable ignored) {}
			GAMA.releaseScope(scope);
			if (ok && GAMA.getExperiment() != null) {
				// user (va snapshot ke tiep) thay ngay hieu ung cua statement
				try { GAMA.getExperiment().refreshAllOutputs(); } catch (final Throwable ignored) {}
			}
		}
	}

	// ----------------------------------------------------------- snapshot

	/** Chup TUNG display cua sim dang chay ra PNG (khong can GUI thread voi
	 *  java2d; OpenGL co the tra null -> fallback chup ca cua so GAMA). */
	private static String snapshot(final String filter, final Supplier<String> windowCapture) {
		final IExperimentPlan plan = GAMA.getExperiment();
		if (plan == null) { return NO_EXP; }
		final File dir = new File(System.getProperty("java.io.tmpdir"), "gama_claude_sim");
		dir.mkdirs();
		final List<String> files = new ArrayList<>();
		final List<String> failed = new ArrayList<>();
		try {
			for (final IOutputManager om : plan.getActiveOutputManagers()) {
				om.forEach((id, out) -> {
					if (!(out instanceof LayeredDisplayOutput d)) { return; }
					final String name = d.getName() == null ? "display" : d.getName();
					if (filter != null && !filter.isBlank()
							&& !name.toLowerCase().contains(filter.toLowerCase())) {
						return;
					}
					try {
						final var img = d.getImage();
						if (img == null) { failed.add(name); return; }
						final File f = new File(dir, name.replaceAll("[^A-Za-z0-9_-]", "_")
								+ "_" + System.currentTimeMillis() + ".png");
						ImageIO.write(img, "png", f);
						files.add(f.getAbsolutePath());
					} catch (final Throwable t) {
						failed.add(name + " (" + t.getMessage() + ")");
					}
				});
			}
		} catch (final Throwable ignored) {}

		final StringBuilder b = new StringBuilder();
		if (!files.isEmpty()) {
			b.append("live display snapshots (use Read on these paths to SEE them):\n");
			b.append(String.join("\n", files));
		}
		if (!failed.isEmpty()) {
			if (b.length() > 0) { b.append('\n'); }
			b.append("(no direct image for: ").append(String.join(", ", failed)).append(")");
		}
		if (files.isEmpty() && windowCapture != null) {
			// OpenGL / khong co display: chup ca cua so GAMA thay the
			final String win = windowCapture.get();
			if (win != null) {
				if (b.length() > 0) { b.append('\n'); }
				b.append("fallback - whole GAMA window capture: ").append(win);
			}
		}
		return b.length() == 0 ? "(nothing to snapshot)" : b.toString();
	}
}
