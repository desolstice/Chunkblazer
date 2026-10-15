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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import com.chunkblazer.NuzlockeChunk;
import com.chunkblazer.NuzlockeTask;
import com.chunkblazer.gps.model.PlayerState;
import com.chunkblazer.gps.plan.GoalMode;
import com.chunkblazer.gps.plan.Plan;
import com.chunkblazer.gps.plan.PlanRequest;
import com.chunkblazer.gps.plan.TaskRef;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class PlanRunnerTest
{
	private final AtomicInteger calls = new AtomicInteger();
	private final AtomicReference<PlanRequest> last = new AtomicReference<>();
	private final Plan empty = new Plan(Collections.emptyList(), 0, Collections.emptyList(), "v");

	private PlanRunner runner()
	{
		return new PlanRunner(null, null, null, req ->
		{
			calls.incrementAndGet();
			last.set(req);
			return empty;
		}, chunks -> new PlanRunner.Travel(null, null));
	}

	private static PlanRunner.Inputs inputs(PlayerState s)
	{
		return new PlanRunner.Inputs(s, null, Collections.emptyList(), Collections.emptySet(), Collections.emptyList());
	}

	@Test
	void loggedOutRealModeDoesNotPlan()
	{
		PlanRunner.Result r = runner().run(false, false, inputs(PlayerState.freshAccount()), GoalMode.maxPoints());
		assertEquals(PlanRunner.LOGGED_OUT_MESSAGE, r.getMessage());
		assertNull(r.getPlan());
		assertEquals(0, calls.get());
	}

	@Test
	void loggedInWithNoSnapshotDoesNotPlan()
	{
		PlanRunner.Result r = runner().run(false, true, null, GoalMode.maxPoints());
		assertEquals(PlanRunner.LOGGED_OUT_MESSAGE, r.getMessage());
		assertEquals(0, calls.get());
	}

	@Test
	void simulateModePlansEvenWhenLoggedOutAndUsesUnknownStart()
	{
		PlayerState s = PlayerState.freshAccount();
		PlanRunner.Result r = runner().run(true, false, inputs(s), GoalMode.maxGear());
		assertSame(empty, r.getPlan());
		assertNull(r.getMessage());
		assertSame(s, last.get().getState());
		assertEquals(GoalMode.Kind.MAX_GEAR, last.get().getGoal().getKind());
		assertFalse(last.get().getStart().isKnown());
	}

	@Test
	void goalMapping()
	{
		assertEquals(GoalMode.Kind.MAX_POINTS, PlanRunner.goalFor(PlanRunner.GoalChoice.MAX_POINTS, null, 0).getKind());
		assertEquals(GoalMode.Kind.UNLOCK_NEXT_CHUNK,
			PlanRunner.goalFor(PlanRunner.GoalChoice.UNLOCK_NEXT_CHUNK, null, 0).getKind());
		GoalMode train = PlanRunner.goalFor(PlanRunner.GoalChoice.TRAIN_SKILL, "cooking", 150);
		assertEquals(GoalMode.Kind.TRAIN_SKILL, train.getKind());
		assertEquals("COOKING", train.getSkill());
		assertEquals(99, train.getLevel());
		assertEquals(GoalMode.Kind.MAX_POINTS, PlanRunner.goalFor(PlanRunner.GoalChoice.TRAIN_SKILL, " ", 5).getKind());
		assertEquals(GoalMode.Kind.MAX_GEAR, PlanRunner.goalFor(PlanRunner.GoalChoice.MAX_GEAR, null, 0).getKind());
	}

	private static NuzlockeTask task(String id)
	{
		NuzlockeTask t = new NuzlockeTask();
		t.setTaskId(id);
		return t;
	}

	private static NuzlockeChunk chunk(int region, NuzlockeTask... tasks)
	{
		NuzlockeChunk c = new NuzlockeChunk();
		c.setRegionIds(Collections.singletonList(region));
		c.setTasks(Arrays.asList(tasks));
		return c;
	}

	@Test
	void taskRefsPreferUnlockedChunkAndAddGlobals()
	{
		PlayerState s = PlayerState.freshAccount().withUnlockedChunk(2);
		List<TaskRef> refs = PlanRunner.taskRefs(
			Arrays.asList(chunk(1, task("a")), chunk(2, task("a"), task("b"))),
			Arrays.asList(task("g"), task("a")), s, region -> region * 10);
		assertEquals(3, refs.size());
		TaskRef a = refs.stream().filter(r -> "a".equals(r.getTask().getTaskId())).findFirst().get();
		assertEquals(2, a.getHomeRegionId());
		assertEquals(20, a.getUnlockCost());
		TaskRef g = refs.stream().filter(r -> "g".equals(r.getTask().getTaskId())).findFirst().get();
		assertEquals(2, g.getHomeRegionId());
		assertEquals(0, g.getUnlockCost());
	}

	@Test
	void simulatedChunksDriveTaskRefsAndStartingGlobalHome()
	{
		SimulationSession sim = new SimulationSession();
		sim.startFresh(Collections.singleton(1));
		sim.setChunk(2, true);
		sim.setChunk(1, false);
		PlayerState s = sim.view();
		List<TaskRef> refs = PlanRunner.taskRefs(
			Arrays.asList(chunk(1, task("a")), chunk(2, task("a"))), Arrays.asList(task("g")), s, region -> 5);
		TaskRef a = refs.stream().filter(r -> "a".equals(r.getTask().getTaskId())).findFirst().get();
		assertEquals(2, a.getHomeRegionId());
		TaskRef g = refs.stream().filter(r -> "g".equals(r.getTask().getTaskId())).findFirst().get();
		assertEquals(2, g.getHomeRegionId());
	}

	@Test
	void simulateRunPlansWithTheSimulatedUnlockedChunks()
	{
		SimulationSession sim = new SimulationSession();
		sim.startFresh(Collections.singleton(1));
		sim.setChunk(77, true);
		PlanRunner.Result r = runner().run(true, false, inputs(sim.view()), GoalMode.maxPoints());
		assertSame(empty, r.getPlan());
		assertEquals(new java.util.HashSet<>(Arrays.asList(1, 77)), last.get().getState().getUnlockedChunks());
	}
}
