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

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Base interface for any STT (Speech-to-Text) provider implementation. STT providers maintain
 * their own internal state and handle their own API/SDK initialization, authentication, and
 * configuration.
 *
 * Providers should NEVER do heavy work in the initialization phase of the object; any first-time
 * setup work should be exclusively done in [create].
 *
 * At any point in time there will only be one active STT provider.
 */
sealed interface SttProvider {
    /**
     * Unique identifier for this STT provider, following Java package name conventions.
     * Example: "org.florisboard.stt.providers.deepgram"
     */
    val providerId: String

    /**
     * Observable state of this provider's current STT session.
     */
    val sttState: StateFlow<SttState>

    /**
     * Called exactly once before any STT requests. Allows one-time setup: loading SDKs,
     * validating API keys, establishing connections, etc.
     */
    suspend fun create()

    /**
     * Called when the provider is no longer needed. Free all resources, close connections,
     * stop any active recording or transcription. After this method call finishes, this
     * provider object is considered dead and will be queued to be cleaned up by the GC.
     */
    suspend fun destroy()

    /**
     * Returns true if this provider is ready to accept STT requests.
     * Implementations should check: API keys configured, SDK initialized, permissions granted, etc.
     */
    suspend fun isAvailable(): Boolean
}

/**
 * Interface for an STT provider that operates in streaming mode -- emitting partial/interim
 * results while the user is still speaking, then a final result when done.
 *
 * Example implementations: Deepgram streaming API, Whispr real-time API.
 */
interface StreamingSttProvider : SttProvider {
    /**
     * Observable flow of STT results. Consumers collect this to receive both interim
     * (partial) and final transcription results as they arrive.
     */
    val resultFlow: StateFlow<SttResult>

    /**
     * Begin listening and streaming audio to the transcription backend.
     * Updates [sttState] to [SttState.Listening] on success, [SttState.Error] on failure.
     * Partial results are emitted via [resultFlow].
     */
    suspend fun startListening()

    /**
     * Stop listening and finalize transcription. The provider should emit a final
     * [SttResult.Final] via [resultFlow] and transition [sttState] to [SttState.Idle].
     */
    suspend fun stopListening()

    /**
     * Cancel the current session without producing a final result.
     * Transitions [sttState] to [SttState.Idle].
     */
    suspend fun cancelListening()
}

/**
 * Interface for an STT provider that operates in batch mode -- records audio first,
 * then transcribes the complete recording in one shot.
 *
 * Example implementations: OpenAI Whisper API, offline on-device models.
 */
interface BatchSttProvider : SttProvider {
    /**
     * Record audio, send it for transcription, and return the final result.
     * Updates [sttState] through [SttState.Listening] -> [SttState.Processing] -> [SttState.Idle].
     *
     * @return The transcription result (final text or error).
     */
    suspend fun recordAndTranscribe(): SttResult

    /**
     * Cancel any in-progress recording or transcription.
     * Transitions [sttState] to [SttState.Idle].
     */
    suspend fun cancel()
}

/**
 * No-op fallback provider used when no STT provider is configured.
 */
object FallbackSttProvider : BatchSttProvider {
    override val providerId = "org.florisboard.stt.providers.fallback"
    override val sttState: StateFlow<SttState> = MutableStateFlow(SttState.Idle)

    override suspend fun create() { /* no-op */ }
    override suspend fun destroy() { /* no-op */ }
    override suspend fun isAvailable(): Boolean = false
    override suspend fun recordAndTranscribe(): SttResult =
        SttResult.Error(SttError.ServiceUnavailable())
    override suspend fun cancel() { /* no-op */ }
}
