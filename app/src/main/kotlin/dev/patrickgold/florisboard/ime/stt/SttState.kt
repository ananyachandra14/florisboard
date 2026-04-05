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
 * Represents the current state of an STT session.
 */
sealed class SttState {
    /** No active STT session. */
    data object Idle : SttState()

    /** Provider is actively recording and listening to audio input. */
    data object Listening : SttState()

    /** Audio has been captured; transcription is in progress (batch mode). */
    data object Processing : SttState()

    /** An error occurred during the STT session. */
    data class Error(val error: SttError) : SttState()
}
