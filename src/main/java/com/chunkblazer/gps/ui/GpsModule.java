/*
 * Copyright (c) 2026, Vani-Lab
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 */
package com.chunkblazer.gps.ui;

import com.chunkblazer.NuzlockeChunk;
import com.chunkblazer.NuzlockeTask;
import com.chunkblazer.gps.adapter.BankCache;
import com.chunkblazer.gps.adapter.PlayerStateProvider;
import com.chunkblazer.gps.data.GpsData;
import com.chunkblazer.gps.data.GpsDataLocator;
import com.chunkblazer.gps.data.GpsDataStore;
import com.chunkblazer.gps.data.Tile;
import com.chunkblazer.gps.eligibility.EligibilityEngine;
import com.chunkblazer.gps.eligibility.Overrides;
import com.chunkblazer.gps.eligibility.RequirementDeriver;
import com.chunkblazer.gps.model.PlayerState;
import com.chunkblazer.gps.model.QuestCatalog;
import com.chunkblazer.gps.model.QuestHelperEntry;
import com.chunkblazer.gps.model.QuestModel;
import com.chunkblazer.gps.plan.GoalMode;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.IntPredicate;
import java.util.function.IntUnaryOperator;
import java.util.function.Supplier;
import javax.swing.SwingUtilities;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Quest;
import net.runelite.api.Player;
import net.runelite.api.QuestState;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;

/** Owns the GPS panel and nav button so the plugin only needs to start and stop it. */
@Slf4j
public final class GpsModule
{
	private final Client client;
	private final ClientThread clientThread;
	private final ScheduledExecutorService executor;
	private final ClientToolbar clientToolbar;
	private final Supplier<String> dataDirConfig;
	private final Supplier<List<NuzlockeTask>> taskSupplier;
	private final Supplier<Set<String>> openRegionIds;
	private final BufferedImage icon;
	private final BankCache bankCache;
	private final ItemManager itemManager;

	private final PlanSources sources;
	private final PlanHighlight highlight;
	private final SimulationSession simulation = new SimulationSession();
	private final ReplanTrigger trigger = new ReplanTrigger(System::currentTimeMillis,
		ReplanTrigger.DEFAULT_WINDOW_MILLIS, this::runPlan);

	private volatile boolean stopped;
	private NavigationButton button;
	private volatile GpsPanel panel;
	private volatile GpsPlanPanel planPanel;
	private volatile PlanRunner runner;
	private volatile PlayerStateProvider provider;
	private volatile ScheduledFuture<?> tick;

	/** Plugin-side catalog reads needed to build the planner's task list (read on the client thread). */
	public static final class PlanSources
	{
		private final Supplier<List<NuzlockeChunk>> chunks;
		private final Supplier<List<NuzlockeTask>> globalTasks;
		private final IntUnaryOperator unlockCost;
		private final Supplier<Set<String>> completedTaskIds;
		private final IntPredicate freeRegion;

		public PlanSources(Supplier<List<NuzlockeChunk>> chunks, Supplier<List<NuzlockeTask>> globalTasks,
			IntUnaryOperator unlockCost, Supplier<Set<String>> completedTaskIds, IntPredicate freeRegion)
		{
			this.chunks = chunks;
			this.globalTasks = globalTasks;
			this.unlockCost = unlockCost;
			this.completedTaskIds = completedTaskIds;
			this.freeRegion = freeRegion;
		}
	}

	public GpsModule(Client client, ClientThread clientThread, ScheduledExecutorService executor,
		ClientToolbar clientToolbar, Supplier<String> dataDirConfig, Supplier<List<NuzlockeTask>> taskSupplier,
		Supplier<Set<String>> openRegionIds, BufferedImage icon, BankCache bankCache, ItemManager itemManager,
		PlanSources sources, PlanHighlight highlight)
	{
		this.sources = sources;
		this.highlight = highlight;
		this.client = client;
		this.clientThread = clientThread;
		this.executor = executor;
		this.clientToolbar = clientToolbar;
		this.dataDirConfig = dataDirConfig;
		this.taskSupplier = taskSupplier;
		this.openRegionIds = openRegionIds;
		this.icon = icon;
		this.bankCache = bankCache;
		this.itemManager = itemManager;
	}

