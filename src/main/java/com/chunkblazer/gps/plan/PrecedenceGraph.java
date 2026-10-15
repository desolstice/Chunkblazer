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

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.Value;

/**
 * The selected nodes with their precedence constraints. {@code preds.get(id)} lists the node ids that must be
 * visited before {@code id}; every node has an entry.
 */
@Value
public class PrecedenceGraph
{
	List<PlanNode> nodes;
	Map<String, List<String>> preds;
	List<Plan.Unplannable> unplannable;
	/** Ids of the goal nodes (the rest are providers pulled in for them). */
	Set<String> goals;

	public static PrecedenceGraph of(List<PlanNode> nodes, Map<String, List<String>> preds)
	{
		return of(nodes, preds, Collections.emptyList());
	}

	public static PrecedenceGraph of(List<PlanNode> nodes, Map<String, List<String>> preds,
		List<Plan.Unplannable> unplannable)
	{
		return of(nodes, preds, unplannable, null);
	}

	/** {@code goals == null} means every node counts as a goal. */
	public static PrecedenceGraph of(List<PlanNode> nodes, Map<String, List<String>> preds,
		List<Plan.Unplannable> unplannable, Set<String> goals)
	{
		Set<String> goalSet = new LinkedHashSet<>();
		if (goals == null)
		{
			nodes.forEach(n -> goalSet.add(n.getId()));
		}
		else
		{
			goalSet.addAll(goals);
		}
		Map<String, List<String>> copy = new LinkedHashMap<>();
		for (PlanNode n : nodes)
		{
			List<String> p = preds.get(n.getId());
			copy.put(n.getId(), p == null ? Collections.emptyList() : Collections.unmodifiableList(new ArrayList<>(p)));
		}
		return new PrecedenceGraph(Collections.unmodifiableList(new ArrayList<>(nodes)),
			Collections.unmodifiableMap(copy), Collections.unmodifiableList(new ArrayList<>(unplannable)), Collections.unmodifiableSet(goalSet));
	}

	public boolean hasCycle()
	{
		return !findCycle().isEmpty();
	}

	/** The ids on one precedence cycle in path order, or empty when the graph is acyclic. Deterministic. */
	public List<String> findCycle()
	{
		Map<String, Integer> color = new HashMap<>();
		List<String> path = new ArrayList<>();
		for (PlanNode n : nodes)
		{
			if (!color.containsKey(n.getId()))
			{
				List<String> c = visit(n.getId(), color, path);
				if (!c.isEmpty())
				{
					return c;
				}
			}
		}
		return Collections.emptyList();
	}

	private List<String> visit(String id, Map<String, Integer> color, List<String> path)
	{
		color.put(id, 1);
		path.add(id);
		List<String> ps = preds.get(id);
		if (ps != null)
		{
			for (String p : ps)
			{
				Integer c = color.get(p);
				if (c == null)
				{
					if (preds.containsKey(p))
					{
						List<String> found = visit(p, color, path);
						if (!found.isEmpty())
						{
							return found;
						}
					}
				}
				else if (c == 1)
				{
					return new ArrayList<>(path.subList(path.indexOf(p), path.size()));
				}
			}
		}
		path.remove(path.size() - 1);
		color.put(id, 2);
		return Collections.emptyList();
	}
}
