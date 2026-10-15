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

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/** One entry of quest_requirements.json. Unknown JSON fields are ignored by Gson. */
public class QuestHelperEntry
{
	private static final String RESOURCE = "/com/chunkblazer/quest_requirements.json";

	private final List<Integer> chunks;
	private final List<String> quests;
	private final Map<String, Integer> skills;

	public QuestHelperEntry(List<Integer> chunks, List<String> quests, Map<String, Integer> skills)
	{
		this.chunks = chunks;
		this.quests = quests;
		this.skills = skills;
	}

	public List<Integer> getChunks()
	{
		return chunks == null ? Collections.emptyList() : chunks;
	}

	public List<String> getQuests()
	{
		return quests == null ? Collections.emptyList() : quests;
	}

	public Map<String, Integer> getSkills()
	{
		return skills == null ? Collections.emptyMap() : skills;
	}

	public static Map<String, QuestHelperEntry> loadFromResource()
	{
		try (InputStream in = QuestHelperEntry.class.getResourceAsStream(RESOURCE))
		{
			if (in == null)
			{
				return Collections.emptyMap();
			}
			try (Reader r = new InputStreamReader(in, StandardCharsets.UTF_8))
			{
				Map<String, QuestHelperEntry> m = new Gson().fromJson(r,
					new TypeToken<Map<String, QuestHelperEntry>>() {}.getType());
				return m == null ? Collections.emptyMap() : m;
			}
		}
		catch (Exception e)
		{
			return Collections.emptyMap();
		}
	}
}
