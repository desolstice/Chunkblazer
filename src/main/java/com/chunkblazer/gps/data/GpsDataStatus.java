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

import java.nio.file.Path;
import java.util.List;
import lombok.Value;

@Value
public class GpsDataStatus
{
	Path dir;
	boolean directoryFound;
	List<String> missingFiles;
	List<String> invalidFiles;
	String dataVersion;

	public List<String> getMissingFiles()
	{
		return Safe.list(missingFiles);
	}

	public List<String> getInvalidFiles()
	{
		return Safe.list(invalidFiles);
	}

	public boolean isComplete()
	{
		return directoryFound && getMissingFiles().isEmpty() && getInvalidFiles().isEmpty();
	}

	private String versionSuffix()
	{
		return dataVersion == null ? "" : " (version " + dataVersion + ")";
	}

	public String describe()
	{
		if (!directoryFound)
		{
			return "GPS data not found at " + dir + ". Run the scraper (see docs/superpowers/plans).";
		}
		if (isComplete())
		{
			return "GPS data loaded" + versionSuffix() + ".";
		}
		StringBuilder sb = new StringBuilder("GPS data loaded").append(versionSuffix()).append(".");
		if (!getMissingFiles().isEmpty())
		{
			sb.append(" Missing: ").append(String.join(", ", getMissingFiles())).append(".");
		}
		if (!getInvalidFiles().isEmpty())
		{
			sb.append(" Invalid: ").append(String.join(", ", getInvalidFiles())).append(".");
		}
		return sb.toString();
	}
}
