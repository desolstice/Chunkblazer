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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.chunkblazer.gps.travel.TravelConfig;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Random;
import java.util.function.IntBinaryOperator;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class SolverTest
{
	// nodes on a line: index 0 is START at x=0; node k at position pos[k]
	private static IntBinaryOperator lineCost(int[] pos)
	{
		return (a, b) -> Math.abs(pos[a] - pos[b]);
	}

	private static int[][] noPreds(int n)
	{
		int[][] p = new int[n + 1][];
		for (int i = 0; i <= n; i++)
		{
			p[i] = new int[0];
		}
		return p;
	}

	@Test
	void visitsAllNodesExactlyOnce()
	{
		int[] pos = {0, 30, 10, 20};
		int[] order = Solver.solve(3, lineCost(pos), noPreds(3), 1, 500);
		assertEquals(3, order.length);
		assertEquals(new HashSet<>(Arrays.asList(1, 2, 3)), Arrays.stream(order).boxed().collect(Collectors.toSet()));
	}

	@Test
	void openPathOnALineIsOptimal()
	{
		int[] pos = {0, 30, 10, 20};
		int[] order = Solver.solve(3, lineCost(pos), noPreds(3), 1, 500);
		assertEquals(30, Solver.pathCost(order, lineCost(pos)));
	}

	@Test
	void precedenceRespectedEvenWhenItCostsMore()
	{
		int[] pos = {0, 10, 20, 30}; // node1@10 must come after node3@30
		int[][] preds = noPreds(3);
		preds[1] = new int[]{3};
		int[] order = Solver.solve(3, lineCost(pos), preds, 1, 500);
		assertTrue(Solver.valid(order, preds));
		assertTrue(indexOf(order, 3) < indexOf(order, 1));
	}

	@Test
	void questChainInterleavesNearbyTask()
	{
		// START@0; quest step1@10 (node1), step2@100 (node2, after node1); task@55 (node3) lies between
		int[] pos = {0, 10, 100, 55};
		int[][] preds = noPreds(3);
		preds[2] = new int[]{1};
		int[] order = Solver.solve(3, lineCost(pos), preds, 1, 500);
		assertArrayEquals(new int[]{1, 3, 2}, order); // task done between the two quest steps
		assertEquals(100, Solver.pathCost(order, lineCost(pos)));
	}

	@Test
	void localSearchIsNoWorseThanNearestNeighbourAndCloseToOptimum()
	{
		int[][] pts = {{0, 0}, {10, 0}, {0, 10}, {11, 1}, {1, 11}, {5, 5}};
		IntBinaryOperator c = (a, b) -> (int) Math.round(Math.hypot(pts[a][0] - pts[b][0], pts[a][1] - pts[b][1]));
		int[] order = Solver.solve(5, c, noPreds(5), 3, 1000);
		long nn = Solver.pathCost(Solver.construct(5, c, noPreds(5)), c);
		long cost = Solver.pathCost(order, c);
		assertTrue(cost <= nn, cost + " vs nn " + nn);
		assertTrue(cost <= Math.round(bruteForce(5, c) * 1.1), cost + " vs optimum " + bruteForce(5, c));
	}

	@Test
	void asymmetricCostsAreNotAssumedSymmetric()
	{
		// going "down" the index chain is free, going up is expensive; the best open path is 1,2,3,4 style descending use
		int n = 7;
		Random r = new Random(11);
		int[][] m = new int[n + 1][n + 1];
		for (int a = 0; a <= n; a++)
		{
			for (int b = 0; b <= n; b++)
			{
				m[a][b] = a == b ? 0 : 1 + r.nextInt(50);
			}
		}
		IntBinaryOperator c = (a, b) -> m[a][b];
		int[] order = Solver.solve(n, c, noPreds(n), 4, 2000);
		long cost = Solver.pathCost(order, c);
		long best = bruteForce(n, c);
		assertTrue(cost <= Solver.pathCost(Solver.construct(n, c, noPreds(n)), c));
		assertTrue(cost <= Math.round(best * 1.25), cost + " vs optimum " + best);
	}

	@Test
	void unreachableNodeIsVisitedLast()
	{
		int[] pos = {0, 10, 20};
		IntBinaryOperator c = (a, b) -> b == 1 && a == 0 ? 5 : (a == 2 || b == 2 ? TravelConfig.INF : Math.abs(pos[a] - pos[b]));
		int[] order = Solver.solve(2, c, noPreds(2), 1, 200);
		assertArrayEquals(new int[]{1, 2}, order);
		assertTrue(Solver.pathCost(order, c) >= TravelConfig.INF);
	}

	private static long bruteForce(int n, IntBinaryOperator c)
	{
		return permute(new int[n], new boolean[n + 1], 0, n, c, Long.MAX_VALUE);
	}

	private static long permute(int[] cur, boolean[] used, int k, int n, IntBinaryOperator c, long best)
	{
		if (k == n)
		{
			return Math.min(best, Solver.pathCost(cur, c));
		}
		for (int v = 1; v <= n; v++)
		{
			if (!used[v])
			{
				used[v] = true;
				cur[k] = v;
				best = permute(cur, used, k + 1, n, c, best);
				used[v] = false;
			}
		}
		return best;
	}

	@Test
	void deterministicForSameSeed()
	{
		Random r = new Random(5);
		int[] pos = new int[41];
		for (int i = 1; i <= 40; i++)
		{
			pos[i] = r.nextInt(1000);
		}
		int[] a = Solver.solve(40, lineCost(pos), noPreds(40), 9, 2000);
		int[] b = Solver.solve(40, lineCost(pos), noPreds(40), 9, 2000);
		assertArrayEquals(a, b);
	}

	@Test
	void timeoutStillReturnsValidOrder()
	{
		Random r = new Random(1);
		int n = 400;
		int[] pos = new int[n + 1];
		for (int i = 1; i <= n; i++)
		{
			pos[i] = r.nextInt(100000);
		}
		int[][] preds = noPreds(n);
		for (int i = 2; i <= n; i += 2)
		{
			preds[i] = new int[]{i - 1}; // many chains
		}
		int[] order = Solver.solve(n, lineCost(pos), preds, 1, 1); // 1 ms budget
		assertEquals(n, order.length);
		assertTrue(Solver.valid(order, preds));
	}

	@Test
	void precedenceHeavyRandomInstancesStayValid()
	{
		Random r = new Random(3);
		int n = 60;
		int[] pos = new int[n + 1];
		for (int i = 1; i <= n; i++)
		{
			pos[i] = r.nextInt(5000);
		}
		int[][] preds = noPreds(n);
		for (int i = 2; i <= n; i++)
		{
			if (r.nextInt(3) == 0)
			{
				preds[i] = new int[]{1 + r.nextInt(i - 1)};
			}
		}
		int[] order = Solver.solve(n, lineCost(pos), preds, 2, 1500);
		assertTrue(Solver.valid(order, preds));
		assertTrue(Solver.pathCost(order, lineCost(pos)) <= Solver.pathCost(Solver.construct(n, lineCost(pos), preds), lineCost(pos)));
	}

	@Test
	void emptyAndSingle()
	{
		assertEquals(0, Solver.solve(0, (a, b) -> 0, noPreds(0), 1, 100).length);
		assertArrayEquals(new int[]{1}, Solver.solve(1, (a, b) -> 7, noPreds(1), 1, 100));
	}

	@Test
	void cycleDoesNotHangAndIsReportedInvalid()
	{
		int[][] preds = noPreds(2);
		preds[1] = new int[]{2};
		preds[2] = new int[]{1};
		int[] order = Solver.solve(2, (a, b) -> 1, preds, 1, 100);
		assertEquals(2, order.length);
		assertFalse(Solver.valid(order, preds));
	}

	@Test
	void hugeDeadlineDoesNotOverflowAndMatchesALongOne()
	{
		Random r = new Random(5);
		int n = 40;
		int[] pos = new int[n + 1];
		for (int i = 1; i <= n; i++)
		{
			pos[i] = r.nextInt(1000);
		}
		int[] huge = Solver.solve(n, lineCost(pos), noPreds(n), 3, Long.MAX_VALUE);
		int[] normal = Solver.solve(n, lineCost(pos), noPreds(n), 3, 20_000);
		assertArrayEquals(normal, huge);
		assertTrue(Solver.valid(Solver.solve(n, lineCost(pos), noPreds(n), 3, -5), noPreds(n)));
	}

	@Test
	void twoOptEvaluationCountGrowsQuadratically()
	{
		int n = 200;
		int[] pos = new int[n + 1];
		for (int i = 1; i <= n; i++)
		{
			pos[i] = i * 10; // nearest neighbour is already optimal, so this measures one pass of each local search
		}
		long[] calls = {0};
		IntBinaryOperator counting = (a, b) ->
		{
			calls[0]++;
			return Math.abs(pos[a] - pos[b]);
		};
		int[] order = Solver.solve(n, counting, noPreds(n), 1, 20_000);
		assertEquals(10L * n, Solver.pathCost(order, lineCost(pos)));
		assertTrue(calls[0] < 1_500_000L, "cost evaluations " + calls[0]);
	}

	private static int indexOf(int[] a, int v)
	{
		for (int i = 0; i < a.length; i++)
		{
			if (a[i] == v)
			{
				return i;
			}
		}
		return -1;
	}
}
