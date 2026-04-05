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
 * Common STT error types. Providers map their internal errors to these categories
 * so the manager and UI can handle them uniformly.
 */
sealed class SttError(open val message: String) {
    /** RECORD_AUDIO permission not granted. */
    data class PermissionDenied(
        override val message: String = "Microphone permission not granted",
    ) : SttError(message)

    /** Network unavailable or request timed out. */
    data class NetworkError(
        override val message: String = "Network error",
    ) : SttError(message)

    /** API key invalid, expired, or missing. */
    data class AuthenticationError(
        override val message: String = "Authentication failed",
    ) : SttError(message)

    /** Provider SDK or service not available or not initialized. */
    data class ServiceUnavailable(
        override val message: String = "STT service unavailable",
    ) : SttError(message)

    /** Audio input error (microphone busy, hardware failure). */
    data class AudioError(
        override val message: String = "Audio capture error",
    ) : SttError(message)

    /** Catch-all for provider-specific errors. */
    data class Unknown(
        override val message: String,
        val cause: Throwable? = null,
    ) : SttError(message)
}
