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

import com.chunkblazer.gps.GpsTestFixtures;
import com.chunkblazer.gps.data.GpsData;
import com.chunkblazer.gps.model.PlayerState;
import com.chunkblazer.gps.model.QuestCatalog;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

class TransportModelTest
{
	private static final String TELE = "{\"schema\":1,\"edges\":[{\"id\":\"varrock_tele\",\"kind\":\"SPELL\",\"name\":\"Varrock Teleport\",\"from\":null,"
		+ "\"to\":{\"x\":3213,\"y\":3424,\"plane\":0},\"ticks\":8,\"requires\":{\"skills\":{\"MAGIC\":25},\"quests\":[],\"items\":[{\"ids\":[554],\"qty\":1}]},\"consumesItem\":true}]}";
	private static final String ITEMS = "{\"schema\":1,\"items\":[{\"id\":554,\"name\":\"Fire rune\",\"members\":false,\"slot\":null,\"requirements\":{\"skills\":{},\"quests\":[]},"
		+ "\"sources\":[{\"type\":\"SHOP\",\"shop\":\"Aubury's Rune Shop\",\"price\":17}],\"teleport\":null}]}";
	private static final String LOCS = "{\"schema\":1,\"entries\":[{\"name\":\"Aubury's Rune Shop\",\"kind\":\"SHOP\",\"ids\":[],\"x\":3253,\"y\":3401,\"plane\":0}]}";
	private static final int VARROCK = TileMath.regionId(3213, 3424);

	private static TransportModel build(String transport, String items, String locs)
	{
		GpsData d = GpsTestFixtures.dataWith(transport, items, locs);
		return new TransportModel(d, Collections.emptyList(), QuestCatalog.build(d, Collections.emptyMap()));
	}

	private TransportModel model()
	{
		return build(TELE, ITEMS, LOCS);
	}

	private PlayerState state(int magic, boolean haveRune)
	{
		PlayerState.Builder b = PlayerState.builder().level("MAGIC", magic).chunk(VARROCK);
		if (haveRune)
		{
			b.item(554, 5);
		}
		return b.build();
	}

	private static String edge(String id, String to, String requires)
	{
		return "{\"id\":\"" + id + "\",\"kind\":\"SPELL\",\"name\":\"" + id + "\",\"from\":null,\"to\":" + to
			+ ",\"ticks\":5,\"requires\":" + requires + ",\"consumesItem\":false}";
	}

	private static String edges(String... es)
	{
		return "{\"schema\":1,\"edges\":[" + String.join(",", es) + "]}";
	}

	private static final String TO_VARROCK = "{\"x\":3213,\"y\":3424,\"plane\":0}";

	@Test
	void availableWhenOwnedAndPenaltyApplied()
	{
		List<UsableEdge> e = model().usable(state(30, true), Collections.emptySet(), true);
		assertEquals(1, e.size());
		assertEquals(UsableEdge.Availability.AVAILABLE, e.get(0).getAvailability());
		assertEquals(8 + TravelConfig.CONSUMABLE_PENALTY_TICKS, e.get(0).getTicks());
		assertNull(e.get(0).getAcquisition());
	}

	@Test
	void notOwnedIsAcquirableWithShop()
	{
		List<UsableEdge> e = model().usable(state(30, false), Collections.emptySet(), true);
		assertEquals(UsableEdge.Availability.ACQUIRABLE, e.get(0).getAvailability());
		assertEquals("Aubury's Rune Shop", e.get(0).getAcquisition().getShop());
		assertEquals(3253, (int) e.get(0).getAcquisition().getShopTile().getX());
		assertEquals(17, e.get(0).getAcquisition().getPrice());
		assertEquals(554, e.get(0).getAcquisition().getItemId());
	}

	@Test
	void acquirableExcludedWhenNotRequested()
	{
		assertTrue(model().usable(state(30, false), Collections.emptySet(), false).isEmpty());
	}

	@Test
	void acquiredEdgeBecomesAvailable()
	{
		List<UsableEdge> e = model().usable(state(30, false), Collections.singleton("varrock_tele"), false);
		assertEquals(UsableEdge.Availability.AVAILABLE, e.get(0).getAvailability());
	}

