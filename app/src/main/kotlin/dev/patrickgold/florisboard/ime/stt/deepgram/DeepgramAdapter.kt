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

package dev.patrickgold.florisboard.ime.stt.deepgram

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import dev.patrickgold.florisboard.ime.stt.StreamingSttProvider
import dev.patrickgold.florisboard.ime.stt.SttError
import dev.patrickgold.florisboard.ime.stt.SttResult
import dev.patrickgold.florisboard.ime.stt.SttState
import dev.patrickgold.florisboard.lib.devtools.LogTopic
import dev.patrickgold.florisboard.lib.devtools.flogError
import dev.patrickgold.florisboard.lib.devtools.flogInfo
import dev.patrickgold.florisboard.lib.devtools.flogWarning
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.concurrent.TimeUnit

/**
 * STT adapter that records audio locally, then sends it to the Deepgram REST API
 * for transcription when recording stops.
 *
 * Flow: press voice icon -> start recording -> speak -> press again -> stop recording ->
 *       POST audio to Deepgram -> parse transcript -> commit text to editor.
 */
class DeepgramAdapter(private val apiKey: String) : StreamingSttProvider {
    private companion object {
        private const val LOG_PREFIX = "Ananya"
        private const val API_BASE_URL = "https://api.deepgram.com"
        private const val SAMPLE_RATE = 16000
    }

    override val providerId = "org.florisboard.stt.providers.deepgram"

    private val _sttState = MutableStateFlow<SttState>(SttState.Idle)
    override val sttState: StateFlow<SttState> = _sttState.asStateFlow()

    private val _resultFlow = MutableStateFlow<SttResult>(SttResult.Empty)
    override val resultFlow: StateFlow<SttResult> = _resultFlow.asStateFlow()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var httpClient: OkHttpClient? = null
    private var audioRecord: AudioRecord? = null
    private var recordingJob: Job? = null
    private val audioBuffer = ByteArrayOutputStream()

    private val json = Json { ignoreUnknownKeys = true }

    private fun ensureHttpClient() {
        if (httpClient == null) {
            httpClient = OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .build()
        }
    }

    override suspend fun create() {
        flogInfo(LogTopic.STT) { "$LOG_PREFIX Creating Deepgram HTTP client" }
        ensureHttpClient()
    }

    override suspend fun destroy() {
        flogInfo(LogTopic.STT) { "$LOG_PREFIX Destroying Deepgram adapter" }
        cancelListening()
        httpClient?.dispatcher?.executorService?.shutdown()
        httpClient = null
    }

    override suspend fun isAvailable(): Boolean {
        val available = apiKey.isNotBlank()
        flogInfo(LogTopic.STT) { "$LOG_PREFIX Deepgram availability check: available=$available clientReady=${httpClient != null}" }
        return available
    }

