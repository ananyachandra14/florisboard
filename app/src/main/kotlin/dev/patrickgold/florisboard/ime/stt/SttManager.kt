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

import android.content.Context
import dev.patrickgold.florisboard.editorInstance
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.florisboard.lib.kotlin.collectLatestIn

class SttManager(context: Context) {
    private val editorInstance by context.editorInstance()

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private val _activeProvider = MutableStateFlow<SttProvider?>(null)
    val activeProvider: StateFlow<SttProvider?> = _activeProvider.asStateFlow()

    private val _sttState = MutableStateFlow<SttState>(SttState.Idle)
    val sttState: StateFlow<SttState> = _sttState.asStateFlow()

    /**
     * Register and activate an STT provider. Calls [SttProvider.create] and begins
     * observing its state. Only one provider is active at a time; the previous one
     * is destroyed.
     */
    fun setActiveProvider(provider: SttProvider) {
        scope.launch {
            _activeProvider.value?.destroy()
            provider.create()
            _activeProvider.value = provider

            // Mirror the provider's state into the manager's public state flow
            provider.sttState.collectLatestIn(scope) { state ->
                _sttState.value = state
            }

            // If streaming, observe results and commit text
            if (provider is StreamingSttProvider) {
                provider.resultFlow.collectLatestIn(scope) { result ->
                    handleResult(result)
                }
            }
        }
    }

    /**
     * Start an STT session. Called when the voice input key is pressed.
     */
    fun startListening() {
        val provider = _activeProvider.value ?: return
        scope.launch {
            if (!provider.isAvailable()) {
                _sttState.value = SttState.Error(SttError.ServiceUnavailable())
                return@launch
            }
            when (provider) {
                is StreamingSttProvider -> provider.startListening()
                is BatchSttProvider -> {
                    val result = provider.recordAndTranscribe()
                    handleResult(result)
                }
            }
        }
    }

    /**
     * Stop the current STT session (user pressed the mic button again or UI stop).
     */
    fun stopListening() {
        val provider = _activeProvider.value ?: return
        scope.launch {
            when (provider) {
                is StreamingSttProvider -> provider.stopListening()
                is BatchSttProvider -> provider.cancel()
            }
        }
    }

    /**
     * Cancel the current STT session without committing any text.
     */
    fun cancelListening() {
        val provider = _activeProvider.value ?: return
        scope.launch {
            when (provider) {
                is StreamingSttProvider -> provider.cancelListening()
                is BatchSttProvider -> provider.cancel()
            }
        }
    }

    private fun handleResult(result: SttResult) {
        when (result) {
            is SttResult.Final -> editorInstance.commitText(result.text)
            is SttResult.Partial -> {
                // TODO: update composing text so user sees interim results
            }
            is SttResult.Error -> _sttState.value = SttState.Error(result.error)
            is SttResult.Empty -> { /* no-op */ }
        }
    }

    /**
     * Clean up when the manager is no longer needed.
     */
    fun destroy() {
        scope.launch {
            _activeProvider.value?.destroy()
            _activeProvider.value = null
        }
    }
}
