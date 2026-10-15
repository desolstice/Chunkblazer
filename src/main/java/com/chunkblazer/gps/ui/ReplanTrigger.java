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

import java.util.function.LongSupplier;

/**
 * Debounced, single-flight trigger for re-planning. Bursts of {@link #request()} calls collapse
 * into at most one run per window, and two runs never overlap: the action must call
 * {@link #finished()} when planning completes.
 */
public class ReplanTrigger
{
	public static final long DEFAULT_WINDOW_MILLIS = 1500;

	private final LongSupplier clockMillis;
	private final long windowMillis;
	private final Runnable action;

	private boolean dirty;
	private boolean inFlight;
	private boolean everRan;
	private long lastRunStart;

	public ReplanTrigger(LongSupplier clockMillis, long windowMillis, Runnable action)
	{
		this.clockMillis = clockMillis;
		this.windowMillis = windowMillis;
		this.action = action;
	}

	/** Marks a re-plan as wanted; runs immediately if allowed, otherwise stays pending. */
	public void request()
	{
		boolean run;
		synchronized (this)
		{
			dirty = true;
			run = claim();
		}
		if (run)
		{
			action.run();
		}
	}

	/** Periodic poll: runs a pending request once the window has elapsed. */
	public void tick()
	{
		boolean run;
		synchronized (this)
		{
			run = claim();
		}
		if (run)
		{
			action.run();
		}
	}

	/** Must be called by the action when a run completes. */
	public synchronized void finished()
	{
		inFlight = false;
	}

	/** Caller holds the lock. */
	private boolean claim()
	{
		long now = clockMillis.getAsLong();
		if (!dirty || inFlight || (everRan && now - lastRunStart < windowMillis))
		{
			return false;
		}
		dirty = false;
		inFlight = true;
		everRan = true;
		lastRunStart = now;
		return true;
	}
}
