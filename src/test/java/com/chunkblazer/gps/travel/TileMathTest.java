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

import com.chunkblazer.gps.data.Tile;
import static org.junit.jupiter.api.Assertions.assertEquals;
import org.junit.jupiter.api.Test;

class TileMathTest
{
	@Test
	void regionIdRoundTrip()
	{
		assertEquals(12850, TileMath.regionId(50 * 64 + 5, 50 * 64 + 63));
		assertEquals(50, TileMath.regionX(12850));
		assertEquals(50, TileMath.regionY(12850));
	}

	@Test
	void knownRegion()
	{
		// Lumbridge spawn (3222, 3218) is region 12850
		assertEquals(12850, TileMath.regionId(3222, 3218));
	}

	@Test
	void centreOfRegion()
	{
		Tile c = TileMath.centre(12850);
		assertEquals(50 * 64 + 32, (int) c.getX());
		assertEquals(50 * 64 + 32, (int) c.getY());
		assertEquals(0, c.getPlane());
	}

	@Test
	void distanceIsDiagonalAware()
	{
		assertEquals(10, TileMath.tileDistance(tile(0, 0), tile(10, 10)));
		assertEquals(10, TileMath.tileDistance(tile(0, 0), tile(10, 3)));
		assertEquals(0, TileMath.tileDistance(tile(5, 5), tile(5, 5)));
	}

	private static Tile tile(int x, int y)
	{
		return new Tile(x, y, 0, false, null);
	}
}
