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
import java.util.List;
import java.util.Random;
import java.util.function.IntBinaryOperator;

/**
 * Precedence-aware open-path ordering: nearest-neighbour construction followed by 2-opt and or-opt local search.
 * Index 0 is the START node, 1..n are plan nodes. The cost function may be asymmetric and may return values at or
 * above {@code TravelConfig.INF}; path costs are summed in a long so such edges saturate rather than overflow.
 */
public final class Solver
{
	private static final int CHECK_EVERY = 256;

	private Solver()
	{
	}

	/** Returns the visiting order as node indices 1..n. */
	public static int[] solve(int n, IntBinaryOperator cost, int[][] preds, long seed, long deadlineMillis)
	{
		if (n == 0)
		{
			return new int[0];
		}
		long deadline = System.nanoTime() + deadlineMillis * 1_000_000L;
		int[] order = construct(n, cost, preds);
		if (valid(order, preds))
		{
			new Improver(order, cost, preds, new Random(seed), deadline).run();
		}
		return order;
	}

	static int[] construct(int n, IntBinaryOperator cost, int[][] preds)
	{
		boolean[] placed = new boolean[n + 1];
		int[] order = new int[n];
		int cur = 0;
		for (int k = 0; k < n; k++)
		{
			int best = -1;
			int bestCost = Integer.MAX_VALUE;
			for (int v = 1; v <= n; v++)
			{
				if (placed[v] || !ready(v, placed, preds))
				{
					continue;
				}
				int c = cost.applyAsInt(cur, v);
				if (best < 0 || c < bestCost)
				{
					bestCost = c;
					best = v;
				}
			}
			if (best < 0)
			{
				// cycle: append the rest by index; valid() will report it
				for (int v = 1; v <= n; v++)
				{
					if (!placed[v])
					{
						best = v;
						break;
					}
				}
			}
			placed[best] = true;
			order[k] = best;
			cur = best;
		}
		return order;
	}

	private static boolean ready(int v, boolean[] placed, int[][] preds)
	{
		for (int p : preds[v])
		{
			if (!placed[p])
			{
				return false;
			}
		}
		return true;
	}

	public static boolean valid(int[] order, int[][] preds)
	{
		int[] pos = new int[order.length + 1];
		for (int i = 0; i < order.length; i++)
		{
			pos[order[i]] = i;
		}
		for (int v = 1; v <= order.length; v++)
		{
			for (int p : preds[v])
			{
				if (pos[p] >= pos[v])
				{
					return false;
				}
			}
		}
		return true;
	}

	/** Cost of START, order[0], ..., order[n-1]; no return leg. */
	public static long pathCost(int[] order, IntBinaryOperator cost)
	{
		long total = 0;
		int cur = 0;
		for (int v : order)
		{
			total += cost.applyAsInt(cur, v);
			cur = v;
		}
		return total;
	}

	private static final class Improver
	{
		private final int[] order;
		private final int n;
		private final IntBinaryOperator cost;
		private final int[][] preds;
		private final int[][] succs;
		private final int[] pos;
		private final Random random;
		private final long deadline;
		private int evals;
		private boolean timedOut;

		Improver(int[] order, IntBinaryOperator cost, int[][] preds, Random random, long deadline)
		{
			this.order = order;
			this.n = order.length;
			this.cost = cost;
			this.preds = preds;
			this.random = random;
			this.deadline = deadline;
			this.pos = new int[n + 1];
			List<List<Integer>> s = new ArrayList<>();
			for (int i = 0; i <= n; i++)
			{
				s.add(new ArrayList<>());
			}
			for (int v = 1; v <= n; v++)
			{
				for (int p : preds[v])
				{
					s.get(p).add(v);
				}
			}
			this.succs = new int[n + 1][];
			for (int i = 0; i <= n; i++)
			{
				succs[i] = s.get(i).stream().mapToInt(Integer::intValue).toArray();
			}
			reindex();
		}

		void run()
		{
			boolean improved = true;
			while (improved && !timedOut)
			{
				improved = twoOpt();
				if (!timedOut)
				{
					improved |= orOpt();
				}
			}
		}

		private void reindex()
		{
			for (int i = 0; i < n; i++)
			{
				pos[order[i]] = i;
			}
		}

		private boolean tick()
		{
			if ((++evals & (CHECK_EVERY - 1)) == 0 && System.nanoTime() - deadline > 0)
			{
				timedOut = true;
			}
			return timedOut;
		}

