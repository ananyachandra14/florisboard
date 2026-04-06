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
import dev.patrickgold.florisboard.ime.stt.deepgram.DeepgramAdapter
import dev.patrickgold.florisboard.ime.stt.deepgram.DeepgramConfig
import dev.patrickgold.florisboard.lib.devtools.LogTopic
import dev.patrickgold.florisboard.lib.devtools.flogError
import dev.patrickgold.florisboard.lib.devtools.flogInfo
import dev.patrickgold.florisboard.lib.devtools.flogWarning
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.florisboard.lib.kotlin.collectLatestIn

class SttManager(context: Context) {
    private companion object {
        private const val LOG_PREFIX = "Ananya"
    }

    private val editorInstance by context.editorInstance()

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val _activeProvider = MutableStateFlow<SttProvider?>(null)
    val activeProvider: StateFlow<SttProvider?> = _activeProvider.asStateFlow()

    private val _sttState = MutableStateFlow<SttState>(SttState.Idle)
    val sttState: StateFlow<SttState> = _sttState.asStateFlow()

    init {
        if (DeepgramConfig.API_KEY.isNotBlank()) {
            flogInfo(LogTopic.STT) { "$LOG_PREFIX Deepgram API key detected, activating Deepgram STT provider" }
            activateProvider(DeepgramAdapter(DeepgramConfig.API_KEY))
        } else {
            flogWarning(LogTopic.STT) { "$LOG_PREFIX No Deepgram API key configured, STT provider remains inactive" }
        }
    }

    private fun activateProvider(provider: SttProvider) {
        _activeProvider.value = provider
        scope.launch {
            provider.create()

            provider.sttState.collectLatestIn(scope) { state ->
                flogInfo(LogTopic.STT) { "$LOG_PREFIX Provider state changed: $state" }
                _sttState.value = state
            }

            if (provider is StreamingSttProvider) {
                provider.resultFlow.collectLatestIn(scope) { result ->
                    handleResult(result)
                }
            }
        }
    }

    /**
     * Register and activate an STT provider. Calls [SttProvider.create] and begins
     * observing its state. Only one provider is active at a time; the previous one
     * is destroyed.
     */
    fun setActiveProvider(provider: SttProvider) {
        scope.launch {
            flogInfo(LogTopic.STT) { "$LOG_PREFIX Switching active STT provider to ${provider.providerId}" }
            _activeProvider.value?.destroy()
            activateProvider(provider)
        }
    }

    /**
     * Start an STT session. Called when the voice input key is pressed.
     */
    fun startListening() {
        val provider = _activeProvider.value ?: return
        scope.launch {
            flogInfo(LogTopic.STT) { "$LOG_PREFIX Starting STT session with provider=${provider.providerId}" }
            if (!provider.isAvailable()) {
                flogWarning(LogTopic.STT) { "$LOG_PREFIX STT provider ${provider.providerId} is not available" }
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
            flogInfo(LogTopic.STT) { "$LOG_PREFIX Stopping STT session for provider=${provider.providerId}" }
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
            flogInfo(LogTopic.STT) { "$LOG_PREFIX Cancelling STT session for provider=${provider.providerId}" }
            when (provider) {
                is StreamingSttProvider -> provider.cancelListening()
                is BatchSttProvider -> provider.cancel()
            }
        }
    }

    private suspend fun handleResult(result: SttResult) {
        when (result) {
            is SttResult.Final -> {
                flogInfo(LogTopic.STT) {
                    "$LOG_PREFIX Final STT text received length=${result.text.length} text='${result.text.take(120)}'"
                }
                val committed = withContext(Dispatchers.Main.immediate) {
                    editorInstance.commitText(result.text)
                }
                if (committed) {
                    flogInfo(LogTopic.STT) { "$LOG_PREFIX Committed STT text into the active editor" }
                } else {
                    flogError(LogTopic.STT) { "$LOG_PREFIX Failed to commit STT text into the active editor" }
                }
            }
            is SttResult.Partial -> {
                flogInfo(LogTopic.STT) {
                    "$LOG_PREFIX Partial STT result length=${result.text.length} text='${result.text.take(120)}'"
                }
                // TODO: update composing text so user sees interim results
            }
            is SttResult.Error -> {
                flogError(LogTopic.STT) { "$LOG_PREFIX STT error result received: ${result.error}" }
                _sttState.value = SttState.Error(result.error)
            }
            is SttResult.Empty -> { /* no-op */ }
        }
    }

    /**
     * Clean up when the manager is no longer needed.
     */
    fun destroy() {
        scope.launch {
            flogInfo(LogTopic.STT) { "$LOG_PREFIX Destroying STT manager and active provider" }
            _activeProvider.value?.destroy()
            _activeProvider.value = null
        }
    }
}
