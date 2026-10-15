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
package com.chunkblazer.gps.model;

import com.chunkblazer.gps.data.GpsData;
import com.chunkblazer.gps.data.QuestDef;
import com.chunkblazer.gps.data.Requirements;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Quests merged from wiki data and quest_requirements.json. */
public final class QuestCatalog
{
	private final Map<String, QuestModel> quests;

	private QuestCatalog(Map<String, QuestModel> quests)
	{
		this.quests = quests;
	}

	public static QuestCatalog build(GpsData data, Map<String, QuestHelperEntry> helper)
	{
		Map<String, QuestModel> out = new LinkedHashMap<>();
		Map<String, QuestHelperEntry> h = helper == null ? Collections.emptyMap() : helper;
		for (Map.Entry<String, QuestHelperEntry> e : h.entrySet())
		{
			QuestHelperEntry entry = e.getValue() == null ? new QuestHelperEntry(null, null, null) : e.getValue();
			String id = e.getKey();
			out.put(id, new QuestModel(id, id, helperRequirement(entry), Collections.emptyList(),
				new QuestDef.Rewards(null, null, 0, null), false, new ArrayList<>(entry.getChunks())));
		}
		for (QuestDef def : data.getQuests())
		{
			QuestHelperEntry entry = h.get(def.getId());
			if (entry == null)
			{
				entry = new QuestHelperEntry(null, null, null);
			}
			Requirement req = helperRequirement(entry).merge(wikiRequirement(def.getRequirements()));
			out.put(def.getId(), new QuestModel(def.getId(), def.getName(), req, def.getSteps(),
				def.getRewards(), true, new ArrayList<>(entry.getChunks())));
		}
		return new QuestCatalog(out);
	}

	private static Requirement helperRequirement(QuestHelperEntry e)
	{
		Requirement.Builder b = Requirement.builder();
		e.getSkills().forEach(b::skill);
		e.getQuests().forEach(b::quest);
		return b.build();
	}

	private static Requirement wikiRequirement(Requirements r)
	{
		Requirement.Builder b = Requirement.builder();
		r.getSkills().forEach(b::skill);
		r.getQuests().forEach(b::quest);
		b.questPoints(r.getQuestPoints());
		b.combatLevel(r.getCombatLevel());
		for (Requirements.ItemReq i : r.getItems())
		{
			if (i.getIds().isEmpty())
			{
				b.unknown("Item: " + i.getName());
			}
			else
			{
				b.item(new ItemNeed(new HashSet<>(i.getIds()), i.getQty(), i.getName()));
			}
		}
		return b.build();
	}

	public Optional<QuestModel> get(String id)
	{
		return Optional.ofNullable(quests.get(id));
	}

	public Collection<QuestModel> all()
	{
		return Collections.unmodifiableCollection(quests.values());
	}

	/** Transitive prerequisites, prerequisites first, excluding the quest itself. */
	public List<String> prerequisitesClosure(String questId)
	{
		List<String> out = new ArrayList<>();
		QuestModel root = quests.get(questId);
		if (root == null)
		{
			return out;
		}
		Set<String> visited = new HashSet<>();
		visited.add(questId);
		Deque<Iterator<String>> stack = new ArrayDeque<>();
		Deque<String> path = new ArrayDeque<>();
		stack.push(root.getRequirement().getQuests().iterator());
		path.push(questId);
		while (!stack.isEmpty())
		{
			Iterator<String> it = stack.peek();
			if (it.hasNext())
			{
				String next = it.next();
				QuestModel m = quests.get(next);
				if (m != null && visited.add(next))
				{
					stack.push(m.getRequirement().getQuests().iterator());
					path.push(next);
				}
			}
			else
			{
				stack.pop();
				String done = path.pop();
				if (!done.equals(questId))
				{
					out.add(done);
				}
			}
		}
		return out;
	}
}
