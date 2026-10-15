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

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ReplanTriggerTest
{
	private final AtomicLong clock = new AtomicLong(0);
	private final AtomicInteger runs = new AtomicInteger();

	private ReplanTrigger trigger()
	{
		return new ReplanTrigger(clock::get, 1500, runs::incrementAndGet);
	}

	@Test
	void firstRequestRunsImmediately()
	{
		ReplanTrigger t = trigger();
		t.request();
		assertEquals(1, runs.get());
	}

	@Test
	void burstWithinWindowRunsOnceThenOnceMore()
	{
		ReplanTrigger t = trigger();
		t.request();
		t.finished();
		for (int i = 0; i < 30; i++)
		{
			clock.addAndGet(10);
			t.request();
		}
		assertEquals(1, runs.get());
		clock.set(1600);
		t.tick();
		assertEquals(2, runs.get());
		t.finished();
		clock.set(5000);
		t.tick();
		assertEquals(2, runs.get());
	}

	@Test
	void neverOverlapsRuns()
	{
		ReplanTrigger t = trigger();
		t.request();
		clock.set(10_000);
		t.request();
		t.tick();
		assertEquals(1, runs.get());
		t.finished();
		t.tick();
		assertEquals(2, runs.get());
	}

	@Test
	void throwingActionClearsInFlightMarksDirtyAndRethrows()
	{
		AtomicInteger calls = new AtomicInteger();
		ReplanTrigger t = new ReplanTrigger(clock::get, 1500, () ->
		{
			if (calls.incrementAndGet() == 1)
			{
				throw new IllegalStateException("boom");
			}
		});
		assertThrows(IllegalStateException.class, t::request);
		clock.set(2000);
		t.tick();
		assertEquals(2, calls.get());
	}

	@Test
	void requestAfterWindowRunsImmediately()
	{
		ReplanTrigger t = trigger();
		t.request();
		t.finished();
		clock.set(2000);
		t.request();
		assertEquals(2, runs.get());
	}
}
