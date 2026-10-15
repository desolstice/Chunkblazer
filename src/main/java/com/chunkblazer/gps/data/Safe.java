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
package com.chunkblazer.gps.data;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Null-safe, read-only views over Gson-produced collections. */
final class Safe
{
	private Safe()
	{
	}

	/** Never null, never modifiable, never contains null elements. */
	static <T> List<T> list(List<T> in)
	{
		if (in == null || in.isEmpty())
		{
			return Collections.emptyList();
		}
		if (!in.contains(null))
		{
			return Collections.unmodifiableList(in);
		}
		List<T> out = new ArrayList<>(in.size());
		for (T t : in)
		{
			if (t != null)
			{
				out.add(t);
			}
		}
		return Collections.unmodifiableList(out);
	}

	/** Never null, never modifiable, never contains null keys or values. */
	static <K, V> Map<K, V> map(Map<K, V> in)
	{
		if (in == null || in.isEmpty())
		{
			return Collections.emptyMap();
		}
		Map<K, V> out = new LinkedHashMap<>();
		for (Map.Entry<K, V> e : in.entrySet())
		{
			if (e.getKey() != null && e.getValue() != null)
			{
				out.put(e.getKey(), e.getValue());
			}
		}
		return Collections.unmodifiableMap(out);
	}
}
