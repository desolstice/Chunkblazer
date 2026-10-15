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
import com.chunkblazer.gps.adapter.SimulatedState;
import com.chunkblazer.gps.model.PlayerState;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.function.IntPredicate;

/**
 * The shared simulated account. The Simulate tab edits it; the Tasks tab and the planner read {@link #view()}, which
 * is null while simulation is off (real account). Edits run on the EDT; {@link #view()} is safe from any thread.
 */
public final class SimulationSession
{
	private SimulatedState state;
	private volatile PlayerState view;
	private volatile Runnable listener = () ->
	{
	};

	/** Called (on the thread that made the change) after every start, stop or edit. */
	public void setListener(Runnable listener)
	{
		this.listener = listener == null ? () ->
		{
		} : listener;
	}

	public boolean isActive()
	{
		return view != null;
	}

	/** The simulated player state, or null when not simulating. */
	public PlayerState view()
	{
		return view;
	}

	/** A brand-new account that already holds the given starting (free) regions. */
	public void startFresh(Collection<Integer> startingRegions)
	{
		SimulatedState s = SimulatedState.fresh();
		for (Integer r : startingRegions)
		{
			s.setChunk(r, true);
		}
		begin(s);
	}

	public void startFromReal(PlayerState real)
	{
		begin(SimulatedState.fromReal(real));
	}

	public void stop()
	{
		state = null;
		view = null;
		listener.run();
	}

	public void setLevel(String skill, int level)
	{
		edit(s -> s.setLevel(skill, level));
	}

	public void toggleQuest(String id)
	{
		edit(s -> s.toggleQuest(id));
	}

	public void setChunk(int regionId, boolean unlocked)
	{
		edit(s -> s.setChunk(regionId, unlocked));
	}

	public void setItem(int id, int qty)
	{
		edit(s -> s.setItem(id, qty));
	}

	public void setMaxHit(int maxHit)
	{
		edit(s -> s.setMaxHit(maxHit));
	}

	public void setTaskPoints(int points)
	{
		edit(s -> s.setTaskPoints(points));
	}

	/** Every region of every chunk that the predicate calls free (always accessible from a brand-new account). */
	public static Set<Integer> startingRegions(Collection<NuzlockeChunk> chunks, IntPredicate free)
	{
		Set<Integer> out = new HashSet<>();
		if (chunks == null)
		{
			return out;
		}
		for (NuzlockeChunk c : chunks)
		{
			if (c == null || c.getRegionIds() == null)
			{
				continue;
			}
			for (Integer r : c.getRegionIds())
			{
				if (r != null && free.test(r))
				{
					out.add(r);
				}
			}
		}
		return out;
	}

	private void begin(SimulatedState s)
	{
		state = s;
		view = s.build();
		listener.run();
	}

	private void edit(java.util.function.Consumer<SimulatedState> change)
	{
		SimulatedState s = state;
		if (s == null)
		{
			return;
		}
		change.accept(s);
		view = s.build();
		listener.run();
	}
}