	/** Loads the data off the client thread, then registers the panel on the EDT. */
	public void start()
	{
		stopped = false;
		executor.submit(() ->
		{
			try
			{
				java.nio.file.Path dir = GpsDataLocator.resolveDir(dataDirConfig.get());
				GpsData data = GpsDataStore.load(dir);
				QuestCatalog quests = QuestCatalog.build(data, QuestHelperEntry.loadFromResource());
				EligibilityEngine engine = new EligibilityEngine(
					new RequirementDeriver(quests, Overrides.load(dir), data), quests, data);
				PlayerStateProvider provider = new PlayerStateProvider(client, openRegionIds, this::questFinished,
					bankCache::items, itemManager);
				this.provider = provider;
				runner = new PlanRunner(data, quests, new RequirementDeriver(quests, Overrides.load(dir), data));
				ScheduledFuture<?> scheduled = executor.scheduleWithFixedDelay(this::tickSafely, 1, 1, TimeUnit.SECONDS);
				tick = scheduled;
				if (stopped)
				{
					// stop() ran while loading and found no tick to cancel.
					scheduled.cancel(false);
					return;
				}
				SwingUtilities.invokeLater(() -> createPanel(data, engine, provider, quests));
			}
			catch (Throwable ex)
			{
				log.warn("Progression GPS failed to start", ex);
			}
		});
	}

	private boolean questFinished(String name)
	{
		try
		{
			return Quest.valueOf(name).getState(client) == QuestState.FINISHED;
		}
		catch (IllegalArgumentException ex)
		{
			return false;
		}
	}

	// EDT
	private void createPanel(GpsData data, EligibilityEngine engine, PlayerStateProvider provider, QuestCatalog quests)
	{
		if (stopped)
		{
			return;
		}

		GpsPanelModel model = new GpsPanelModel(engine, Collections.emptyList());
		Consumer<BiConsumer<PlayerState, GpsPanelModel>> requester = callback -> clientThread.invokeLater(() ->
		{
			try
			{
				// Client thread: snapshot runs client scripts; the task list is copied here (not reloaded).
				boolean loggedIn = client.getGameState() == GameState.LOGGED_IN;
				PlayerState state = loggedIn ? provider.snapshot() : PlayerState.freshAccount();
				callback.accept(state, new GpsPanelModel(engine, taskSupplier.get()));
				GpsPanel p = panel;
				if (p != null)
				{
					SwingUtilities.invokeLater(() -> p.setLoggedIn(loggedIn));
				}
			}
			catch (RuntimeException ex)
			{
				log.warn("GPS snapshot failed", ex);
			}
		});
		planPanel = new GpsPlanPanel(trigger::requestNow, trigger::request, highlight, data,
			id -> quests.get(id).map(QuestModel::getName).orElse(null));
		SimulatePanel simulatePanel = new SimulatePanel(simulation, data, quests, sources.chunks,
			sources.freeRegion, trigger::requestNow);
		GpsPanel gpsPanel = new GpsPanel(model, data, requester, planPanel, simulatePanel, simulation);
		panel = gpsPanel;
		simulation.setListener(() ->
		{
			gpsPanel.refresh();
			requestReplan();
		});
		button = NavigationButton.builder().tooltip("GPS").icon(icon).priority(9).panel(panel).build();
		clientToolbar.addNavigation(button);
	}

	/** Asks for a (debounced) re-plan when "Auto re-plan" is on. Any thread; the plugin's events call this. */
	public void requestReplan()
	{
		GpsPlanPanel p = planPanel;
		if (p != null && p.autoReplan())
		{
			trigger.request();
		}
	}

