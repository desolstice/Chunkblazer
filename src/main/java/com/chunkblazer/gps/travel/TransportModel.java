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

import com.chunkblazer.gps.data.GpsData;
import com.chunkblazer.gps.data.ItemDef;
import com.chunkblazer.gps.data.LocationEntry;
import com.chunkblazer.gps.data.Requirements;
import com.chunkblazer.gps.data.Tile;
import com.chunkblazer.gps.data.TransportEdge;
import com.chunkblazer.gps.model.PlayerState;
import com.chunkblazer.gps.model.QuestCatalog;
import com.chunkblazer.gps.model.Requirement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/** Filters data and charter transport edges by what the player can use, own or buy. Immutable and thread-safe. */
public final class TransportModel
{
	private static final Comparator<LocationEntry> TILE_ORDER = Comparator
		.comparingInt((LocationEntry l) -> l.getX())
		.thenComparingInt(l -> l.getY())
		.thenComparingInt(LocationEntry::getPlane);

	private final List<TransportEdge> edges;
	private final Map<Integer, ItemDef> items = new HashMap<>();
	private final Map<String, LocationEntry> shopsExact = new HashMap<>();
	private final Map<String, LocationEntry> shopsLower = new HashMap<>();
	private final List<ChunkNode> chunks;

	/** The quest catalog is accepted for API symmetry; quest requirements are checked against PlayerState directly. */
	public TransportModel(GpsData data, List<ChunkNode> chunks, QuestCatalog quests)
	{
		List<TransportEdge> list = new ArrayList<>();
		if (data != null)
		{
			for (TransportEdge e : data.getTransport())
			{
				if (e != null && e.getId() != null && e.getTo() != null && e.getTo().isKnown())
				{
					list.add(e);
				}
			}
			for (ItemDef d : data.getItems())
			{
				if (d != null)
				{
					items.putIfAbsent(d.getId(), d);
				}
			}
			for (LocationEntry l : data.getLocations())
			{
				if (l == null || l.getName() == null || l.getX() == null || l.getY() == null
					|| !"SHOP".equalsIgnoreCase(l.getKind()))
				{
					continue;
				}
				shopsExact.merge(l.getName(), l, TransportModel::earlier);
				shopsLower.merge(l.getName().toLowerCase(Locale.ROOT), l, TransportModel::earlier);
			}
		}
		list.sort(Comparator.comparing(TransportEdge::getId));
		this.edges = Collections.unmodifiableList(list);
		List<ChunkNode> cs = new ArrayList<>();
		if (chunks != null)
		{
			for (ChunkNode c : chunks)
			{
				if (c != null)
				{
					cs.add(c);
				}
			}
		}
		this.chunks = Collections.unmodifiableList(cs);
	}

	private static LocationEntry earlier(LocationEntry a, LocationEntry b)
	{
		return TILE_ORDER.compare(a, b) <= 0 ? a : b;
	}

	/** Usable edges sorted by id; duplicate ids keep the first. */
	public List<UsableEdge> usable(PlayerState s, Set<String> acquiredEdgeIds, boolean includeAcquirable)
	{
		if (s == null)
		{
			return new ArrayList<>();
		}
		Set<String> acquired = acquiredEdgeIds == null ? Collections.<String>emptySet() : acquiredEdgeIds;
		Map<String, UsableEdge> out = new TreeMap<>();
		for (TransportEdge e : edges)
		{
			if (!out.containsKey(e.getId()))
			{
				UsableEdge u = evaluate(e, s, acquired, includeAcquirable);
				if (u != null)
				{
					out.put(e.getId(), u);
				}
			}
		}
		for (UsableEdge c : charterEdges(s))
		{
			out.putIfAbsent(c.getEdge().getId(), c);
		}
		return new ArrayList<>(out.values());
	}

	private UsableEdge evaluate(TransportEdge e, PlayerState s, Set<String> acquired, boolean includeAcquirable)
	{
		Tile to = e.getTo();
		if (!s.hasUnlockedChunk(TileMath.regionId(to.getX(), to.getY())))
		{
			return null;
		}
		Tile from = e.getFrom();
		if (from != null && (!from.isKnown() || !s.hasUnlockedChunk(TileMath.regionId(from.getX(), from.getY()))))
		{
			return null;
		}
		Requirements req = e.getRequires();
		Requirement.Builder rb = Requirement.builder();
		for (Map.Entry<String, Integer> k : req.getSkills().entrySet())
		{
			if (k.getKey() != null && k.getValue() != null)
			{
				rb.skill(k.getKey(), k.getValue());
			}
		}
		for (String q : req.getQuests())
		{
			if (q != null)
			{
				rb.quest(q);
			}
		}
		rb.questPoints(req.getQuestPoints());
		rb.combatLevel(req.getCombatLevel());
		if (!rb.build().missing(s).isEmpty())
		{
			return null;
		}
		boolean granted = e.getName() != null && s.hasUnlock("TELEPORT:" + e.getName());
		// A token-granted teleport consumes no item, so it carries no consumable penalty.
		int ticks = ticks(e, !granted);
		if (granted || acquired.contains(e.getId()))
		{
			return new UsableEdge(e, UsableEdge.Availability.AVAILABLE, Collections.<Acquisition>emptyList(), ticks);
		}
		List<Acquisition> acquisitions = new ArrayList<>();
		for (Requirements.ItemReq r : req.getItems())
		{
			if (r == null)
			{
				continue;
			}
			long owned = 0;
			for (Integer id : r.getIds())
			{
				if (id != null)
				{
					owned += s.ownedCount(id);
				}
			}
			if (owned >= r.getQty())
			{
				continue;
			}
			Acquisition a = includeAcquirable ? cheapestShop(s, r, (int) Math.min(Integer.MAX_VALUE, r.getQty() - owned)) : null;
			if (a == null)
			{
				return null;
			}
			acquisitions.add(a);
		}
		return new UsableEdge(e,
			acquisitions.isEmpty() ? UsableEdge.Availability.AVAILABLE : UsableEdge.Availability.ACQUIRABLE,
			acquisitions, ticks);
	}

