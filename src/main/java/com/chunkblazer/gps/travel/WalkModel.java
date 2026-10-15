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
package com.chunkblazer.gps.travel;

import com.chunkblazer.gps.data.Tile;

/** Immutable, thread-safe walking cost model; planes are ignored apart from a flat per-plane-difference cost. */
public final class WalkModel
{
	private final ChunkGraph.View view;

	public WalkModel(ChunkGraph.View view)
	{
		this.view = view;
	}

	public int ticks(Tile a, Tile b)
	{
		if (a == null || b == null || !a.isKnown() || !b.isKnown())
		{
			return TravelConfig.INF;
		}
		int walk = view.walkTicks(a, b);
		if (walk >= TravelConfig.INF)
		{
			return TravelConfig.INF;
		}
		long total = (long) walk + (long) Math.abs(a.getPlane() - b.getPlane()) * TravelConfig.PLANE_CHANGE_TICKS;
		return (int) Math.min(total, TravelConfig.INF);
	}
}
