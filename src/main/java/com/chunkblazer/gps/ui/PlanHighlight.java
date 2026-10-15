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

import com.chunkblazer.gps.data.Tile;
import com.chunkblazer.gps.plan.PlanStep;
import com.chunkblazer.gps.travel.TileMath;
import java.util.OptionalInt;

/** Thread-safe holder for the region (and label) of the plan step to highlight on the world map. */
public class PlanHighlight
{
	private volatile State state;

	private static final class State
	{
		private final int regionId;
		private final String label;

		private State(int regionId, String label)
		{
			this.regionId = regionId;
			this.label = label;
		}
	}

	public void set(int regionId, String label)
	{
		state = new State(regionId, label);
	}

	public void clear()
	{
		state = null;
	}

	public OptionalInt regionId()
	{
		State s = state;
		return s == null ? OptionalInt.empty() : OptionalInt.of(s.regionId);
	}

	/** The label of the current highlight, or null when none is set. */
	public String label()
	{
		State s = state;
		return s == null ? null : s.label;
	}

	/** The region containing the step's tile; empty when the step or its tile is unknown. */
	public static OptionalInt regionOf(PlanStep step)
	{
		Tile t = step == null ? null : step.getLocation();
		if (t == null || !t.isKnown())
		{
			return OptionalInt.empty();
		}
		return OptionalInt.of(TileMath.regionId(t.getX(), t.getY()));
	}
}
