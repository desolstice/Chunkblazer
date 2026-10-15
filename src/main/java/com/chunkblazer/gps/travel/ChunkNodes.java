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

import com.chunkblazer.NuzlockeChunk;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class ChunkNodes
{
	private ChunkNodes()
	{
	}

	public static List<ChunkNode> from(List<NuzlockeChunk> chunks)
	{
		List<ChunkNode> out = new ArrayList<>();
		if (chunks == null)
		{
			return out;
		}
		for (NuzlockeChunk c : chunks)
		{
			if (c == null)
			{
				continue;
			}
			List<Integer> dests = new ArrayList<>();
			if (c.getTravelConnections() != null)
			{
				for (NuzlockeChunk.TravelConnection tc : c.getTravelConnections())
				{
					if (tc != null && tc.getDestinationId() != null)
					{
						dests.add(tc.getDestinationId());
					}
				}
			}
			out.add(new ChunkNode(copy(c.getRegionIds()), copy(c.getNeighborIds()), c.isCharter(), dests));
		}
		return out;
	}

	private static List<Integer> copy(List<Integer> in)
	{
		if (in == null)
		{
			return Collections.<Integer>emptyList();
		}
		List<Integer> out = new ArrayList<>(in.size());
		for (Integer i : in)
		{
			if (i != null)
			{
				out.add(i);
			}
		}
		return out;
	}
}