	@Test
	void skillTooLowOrChunkLockedExcluded()
	{
		assertTrue(model().usable(state(10, true), Collections.emptySet(), true).isEmpty());
		PlayerState noChunk = PlayerState.builder().level("MAGIC", 30).item(554, 5).build();
		assertTrue(model().usable(noChunk, Collections.emptySet(), true).isEmpty());
	}

	@Test
	void unlockTokenGrantsEdgeWithoutItems()
	{
		PlayerState s = PlayerState.builder().level("MAGIC", 30).chunk(VARROCK).build().withUnlock("TELEPORT:Varrock Teleport");
		List<UsableEdge> e = model().usable(s, Collections.emptySet(), false);
		assertEquals(UsableEdge.Availability.AVAILABLE, e.get(0).getAvailability());
		assertEquals(8, e.get(0).getTicks());
	}

	@Test
	void onlyUnpricedShopIsFlaggedUnpriced()
	{
		String items = "{\"schema\":1,\"items\":[{\"id\":554,\"name\":\"Fire rune\",\"sources\":[{\"type\":\"SHOP\",\"shop\":\"Unpriced\"}]}]}";
		Acquisition a = build(TELE, items, null).usable(state(30, false), Collections.emptySet(), true).get(0).getAcquisition();
		assertEquals(false, a.isPriced());
		assertEquals(1, a.getQty());
		Acquisition priced = model().usable(state(30, false), Collections.emptySet(), true).get(0).getAcquisition();
		assertTrue(priced.isPriced());
	}

	@Test
	void missingQuantityIsReported()
	{
		String t = edges(edge("e", TO_VARROCK, "{\"items\":[{\"ids\":[554],\"qty\":5}]}"));
		PlayerState has2 = PlayerState.builder().chunk(VARROCK).item(554, 2).build();
		Acquisition a = build(t, ITEMS, LOCS).usable(has2, Collections.emptySet(), true).get(0).getAcquisition();
		assertEquals(3, a.getQty());
		assertEquals(17, a.getPrice());
		Acquisition none = build(t, ITEMS, LOCS).usable(state(1, false), Collections.emptySet(), true).get(0).getAcquisition();
		assertEquals(5, none.getQty());
	}

	@Test
	void charterEdgeFromChunkNodes()
	{
		int a = TileMath.regionId(3000, 3000), b = TileMath.regionId(2000, 3000);
		ChunkNode port = new ChunkNode(Arrays.asList(a), Collections.emptyList(), true, Arrays.asList(b));
		TransportModel m = new TransportModel(GpsData.empty(null), Arrays.asList(port), QuestCatalog.build(GpsData.empty(null), Collections.emptyMap()));
		PlayerState s = PlayerState.builder().chunk(a).chunk(b).build();
		List<UsableEdge> e = m.usable(s, Collections.emptySet(), true);
		assertEquals(1, e.size());
		assertTrue(e.get(0).getEdge().getId().startsWith("charter:"));
		assertTrue(e.get(0).getTicks() >= TravelConfig.CHARTER_BASE_TICKS);
		assertTrue(m.usable(PlayerState.builder().chunk(a).build(), Collections.emptySet(), true).isEmpty());
		assertTrue(m.usable(PlayerState.builder().chunk(b).build(), Collections.emptySet(), true).isEmpty());
	}

	@Test
	void charterOrderIsDeterministicAndMultiRegionUsesLowestUnlocked()
	{
		int hi = TileMath.regionId(3000, 3000), lo = TileMath.regionId(2000, 3000);
		int d1 = TileMath.regionId(1000, 3000), d2 = TileMath.regionId(500, 3000);
		ChunkNode port = new ChunkNode(Arrays.asList(hi, lo), Collections.emptyList(), true, Arrays.asList(d1, d2, null));
		TransportModel m = new TransportModel(null, Arrays.asList(port, null), null);
		PlayerState s = PlayerState.builder().chunk(hi).chunk(lo).chunk(d1).chunk(d2).build();
		List<UsableEdge> e = m.usable(s, null, true);
		assertEquals(2, e.size());
		List<String> ids = new ArrayList<>();
		for (UsableEdge u : e)
		{
			ids.add(u.getEdge().getId());
			assertTrue(u.getEdge().getId().startsWith("charter:" + lo + "-"));
		}
		List<String> sorted = new ArrayList<>(ids);
		Collections.sort(sorted);
		assertEquals(sorted, ids);
		ChunkNode reversed = new ChunkNode(Arrays.asList(lo, hi), Collections.emptyList(), true, Arrays.asList(d2, d1));
		List<UsableEdge> e2 = new TransportModel(null, Arrays.asList(reversed), null).usable(s, null, true);
		List<String> ids2 = new ArrayList<>();
		for (UsableEdge u : e2)
		{
			ids2.add(u.getEdge().getId());
		}
		assertEquals(ids, ids2);
	}

