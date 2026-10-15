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

import com.chunkblazer.NuzlockeTask;
import com.chunkblazer.gps.adapter.PlayerStateProvider;
import com.chunkblazer.gps.data.GpsData;
import com.chunkblazer.gps.data.GpsDataLocator;
import com.chunkblazer.gps.data.GpsDataStore;
import com.chunkblazer.gps.eligibility.EligibilityEngine;
import com.chunkblazer.gps.eligibility.Overrides;
import com.chunkblazer.gps.eligibility.RequirementDeriver;
import com.chunkblazer.gps.model.PlayerState;
import com.chunkblazer.gps.model.QuestCatalog;
import com.chunkblazer.gps.model.QuestHelperEntry;
import java.awt.image.BufferedImage;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Supplier;
import javax.swing.SwingUtilities;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Quest;
import net.runelite.api.QuestState;
import net.runelite.client.callback.ClientThread;
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

	private volatile boolean stopped;
	private NavigationButton button;
	private volatile GpsPanel panel;

	public GpsModule(Client client, ClientThread clientThread, ScheduledExecutorService executor,
		ClientToolbar clientToolbar, Supplier<String> dataDirConfig, Supplier<List<NuzlockeTask>> taskSupplier,
		Supplier<Set<String>> openRegionIds, BufferedImage icon)
	{
		this.client = client;
		this.clientThread = clientThread;
		this.executor = executor;
		this.clientToolbar = clientToolbar;
		this.dataDirConfig = dataDirConfig;
		this.taskSupplier = taskSupplier;
		this.openRegionIds = openRegionIds;
		this.icon = icon;
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
					Collections::emptyMap);
				SwingUtilities.invokeLater(() -> createPanel(data, engine, provider));
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
	private void createPanel(GpsData data, EligibilityEngine engine, PlayerStateProvider provider)
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
		panel = new GpsPanel(model, data, requester);
		button = NavigationButton.builder().tooltip("GPS").icon(icon).priority(9).panel(panel).build();
		clientToolbar.addNavigation(button);
	}

	public void stop()
	{
		stopped = true;
		SwingUtilities.invokeLater(() ->
		{
			if (button != null)
			{
				clientToolbar.removeNavigation(button);
				button = null;
				panel = null;
			}
		});
	}
}
