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
package com.chunkblazer.gps.ui;

import com.chunkblazer.gps.data.GpsDataStatus;
import java.nio.file.Path;

/** In-app help shown above the GPS tabs when the GPS data is missing or incomplete. Pure, so it can be tested. */
public final class GpsHelpText
{
	/** The config item name of gpsDataDir, as shown in the ChunkBlazer settings. */
	static final String SETTING_NAME = "GPS data folder";

	private GpsHelpText()
	{
	}

	/** The banner text, or an empty string when the data is complete (nothing to explain). */
	public static String forStatus(GpsDataStatus status, Path configuredDir)
	{
		if (status == null || status.isComplete())
		{
			return "";
		}
		String folder = configuredDir == null ? "(default: ~/.runelite/chunkblazer/gps-data)" : configuredDir.toString();
		return status.describe() + "\nConfigured folder: " + folder + " (change it in the ChunkBlazer settings: "
			+ SETTING_NAME + ").";
	}
}