	@Test
	void nullAndEmptyDataNeverThrow()
	{
		TransportModel m = new TransportModel(null, null, null);
		assertTrue(m.usable(PlayerState.freshAccount(), null, true).isEmpty());
		assertTrue(m.usable(null, null, true).isEmpty());
		TransportModel noFiles = build(null, null, null);
		assertTrue(noFiles.usable(state(99, true), Collections.emptySet(), true).isEmpty());
	}

	@Test
	void nullEdgesListOrEdgeWithoutDestinationIgnored()
	{
		assertTrue(build("{\"schema\":1}", null, null).usable(state(99, true), Collections.emptySet(), true).isEmpty());
		assertTrue(build("{\"schema\":1,\"edges\":null}", null, null).usable(state(99, true), Collections.emptySet(), true).isEmpty());
		String t = edges(edge("no_to", "null", "{}"), edge("blank_to", "{\"plane\":0}", "{}"), edge("ok", TO_VARROCK, "{}"));
		List<UsableEdge> e = build(t, null, null).usable(state(1, false), Collections.emptySet(), true);
		assertEquals(1, e.size());
		assertEquals("ok", e.get(0).getEdge().getId());
	}

	@Test
	void edgeWithoutRequiresIsAvailable()
	{
		String t = "{\"schema\":1,\"edges\":[{\"id\":\"free\",\"kind\":\"WALK\",\"name\":\"Free\",\"to\":" + TO_VARROCK + ",\"ticks\":3}]}";
		List<UsableEdge> e = build(t, null, null).usable(state(1, false), Collections.emptySet(), false);
		assertEquals(1, e.size());
		assertEquals(3, e.get(0).getTicks());
	}

	@Test
	void unknownItemIdExcludedEvenWhenAcquirableRequested()
	{
		String t = edges(edge("e", TO_VARROCK, "{\"items\":[{\"ids\":[999999],\"qty\":1}]}"));
		assertTrue(build(t, ITEMS, LOCS).usable(state(1, false), Collections.emptySet(), true).isEmpty());
		String noIds = edges(edge("e", TO_VARROCK, "{\"items\":[{\"name\":\"x\",\"qty\":1}]}"));
		assertTrue(build(noIds, ITEMS, LOCS).usable(state(1, false), Collections.emptySet(), true).isEmpty());
	}

	@Test
	void missingItemWithoutShopExcludesWholeEdge()
	{
		String items = "{\"schema\":1,\"items\":[{\"id\":1,\"name\":\"A\",\"sources\":[{\"type\":\"SHOP\",\"shop\":\"S1\",\"price\":5}]},"
			+ "{\"id\":2,\"name\":\"B\",\"sources\":[{\"type\":\"DROP\",\"npc\":\"Goblin\"}]}]}";
		String two = edges(edge("e", TO_VARROCK, "{\"items\":[{\"ids\":[1],\"qty\":1},{\"ids\":[2],\"qty\":1}]}"));
		assertTrue(build(two, items, null).usable(state(1, false), Collections.emptySet(), true).isEmpty());
		// owning the shopless item leaves only the buyable one missing
		PlayerState hasB = PlayerState.builder().chunk(VARROCK).item(2, 1).build();
		List<UsableEdge> e = build(two, items, null).usable(hasB, Collections.emptySet(), true);
		assertEquals(1, e.size());
		assertEquals(UsableEdge.Availability.ACQUIRABLE, e.get(0).getAvailability());
		assertEquals(1, e.get(0).getAcquisitions().size());
		assertNull(e.get(0).getAcquisition().getShopTile());
	}

