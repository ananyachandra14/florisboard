/*
 * Copyright (C) 2024-2026 The FlorisBoard Contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dev.patrickgold.florisboard.ime.stt

/**
 * Represents a transcription result from an STT provider.
 */
sealed class SttResult {
    /** No result yet. Initial state for result flows. */
    data object Empty : SttResult()

    /** A partial/interim transcription (streaming mode). May change as more audio arrives. */
    data class Partial(val text: String) : SttResult()

    /** A final, committed transcription. */
    data class Final(val text: String) : SttResult()

    /** The transcription failed. */
    data class Error(val error: SttError) : SttResult()
}