		private List<Integer> shuffledStarts(int count)
		{
			List<Integer> l = new ArrayList<>();
			for (int i = 0; i < count; i++)
			{
				l.add(i);
			}
			Collections.shuffle(l, random);
			return l;
		}

		private int node(int idx)
		{
			return idx < 0 ? 0 : order[idx];
		}

		/** Segment reversal; reversed internal legs are recomputed because costs may be asymmetric. */
		private boolean twoOpt()
		{
			boolean any = false;
			for (int i : shuffledStarts(n))
			{
				for (int j = i + 1; j < n; j++)
				{
					if (tick())
					{
						return any;
					}
					boolean blocked = false;
					for (int p : preds[order[j]])
					{
						if (pos[p] >= i && pos[p] < j)
						{
							blocked = true;
							break;
						}
					}
					if (blocked)
					{
						break; // every longer segment also contains the pair
					}
					long fwd = 0;
					long rev = 0;
					for (int k = i; k < j; k++)
					{
						fwd += cost.applyAsInt(order[k], order[k + 1]);
						rev += cost.applyAsInt(order[k + 1], order[k]);
					}
					int prev = node(i - 1);
					long oldCost = cost.applyAsInt(prev, order[i]) + fwd;
					long newCost = cost.applyAsInt(prev, order[j]) + rev;
					if (j + 1 < n)
					{
						oldCost += cost.applyAsInt(order[j], order[j + 1]);
						newCost += cost.applyAsInt(order[i], order[j + 1]);
					}
					if (newCost < oldCost)
					{
						for (int a = i, b = j; a < b; a++, b--)
						{
							int t = order[a];
							order[a] = order[b];
							order[b] = t;
						}
						reindex();
						any = true;
					}
				}
			}
			return any;
		}

		/** Moves a block of 1-3 consecutive nodes to another position, forward orientation only. */
		private boolean orOpt()
		{
			boolean any = false;
			for (int len = 1; len <= 3 && len < n; len++)
			{
				for (int s : shuffledStarts(n - len + 1))
				{
					if (tick())
					{
						return any;
					}
					any |= tryMove(s, len);
				}
			}
			return any;
		}

		private boolean tryMove(int s, int len)
		{
			int e = s + len - 1;
			int first = order[s];
			int last = order[e];
			// feasible insertion indices t (into the array with the block removed)
			int lo = 0;
			int hi = n - len;
			for (int k = s; k <= e; k++)
			{
				for (int p : preds[order[k]])
				{
					if (pos[p] < s || pos[p] > e)
					{
						lo = Math.max(lo, remPos(p, e, len) + 1);
					}
				}
				for (int q : succs[order[k]])
				{
					if (pos[q] < s || pos[q] > e)
					{
						hi = Math.min(hi, remPos(q, e, len));
					}
				}
			}
			int a = node(s - 1);
			boolean hasNext = e + 1 < n;
			long removeGain = cost.applyAsInt(a, first);
			if (hasNext)
			{
				removeGain += cost.applyAsInt(last, order[e + 1]) - cost.applyAsInt(a, order[e + 1]);
			}
			long bestDelta = 0;
			int bestT = -1;
			for (int t = lo; t <= hi; t++)
			{
				if (t == s)
				{
					continue;
				}
				if (tick())
				{
					return false;
				}
				int x = t == 0 ? 0 : remNode(t - 1, s, len);
				long add = cost.applyAsInt(x, first);
				if (t < n - len)
				{
					int y = remNode(t, s, len);
					add += cost.applyAsInt(last, y) - cost.applyAsInt(x, y);
				}
				long delta = add - removeGain;
				if (delta < bestDelta)
				{
					bestDelta = delta;
					bestT = t;
				}
			}
			if (bestT < 0)
			{
				return false;
			}
			int[] block = new int[len];
			System.arraycopy(order, s, block, 0, len);
			int[] rem = new int[n - len];
			for (int k = 0; k < rem.length; k++)
			{
				rem[k] = remNode(k, s, len);
			}
			int w = 0;
			for (int k = 0; k < bestT; k++)
			{
				order[w++] = rem[k];
			}
			for (int b : block)
			{
				order[w++] = b;
			}
			for (int k = bestT; k < rem.length; k++)
			{
				order[w++] = rem[k];
			}
			reindex();
			return true;
		}

		private int remPos(int node, int blockEnd, int len)
		{
			return pos[node] > blockEnd ? pos[node] - len : pos[node];
		}

		private int remNode(int remIdx, int blockStart, int len)
		{
			return remIdx < blockStart ? order[remIdx] : order[remIdx + len];
		}
	}
}