	private void tickSafely()
	{
		if (stopped)
		{
			return;
		}
		try
		{
			trigger.tick();
		}
		catch (RuntimeException ex)
		{
			log.warn("GPS re-plan tick failed", ex);
		}
	}

	/** The trigger action: snapshot on the client thread, plan on the executor, show on the EDT. */
	private void runPlan()
	{
		boolean handedOff = false;
		try
		{
			GpsPanel gp = panel;
			GpsPlanPanel pp = planPanel;
			PlanRunner r = runner;
			PlayerStateProvider prov = provider;
			if (gp == null || pp == null || r == null || prov == null)
			{
				return;
			}
			PlayerState sim = gp.simulatedState();
			GoalMode goal = pp.goal();
			clientThread.invoke(() ->
			{
				boolean ok = false;
				try
				{
					boolean loggedIn = client.getGameState() == GameState.LOGGED_IN && client.getLocalPlayer() != null;
					PlanRunner.Inputs in = sim == null && !loggedIn ? null : gather(sim, loggedIn, prov);
					executor.submit(() -> planOffThread(r, pp, sim != null, loggedIn, in, goal));
					ok = true;
				}
				catch (RuntimeException ex)
				{
					log.warn("GPS plan snapshot failed", ex);
				}
				finally
				{
					if (!ok)
					{
						trigger.finished();
					}
				}
			});
			handedOff = true;
		}
		finally
		{
			if (!handedOff)
			{
				trigger.finished();
			}
		}
	}

	// Client thread.
	private PlanRunner.Inputs gather(PlayerState sim, boolean loggedIn, PlayerStateProvider prov)
	{
		PlayerState state = sim != null ? sim : prov.snapshot();
		Tile position = null;
		Player me = client.getLocalPlayer();
		if (sim == null && loggedIn && me != null && me.getWorldLocation() != null)
		{
			WorldPoint wp = me.getWorldLocation();
			position = new Tile(wp.getX(), wp.getY(), wp.getPlane(), false, "PLAYER");
		}
		// Raw copies only: task refs are built off the client thread from these.
		List<NuzlockeChunk> chunks = sources.chunks.get();
		Set<String> completed = sim != null ? Collections.<String>emptySet() : new HashSet<>(sources.completedTaskIds.get());
		return new PlanRunner.Inputs(state, position, chunks, new ArrayList<>(sources.globalTasks.get()),
			PlanRunner.unlockCosts(chunks, sources.unlockCost), completed);
	}

	private void planOffThread(PlanRunner r, GpsPlanPanel pp, boolean simulate, boolean loggedIn,
		PlanRunner.Inputs in, GoalMode goal)
	{
		try
		{
			PlanRunner.Result result = r.run(simulate, loggedIn, in, goal);
			SwingUtilities.invokeLater(() -> showIfRunning(pp, result));
		}
		catch (RuntimeException | StackOverflowError ex)
		{
			log.warn("GPS planning failed", ex);
			SwingUtilities.invokeLater(() -> showIfRunning(pp, new PlanRunner.Result(null, "Planning failed: " + ex)));
		}
		finally
		{
			trigger.finished();
		}
	}

	// EDT: a result that arrives after stop() must not bring the highlight back.
	private void showIfRunning(GpsPlanPanel pp, PlanRunner.Result result)
	{
		if (!stopped)
		{
			pp.show(result);
		}
	}

	public void stop()
	{
		stopped = true;
		simulation.setListener(null);
		ScheduledFuture<?> t = tick;
		if (t != null)
		{
			t.cancel(false);
		}
		SwingUtilities.invokeLater(() ->
		{
			GpsPlanPanel pp = planPanel;
			if (pp != null)
			{
				pp.tabHidden();
			}
			highlight.clear();
			if (button != null)
			{
				clientToolbar.removeNavigation(button);
				button = null;
				panel = null;
			}
		});
	}
}
