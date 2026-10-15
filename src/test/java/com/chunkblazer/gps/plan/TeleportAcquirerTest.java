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
package com.chunkblazer.gps.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.chunkblazer.gps.travel.TileMath;
import org.junit.jupiter.api.Test;

class TeleportAcquirerTest
{
	private static PlanWorld world(int startX, int shopX, int farX, boolean allow)
	{
		return PlanWorld.builder()
			.unlockedRange(0, 10100, 3200)
			.spellTeleport("tele", 554, farX - 10)
			.shop("Rune shop", 554, shopX)
			.task("far1", "COOKING", 1, 1, farX, 3200)
			.task("far2", "COOKING", 1, 1, farX + 10, 3200)
			.start(startX, 3200)
			.allowAcquisition(allow)
			.build();
	}

	private static int indexOfType(Plan p, NodeType t)
	{
		for (PlanStep s : p.getSteps())
		{
			if (s.getType() == t)
			{
				return s.getOrder();
			}
		}
		return -1;
	}

	private static int indexOfLabel(Plan p, String label)
	{
		for (PlanStep s : p.getSteps())
		{
			if (s.getLabel().contains(label))
			{
				return s.getOrder();
			}
		}
		return -1;
	}

	@Test
	void buysTeleportRunesWhenItSavesALotOfTravel()
	{
		PlanWorld w = world(0, 20, 10000, true);
		Plan with = new Planner(w.request()).plan();
		Plan without = new Planner(w.requestWithAcquisition(false)).plan();
		assertTrue(with.getTotalTravelTicks() < without.getTotalTravelTicks());
		assertTrue(with.getTotalTravelTicks() + TeleportAcquirer.MIN_NET_SAVING_TICKS <= without.getTotalTravelTicks());
		int ia = indexOfType(with, NodeType.ACQUIRE_TELEPORT);
		assertEquals(0, ia);
		assertTrue(ia < indexOfLabel(with, "far1"));
		assertEquals(3, with.getSteps().size());
		assertTrue(with.getSteps().get(ia).getLabel().startsWith("Buy "));
		assertTrue(with.getSteps().get(ia).getLabel().endsWith("at Rune shop"));
	}

	@Test
	void acquisitionPrecedesEveryNodeUsingTheTeleport()
	{
		Plan with = new Planner(world(0, 20, 10000, true).request()).plan();
		int ia = indexOfType(with, NodeType.ACQUIRE_TELEPORT);
		assertTrue(ia >= 0);
		for (PlanStep s : with.getSteps())
		{
			if (s.getViaTransportEdgeIds() != null && s.getViaTransportEdgeIds().contains("tele"))
			{
				assertTrue(ia < s.getOrder());
			}
		}
		assertTrue(with.getSteps().stream().anyMatch(s -> s.getViaTransportEdgeIds().contains("tele")));
	}

	@Test
	void shopOnTheFarSideIsNotWorthTheDetour()
	{
		// START 5000, tasks 10000, shop at 0: walking to the shop and teleporting costs as much as walking on.
		PlanWorld w = world(5000, 0, 10000, true);
		Plan p = new Planner(w.request()).plan();
		assertEquals(-1, indexOfType(p, NodeType.ACQUIRE_TELEPORT));
		assertEquals(new Planner(w.requestWithAcquisition(false)).plan().getTotalTravelTicks(), p.getTotalTravelTicks());
	}

	@Test
	void neverInsertedWhenAcquisitionDisallowed()
	{
		Plan p = new Planner(world(0, 20, 10000, false).request()).plan();
		assertEquals(-1, indexOfType(p, NodeType.ACQUIRE_TELEPORT));
		assertEquals(2, p.getSteps().size());
	}

	@Test
	void savingBelowThresholdRejects()
	{
		// tasks 100 tiles away: a teleport saves well under MIN_NET_SAVING_TICKS
		Plan p = new Planner(world(0, 20, 100, true).request()).plan();
		assertEquals(-1, indexOfType(p, NodeType.ACQUIRE_TELEPORT));
	}