	@Test
	void oneAcquisitionPerMissingItem()
	{
		String items = "{\"schema\":1,\"items\":[{\"id\":1,\"name\":\"A\",\"sources\":[{\"type\":\"SHOP\",\"shop\":\"S1\",\"price\":5}]},"
			+ "{\"id\":2,\"name\":\"B\",\"sources\":[{\"type\":\"SHOP\",\"shop\":\"S2\",\"price\":7}]}]}";
		String two = edges(edge("e", TO_VARROCK, "{\"items\":[{\"ids\":[1],\"qty\":1},{\"ids\":[2],\"qty\":1}]}"));
		UsableEdge u = build(two, items, null).usable(state(1, false), Collections.emptySet(), true).get(0);
		assertEquals(2, u.getAcquisitions().size());
		assertEquals("S1", u.getAcquisition().getShop());
		assertEquals("S2", u.getAcquisitions().get(1).getShop());
	}

	@Test
	void cheapestShopPicked()
	{
		String items = "{\"schema\":1,\"items\":[{\"id\":554,\"name\":\"Fire rune\",\"sources\":["
			+ "{\"type\":\"SHOP\",\"shop\":\"Dear\",\"price\":30},{\"type\":\"DROP\",\"npc\":\"x\"},"
			+ "{\"type\":\"SHOP\",\"shop\":\"Cheap\",\"price\":4},{\"type\":\"SHOP\",\"shop\":\"Unpriced\"}]}]}";
		UsableEdge u = build(TELE, items, null).usable(state(30, false), Collections.emptySet(), true).get(0);
		assertEquals("Cheap", u.getAcquisition().getShop());
		assertEquals(4, u.getAcquisition().getPrice());
		assertNull(u.getAcquisition().getShopTile());
	}

	@Test
	void anyOfItemUsesCheapestAcrossIds()
	{
		String items = "{\"schema\":1,\"items\":[{\"id\":1,\"name\":\"A\",\"sources\":[{\"type\":\"SHOP\",\"shop\":\"S1\",\"price\":50}]},"
			+ "{\"id\":2,\"name\":\"B\",\"sources\":[{\"type\":\"SHOP\",\"shop\":\"S2\",\"price\":9}]}]}";
		String t = edges(edge("e", TO_VARROCK, "{\"items\":[{\"ids\":[1,2],\"qty\":1}]}"));
		UsableEdge u = build(t, items, null).usable(state(1, false), Collections.emptySet(), true).get(0);
		assertEquals(2, u.getAcquisition().getItemId());
		PlayerState owns = PlayerState.builder().chunk(VARROCK).item(1, 1).build();
		assertEquals(UsableEdge.Availability.AVAILABLE, build(t, items, null).usable(owns, Collections.emptySet(), false).get(0).getAvailability());
	}

	@Test
	void questRequirementAndLockedFromTileExcluded()
	{
		String t = edges(edge("q", TO_VARROCK, "{\"quests\":[\"DRAGON_SLAYER_I\"]}"),
			"{\"id\":\"f\",\"kind\":\"X\",\"name\":\"f\",\"from\":{\"x\":100,\"y\":100,\"plane\":0},\"to\":" + TO_VARROCK + ",\"ticks\":1}");
		TransportModel m = build(t, null, null);
		assertTrue(m.usable(state(1, false), Collections.emptySet(), true).isEmpty());
		PlayerState done = PlayerState.builder().chunk(VARROCK).quest("OTHER_QUEST").quest("DRAGON_SLAYER_I").build();
		assertEquals(1, m.usable(done, Collections.emptySet(), true).size());
		PlayerState both = done.withUnlockedChunk(TileMath.regionId(100, 100));
		assertEquals(2, m.usable(both, Collections.emptySet(), true).size());
	}

	@Test
	void resultSortedById()
	{
		String t = edges(edge("c", TO_VARROCK, "{}"), edge("a", TO_VARROCK, "{}"), edge("b", TO_VARROCK, "{}"));
		List<UsableEdge> e = build(t, null, null).usable(state(1, false), Collections.emptySet(), true);
		assertEquals("a", e.get(0).getEdge().getId());
		assertEquals("b", e.get(1).getEdge().getId());
		assertEquals("c", e.get(2).getEdge().getId());
	}
}
