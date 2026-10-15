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

import com.chunkblazer.gps.data.TransportEdge;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** A transport edge the player can use now (AVAILABLE) or could use after buying items (ACQUIRABLE). Immutable. */
public final class UsableEdge
{
	public enum Availability
	{
		AVAILABLE,
		ACQUIRABLE
	}

	private final TransportEdge edge;
	private final Availability availability;
	private final List<Acquisition> acquisitions;
	private final int ticks;

	public UsableEdge(TransportEdge edge, Availability availability, List<Acquisition> acquisitions, int ticks)
	{
		this.edge = edge;
		this.availability = availability;
		this.acquisitions = acquisitions == null
			? Collections.<Acquisition>emptyList()
			: Collections.unmodifiableList(new ArrayList<>(acquisitions));
		this.ticks = ticks;
	}

	public TransportEdge getEdge()
	{
		return edge;
	}

	public Availability getAvailability()
	{
		return availability;
	}

	/** One entry per missing item, in requirement order; empty when AVAILABLE. */
	public List<Acquisition> getAcquisitions()
	{
		return acquisitions;
	}

	/** The first acquisition, or null when there is none. */
	public Acquisition getAcquisition()
	{
		return acquisitions.isEmpty() ? null : acquisitions.get(0);
	}

	public int getTicks()
	{
		return ticks;
	}
}
