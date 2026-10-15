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
package com.chunkblazer.gps.eligibility;

import com.chunkblazer.gps.model.Missing;
import java.util.List;
import lombok.Value;

/** Whether a task or quest can be done now, and what is missing if not. */
@Value
public class Eligibility
{
	public enum Status
	{
		DOABLE, BLOCKED, UNKNOWN
	}

	Status status;
	List<Missing> missing;

	/** DOABLE if nothing is missing, UNKNOWN if every missing entry is Kind.UNKNOWN, BLOCKED otherwise. */
	public static Eligibility of(List<Missing> missing)
	{
		if (missing.isEmpty())
		{
			return new Eligibility(Status.DOABLE, missing);
		}
		boolean allUnknown = missing.stream().allMatch(m -> m.getKind() == Missing.Kind.UNKNOWN);
		return new Eligibility(allUnknown ? Status.UNKNOWN : Status.BLOCKED, missing);
	}
}