    @SuppressLint("MissingPermission")
    override suspend fun startListening() {
        flogInfo(LogTopic.STT) { "$LOG_PREFIX Starting Deepgram audio capture" }
        audioBuffer.reset()
        _resultFlow.value = SttResult.Empty

        val bufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (bufferSize == AudioRecord.ERROR || bufferSize == AudioRecord.ERROR_BAD_VALUE) {
            flogError(LogTopic.STT) { "$LOG_PREFIX Deepgram audio buffer size lookup failed: bufferSize=$bufferSize" }
            _sttState.value = SttState.Error(SttError.AudioError("Failed to determine audio buffer size"))
            return
        }
        flogInfo(LogTopic.STT) { "$LOG_PREFIX Deepgram audio buffer size resolved: $bufferSize bytes" }

        val recorder = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize,
            )
        } catch (e: Exception) {
            flogError(LogTopic.STT) { "$LOG_PREFIX Failed to create AudioRecord: ${e.message}" }
            _sttState.value = SttState.Error(SttError.AudioError("Failed to create AudioRecord: ${e.message}"))
            return
        }

        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            flogError(LogTopic.STT) { "$LOG_PREFIX AudioRecord failed to initialize" }
            _sttState.value = SttState.Error(SttError.AudioError("AudioRecord failed to initialize"))
            return
        }

        audioRecord = recorder
        recorder.startRecording()
        flogInfo(LogTopic.STT) { "$LOG_PREFIX Deepgram audio capture started" }
        _sttState.value = SttState.Listening

        recordingJob = scope.launch {
            val buffer = ByteArray(bufferSize)
            while (isActive && recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                val bytesRead = recorder.read(buffer, 0, buffer.size)
                if (bytesRead > 0) {
                    synchronized(audioBuffer) {
                        audioBuffer.write(buffer, 0, bytesRead)
                    }
                }
            }
        }
    }

    override suspend fun stopListening() {
        val recorder = audioRecord ?: run {
            flogWarning(LogTopic.STT) { "$LOG_PREFIX stopListening called without an active recorder" }
            _sttState.value = SttState.Idle
            return
        }

        // Stop recording
        recorder.stop()
        recordingJob?.cancel()
        recordingJob = null
        recorder.release()
        audioRecord = null

        val pcmData: ByteArray
        synchronized(audioBuffer) {
            pcmData = audioBuffer.toByteArray()
            audioBuffer.reset()
        }
        flogInfo(LogTopic.STT) { "$LOG_PREFIX Stopped Deepgram recording, captured ${pcmData.size} bytes of PCM audio" }

        if (pcmData.isEmpty()) {
            flogError(LogTopic.STT) { "$LOG_PREFIX No audio captured after releasing the voice button" }
            _resultFlow.value = SttResult.Error(
                SttError.AudioError("No audio captured after releasing the voice button")
            )
            _sttState.value = SttState.Idle
            return
        }

        // Transcribe
        _sttState.value = SttState.Processing
        val result = transcribe(pcmData)
        _resultFlow.value = result
        _sttState.value = SttState.Idle
    }

    override suspend fun cancelListening() {
        flogInfo(LogTopic.STT) { "$LOG_PREFIX Cancelling Deepgram audio capture" }
        recordingJob?.cancel()
        recordingJob = null
        audioRecord?.let { recorder ->
            if (recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                recorder.stop()
            }
            recorder.release()
        }
        audioRecord = null
        synchronized(audioBuffer) {
            audioBuffer.reset()
        }
        _sttState.value = SttState.Idle
    }

    private suspend fun transcribe(pcmData: ByteArray): SttResult = withContext(Dispatchers.IO) {
        ensureHttpClient()
        val client = httpClient ?: return@withContext SttResult.Error(
            SttError.ServiceUnavailable("HTTP client not initialized")
        )

        val wavData = encodeWav(pcmData)
        flogInfo(LogTopic.STT) {
            "$LOG_PREFIX Sending Deepgram request pcmBytes=${pcmData.size} wavBytes=${wavData.size} sampleRate=$SAMPLE_RATE"
        }

        val requestBody = wavData.toRequestBody("audio/wav".toMediaType())
        val request = Request.Builder()
            .url("$API_BASE_URL/v1/listen?model=nova-2&smart_format=true")
            .addHeader("Authorization", "Token $apiKey")
            .post(requestBody)
            .build()

        try {
            val response = client.newCall(request).execute()
            val body = response.body?.string()
            flogInfo(LogTopic.STT) {
                "$LOG_PREFIX Deepgram response received code=${response.code} bodyLength=${body?.length ?: 0}"
            }

            if (!response.isSuccessful) {
                flogError(LogTopic.STT) {
                    "$LOG_PREFIX Deepgram request failed code=${response.code} body='${body?.take(300)}'"
                }
                return@withContext when (response.code) {
                    401, 403 -> SttResult.Error(
                        SttError.AuthenticationError("Deepgram API key invalid (HTTP ${response.code})")
                    )
                    else -> SttResult.Error(
                        SttError.NetworkError("Deepgram API error: HTTP ${response.code}")
                    )
                }
            }

            if (body == null) {
                return@withContext SttResult.Error(SttError.Unknown("Empty response from Deepgram"))
            }

            val deepgramResponse = json.decodeFromString<DeepgramResponse>(body)
            val transcript = deepgramResponse.results?.channels
                ?.firstOrNull()?.alternatives
                ?.firstOrNull()?.transcript
                ?: ""
            flogInfo(LogTopic.STT) {
                "$LOG_PREFIX Deepgram transcript parsed length=${transcript.length} text='${transcript.take(120)}'"
            }

            if (transcript.isBlank()) {
                flogError(LogTopic.STT) { "$LOG_PREFIX Deepgram returned an empty final transcript" }
                SttResult.Error(SttError.Unknown("Deepgram returned an empty final transcript"))
            } else {
                SttResult.Final(transcript)
            }
        } catch (e: java.io.IOException) {
            flogError(LogTopic.STT) { "$LOG_PREFIX Deepgram network error: ${e.message}" }
            SttResult.Error(SttError.NetworkError("Network error: ${e.message}"))
        } catch (e: Exception) {
            flogError(LogTopic.STT) { "$LOG_PREFIX Deepgram transcription failure: ${e.message}" }
            SttResult.Error(SttError.Unknown("Transcription failed: ${e.message}", e))
        }
    }

    /**
     * Wraps raw PCM 16-bit mono audio data in a WAV container.
     */
    private fun encodeWav(pcmData: ByteArray): ByteArray {
        val output = ByteArrayOutputStream()
        val writer = DataOutputStream(output)

        val dataSize = pcmData.size
        val fileSize = 36 + dataSize

        // RIFF header
        writer.writeBytes("RIFF")
        writer.writeIntLE(fileSize)
        writer.writeBytes("WAVE")

        // fmt sub-chunk
        writer.writeBytes("fmt ")
        writer.writeIntLE(16)           // sub-chunk size
        writer.writeShortLE(1)          // PCM format
        writer.writeShortLE(1)          // mono
        writer.writeIntLE(SAMPLE_RATE)  // sample rate
        writer.writeIntLE(SAMPLE_RATE * 2) // byte rate (sampleRate * channels * bitsPerSample/8)
        writer.writeShortLE(2)          // block align (channels * bitsPerSample/8)
        writer.writeShortLE(16)         // bits per sample

        // data sub-chunk
        writer.writeBytes("data")
        writer.writeIntLE(dataSize)
        writer.write(pcmData)

        writer.flush()
        return output.toByteArray()
    }

    /** Write a 32-bit int in little-endian order. */
    private fun DataOutputStream.writeIntLE(value: Int) {
        write(value and 0xFF)
        write((value shr 8) and 0xFF)
        write((value shr 16) and 0xFF)
        write((value shr 24) and 0xFF)
    }

    /** Write a 16-bit short in little-endian order. */
    private fun DataOutputStream.writeShortLE(value: Int) {
        write(value and 0xFF)
        write((value shr 8) and 0xFF)
    }

}