	@Test
	void ownedItemNeedsNoAcquisition()
	{
		PlanWorld w = PlanWorld.builder().unlockedRange(0, 10100, 3200).spellTeleport("tele", 554, 9990)
			.shop("Rune shop", 554, 20).task("far1", "COOKING", 1, 1, 10000, 3200).start(0, 3200).allowAcquisition(true)
			.ownItem(554, 5).build();
		Plan p = new Planner(w.request()).plan();
		assertEquals(-1, indexOfType(p, NodeType.ACQUIRE_TELEPORT));
		assertTrue(p.getSteps().get(0).getViaTransportEdgeIds().contains("tele"));
	}

	@Test
	void boughtEdgeUsedBeforeItsAcquireNodeIsDetected()
	{
		PlanWorld w = world(0, 20, 10000, true);
		PlanRequest req = w.request();
		java.util.List<PlanNode> nodes = new java.util.ArrayList<>(
			new Selector(req, new NodeFactory(req.getData(), req.getQuests(), req.getDeriver(),
				new com.chunkblazer.gps.travel.LocationResolver(req.getData(), req.getQuests()), java.util.Collections.emptySet()))
				.select().getNodes());
		assertEquals(2, nodes.size());
		com.chunkblazer.gps.data.Tile shop = w.tile(20, 3200);
		nodes.add(PlanNode.builder().id("acquire-teleport:tele").type(NodeType.ACQUIRE_TELEPORT).label("Buy")
			.location(new com.chunkblazer.gps.travel.ResolvedLocation(shop, false, "NAME")).build());
		java.util.Map<String, com.chunkblazer.gps.data.Tile> tiles = new java.util.HashMap<>();
		for (PlanNode n : nodes)
		{
			tiles.put(n.getId(), n.getLocation().getTile());
		}
		java.util.Set<String> acquired = java.util.Collections.singleton("tele");
		com.chunkblazer.gps.travel.CostMatrix m = Planner.buildMatrix(req, nodes, tiles, acquired, null);
		// order uses 1-based indices: 1,2 = far tasks, 3 = the shop visit
		assertTrue(TeleportAcquirer.usesEdgeBeforeAcquire(new int[] {1, 2, 3}, m, nodes, acquired));
		assertFalse(TeleportAcquirer.usesEdgeBeforeAcquire(new int[] {3, 1, 2}, m, nodes, acquired));
	}

	@Test
	void viaListsNeverContainAnEdgeBeforeItsPurchase()
	{
		Plan with = new Planner(world(0, 20, 10000, true).request()).plan();
		boolean bought = false;
		for (PlanStep s : with.getSteps())
		{
			if (s.getViaTransportEdgeIds().contains("tele"))
			{
				assertTrue(bought);
			}
			if (s.getType() == NodeType.ACQUIRE_TELEPORT)
			{
				bought = true;
			}
		}
	}

	@Test
	void shopReachableOnlyThroughTheUnboughtTeleportIsNotAcquired()
	{
		// The shop sits next to the teleport's destination, so the only cheap way to the shop is the teleport itself.
		// Buying there needs the teleport first: the plan must not claim that impossible saving.
		PlanWorld w = PlanWorld.builder()
			.unlockedRange(0, 10100, 3200)
			.spellTeleport("tele", 554, 9990)
			.shop("Rune shop", 554, 9980)
			.task("far1", "COOKING", 1, 1, 10000, 3200)
			.task("far2", "COOKING", 1, 1, 10010, 3200)
			.start(0, 3200)
			.allowAcquisition(true)
			.build();
		Plan with = new Planner(w.request()).plan();
		Plan without = new Planner(w.requestWithAcquisition(false)).plan();
		assertEquals(-1, indexOfType(with, NodeType.ACQUIRE_TELEPORT));
		assertEquals(without.getTotalTravelTicks(), with.getTotalTravelTicks());
	}

	@Test
	void constantsMatchTheDesign()
	{
		assertEquals(100, TeleportAcquirer.MIN_NET_SAVING_TICKS);
		assertEquals(5, TeleportAcquirer.MAX_ACQUISITIONS);
		assertEquals(25, TeleportAcquirer.MAX_QTY);
		assertFalse(TileMath.regionId(0, 3200) == TileMath.regionId(10000, 3200));
	}
}