	private static int ticks(TransportEdge e, boolean consumes)
	{
		long t = (long) Math.max(0, e.getTicks()) + (consumes && e.isConsumesItem() ? TravelConfig.CONSUMABLE_PENALTY_TICKS : 0);
		return (int) Math.min(t, TravelConfig.INF);
	}

	/** Cheapest SHOP source across the any-of item ids; ties broken by shop name then item id. Shops whose known location lies in a locked chunk
	 * are skipped; shops with no known location are kept (the Acquisition has a null shopTile). Null if none. */
	private Acquisition cheapestShop(PlayerState s, Requirements.ItemReq r, int missingQty)
	{
		Integer bestItem = null;
		ItemDef.Source best = null;
		long bestPrice = Long.MAX_VALUE;
		Set<Integer> ids = new TreeSet<>();
		for (Integer id : r.getIds())
		{
			if (id != null)
			{
				ids.add(id);
			}
		}
		for (Integer id : ids)
		{
			ItemDef def = items.get(id);
			if (def == null)
			{
				continue;
			}
			for (ItemDef.Source src : def.getSources())
			{
				if (src == null || !"SHOP".equalsIgnoreCase(src.getType()) || src.getShop() == null)
				{
					continue;
				}
				Tile st = shopTile(src.getShop());
				if (st != null && !s.hasUnlockedChunk(TileMath.regionId(st.getX(), st.getY())))
				{
					continue;
				}
				// A shop with no stated price ranks after every priced shop.
				long price = src.getPrice() == null ? Long.MAX_VALUE - 1 : src.getPrice();
				if (best == null || price < bestPrice
					|| (price == bestPrice && src.getShop().compareTo(best.getShop()) < 0))
				{
					best = src;
					bestItem = id;
					bestPrice = price;
				}
			}
		}
		if (best == null)
		{
			return null;
		}
		return new Acquisition(bestItem, items.get(bestItem).getName(), best.getShop(), shopTile(best.getShop()),
			best.getPrice() == null ? 0 : best.getPrice(), missingQty, best.getPrice() != null);
	}

	private Tile shopTile(String shop)
	{
		LocationEntry l = shopsExact.get(shop);
		if (l == null)
		{
			l = shopsLower.get(shop.toLowerCase(Locale.ROOT));
		}
		return l == null ? null : new Tile(l.getX(), l.getY(), l.getPlane(), false, "SHOP");
	}

	private List<UsableEdge> charterEdges(PlayerState s)
	{
		List<UsableEdge> out = new ArrayList<>();
		for (ChunkNode c : chunks)
		{
			if (c.getCharterDestinationIds() == null || c.getRegionIds() == null)
			{
				continue;
			}
			// A chunk's regions are mutually walkable, so the lowest unlocked region is the departure point.
			Integer fromRegion = null;
			for (Integer r : c.getRegionIds())
			{
				if (r != null && s.hasUnlockedChunk(r) && (fromRegion == null || r < fromRegion))
				{
					fromRegion = r;
				}
			}
			if (fromRegion == null)
			{
				continue;
			}
			for (Integer dest : c.getCharterDestinationIds())
			{
				if (dest == null || dest.equals(fromRegion) || !s.hasUnlockedChunk(dest))
				{
					continue;
				}
				Tile a = TileMath.centre(fromRegion);
				Tile b = TileMath.centre(dest);
				long t = TravelConfig.CHARTER_BASE_TICKS
					+ (long) Math.ceil(TileMath.tileDistance(a, b) * TravelConfig.CHARTER_TICKS_PER_TILE);
				int ticks = (int) Math.min(t, TravelConfig.INF);
				String id = "charter:" + fromRegion + "-" + dest;
				TransportEdge e = new TransportEdge(id, "CHARTER", "Charter to region " + dest, a, b, ticks,
					null, false);
				out.add(new UsableEdge(e, UsableEdge.Availability.AVAILABLE, Collections.<Acquisition>emptyList(),
					ticks));
			}
		}
		return out;
	}
}
