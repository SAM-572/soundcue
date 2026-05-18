package com.soundcue.babycare.presentation

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.soundcue.babycare.data.AlertNotifier
import com.soundcue.babycare.data.AudioCaptureManager
import com.soundcue.babycare.data.AudioFeatureExtractor
import com.soundcue.babycare.data.BabySoundPipeline
import com.soundcue.babycare.data.ConfidenceGate
import com.soundcue.babycare.data.EventLogEntry
import com.soundcue.babycare.data.EventRepository
import com.soundcue.babycare.data.FakeLocalProvider
import com.soundcue.babycare.data.GemmaLiteRtProvider
import com.soundcue.babycare.data.GestureEventBus
import com.soundcue.babycare.data.ManualEventBus
import com.soundcue.babycare.data.TtsEngine
import com.soundcue.babycare.data.UserProfileStore
import com.soundcue.babycare.data.VadPhase
import com.soundcue.babycare.data.WatchBridge
import com.soundcue.babycare.data.YamNetClassifier
import com.soundcue.babycare.domain.BabySoundLabel
import com.soundcue.babycare.domain.CareEventResult
import com.soundcue.babycare.domain.EventSeverity
import com.soundcue.babycare.domain.NonSpeechCandidate
import com.soundcue.babycare.domain.ToolAction
import com.soundcue.babycare.domain.Urgency
import com.soundcue.babycare.domain.WordingMode
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.soundcue.babycare.domain.AlertPayload
import com.soundcue.babycare.domain.BabyEventType
import com.soundcue.babycare.domain.OutputLang
import com.soundcue.babycare.domain.SoundEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

data class HistoryItem(
    val payload: AlertPayload,
    val timestamp: Long
) {
    val secondsAgo: Long
        get() = (System.currentTimeMillis() - timestamp) / 1000L
}

data class AiRuntimeState(
    val isInitializing: Boolean = true,
    val isReady: Boolean = false,
    val providerName: String = "Gemma 4 E2B (초기화 중)",
    val backend: String = "-",
    val modelPath: String = "-",
    val lastLatencyMs: Long? = null,
    val lastError: String? = null
)

data class ListenState(
    val listening: Boolean = false,
    val rms: Float = 0f,
    val lastLabel: String? = null,
    val lastConfidence: Float = 0f,
    val error: String? = null,
    val analyzing: Boolean = false,
    val phase: VadPhase = VadPhase.WAITING,
    // 투명성: Gemma가 실제로 뱉은 원문 응답 (디버깅·데모 신뢰도 근거 표시용)
    val lastRawResponse: String? = null,
    val lastClipDurationMs: Long = 0L,
    val lastInferenceMs: Long = 0L,
    // 비음성 care reasoning 결과
    val lastHeard: String? = null,
    val lastTopCandidates: List<NonSpeechCandidate> = emptyList(),
    val lastUrgency: Urgency = Urgency.LOW,
    val lastCareHint: String? = null,
    val lastWordingMode: WordingMode = WordingMode.ABSTAIN
)

data class GestureSpeakState(
    val lastGesture: String? = null,
    val lastSpoken: String? = null,
    val timestamp: Long = 0L,
    // Gemma 가 최근 이벤트 기반으로 추천하는 제스처 순서
    val suggestedOrder: List<String> = emptyList()
)

data class DashboardState(
    val currentAlert: AlertPayload? = null,
    val currentTimestamp: Long = 0L,
    val history: List<HistoryItem> = emptyList(),
    val ai: AiRuntimeState = AiRuntimeState(),
    val outputLang: OutputLang = OutputLang.KO,
    val listen: ListenState = ListenState(),
    val gesture: GestureSpeakState = GestureSpeakState()
) {
    val secondsSinceLatest: Long
        get() = if (currentTimestamp == 0L) 0L
        else ((System.currentTimeMillis() - currentTimestamp) / 1000L).coerceAtLeast(0L)
}

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val judgeMode: Boolean = true

    private val modelPath = GemmaLiteRtProvider.defaultModelPath(application)
    private val gemma = GemmaLiteRtProvider(
        context = application,
        modelPath = modelPath,
        judgeMode = judgeMode,
        fallback = if (judgeMode) null else FakeLocalProvider()
    )

    // Gemma 엔진을 직렬화하는 mutex. 버튼·오디오 어느 쪽도 동시 호출 불가.
    // 그러나 코루틴 내부에서 suspend 대기하므로 UI 버튼 탭 자체는 즉시 응답.
    private val gemmaMutex = Mutex()
    private var audioCapture: AudioCaptureManager? = null

    // YAMNet ML 분류기 + 파이프라인
    private val yamnet = YamNetClassifier(application)
    private var soundPipeline: BabySoundPipeline? = null

    // 오디오 이벤트 쿨다운: 같은 이벤트 반복 알림 방지
    private var lastAudioPublishedAt: Long = 0L
    private val audioCooldownMs: Long = 4000L

    // TTS (제스처 → Gemma 문장 → 폰 스피커)
    private val tts: TtsEngine = TtsEngine(application)
    private var lastGestureSpokenAt: Long = 0L
    private val gestureCooldownMs: Long = 2500L

    // 사용자 프로필 + 이벤트 리포지토리
    val profileStore = UserProfileStore(application)
    val eventRepo = EventRepository(application)

    private val _state = MutableStateFlow(
        DashboardState(
            ai = AiRuntimeState(
                isInitializing = true,
                providerName = "Gemma 4 E2B (초기화 중)",
                modelPath = modelPath
            )
        )
    )
    val state: StateFlow<DashboardState> = _state.asStateFlow()

    init {
        AlertNotifier.ensureChannel(application)

        viewModelScope.launch {
            while (true) {
                delay(1000)
                _state.value = _state.value.copy()
            }
        }

        viewModelScope.launch {
            val result = gemma.initialize()
            _state.value = _state.value.copy(
                ai = AiRuntimeState(
                    isInitializing = false,
                    isReady = result.success,
                    providerName = if (result.success) "Gemma 4 E2B · ${result.backend}"
                    else "Gemma 4 (로드 실패)",
                    backend = result.backend,
                    modelPath = result.modelPath,
                    lastError = result.error
                )
            )

            // YAMNet 초기화 + 파이프라인 구성
            val yamnetOk = withContext(Dispatchers.IO) { yamnet.initialize() }
            if (yamnetOk) {
                soundPipeline = BabySoundPipeline(yamnet, gemma)
                android.util.Log.i("MainViewModel", "BabySoundPipeline ready (YAMNet + Gemma)")
            } else {
                android.util.Log.w("MainViewModel", "YAMNet init failed - ML classification unavailable")
            }
        }

        // TTS 초기화 + 프로필 변경 감지해서 성별 반영
        viewModelScope.launch {
            tts.init(_state.value.outputLang)
        }
        viewModelScope.launch {
            profileStore.profile.collect { p ->
                tts.applyVoiceForParent(p.parentTitle)
            }
        }

        // 워치 제스처 수신 → Gemma 문장 → TTS 재생
        viewModelScope.launch {
            GestureEventBus.events.collect { ev ->
                onGesture(ev.gesture)
            }
        }

        // 워치 수동 기록 수신 (수유·기저귀·수면)
        viewModelScope.launch {
            ManualEventBus.events.collect { event ->
                eventRepo.logManual(event)
            }
        }
    }

    private suspend fun onGesture(gestureLabel: String) {
        val now = System.currentTimeMillis()
        if (now - lastGestureSpokenAt < gestureCooldownMs) return
        lastGestureSpokenAt = now

        val profile = profileStore.profile.value
        val lang = _state.value.outputLang
        val langCode = if (lang == OutputLang.EN) "en" else "ko"

        val phrase = if (_state.value.ai.isReady) {
            gemmaMutex.withLock {
                withContext(Dispatchers.IO) {
                    gemma.generateGesturePhrase(
                        gestureLabel = gestureLabel,
                        lang = lang,
                        recentHistory = _state.value.history.take(3).map { h ->
                            h.payload.subtype ?: h.payload.watchText
                        },
                        profileContext = profile.toPromptContext(langCode)
                    )
                }
            }
        } else {
            val name = profile.babyName.ifBlank { if (lang == OutputLang.EN) "baby" else "아기" }
            val parent = profile.parentTitle
            when (lang) {
                OutputLang.EN -> "$parent is here, $name."
                else -> "${name}아, $parent 여기 있어."
            }
        }

        // Gemma 문장을 워치로 전송. 성공하면 워치에서 재생, 실패(미연결)하면 폰 재생.
        viewModelScope.launch {
            val sent = WatchBridge.sendSpeakText(getApplication(), phrase)
            if (sent == 0) {
                // 워치 미연결 → 폰 스피커 폴백
                tts.setLanguage(lang)
                if (profile.isNightMode) {
                    tts.speakWithTone(phrase, pitch = 0.8f, speed = 0.85f)
                } else {
                    tts.speak(phrase)
                }
            }
        }

        // 이벤트 로그
        eventRepo.log(EventLogEntry(
            event = "gesture_$gestureLabel",
            source = "gesture"
        ))

        _state.value = _state.value.copy(
            gesture = GestureSpeakState(
                lastGesture = gestureLabel,
                lastSpoken = phrase,
                timestamp = now
            )
        )
    }

    fun simulate(type: BabyEventType) {
        if (!_state.value.ai.isReady) {
            _state.value = _state.value.copy(
                ai = _state.value.ai.copy(lastError = "Gemma 4가 준비되지 않았습니다.")
            )
            return
        }
        viewModelScope.launch {
            gemmaMutex.withLock {
                val event = SoundEvent(
                    id = UUID.randomUUID().toString(),
                    type = type,
                    confidence = 0.88f,
                    timestamp = SystemClock.elapsedRealtime()
                )
                val recent = _state.value.history.take(5).map { h ->
                    val sec = (System.currentTimeMillis() - h.timestamp) / 1000
                    val src = h.payload.subtype ?: h.payload.watchText
                    if (_state.value.outputLang == OutputLang.EN) "$src (${sec}s ago)"
                    else "$src (${sec}초 전)"
                }
                val result = withContext(Dispatchers.IO) {
                    gemma.summarizeWithMetrics(event, _state.value.outputLang, recent)
                }
                publishResult(result.payload, result.latencyMs)
            }
        }
    }

    // 마이크 청취 → VAD 로 소리 시작~종료 구간만 잘라 Gemma 에 전달
    fun startListening(): Boolean {
        if (!_state.value.ai.isReady) {
            _state.value = _state.value.copy(
                listen = _state.value.listen.copy(error = "Gemma 4 미준비")
            )
            return false
        }

        val capture = audioCapture ?: AudioCaptureManager(
            scope = viewModelScope
        ).also { mgr ->
            audioCapture = mgr
            viewModelScope.launch {
                mgr.isListening.collect { listening ->
                    _state.value = _state.value.copy(
                        listen = _state.value.listen.copy(listening = listening)
                    )
                }
            }
            viewModelScope.launch {
                mgr.rms.collect { rms ->
                    _state.value = _state.value.copy(
                        listen = _state.value.listen.copy(rms = rms)
                    )
                }
            }
            viewModelScope.launch {
                mgr.phase.collect { phase ->
                    _state.value = _state.value.copy(
                        listen = _state.value.listen.copy(
                            phase = phase,
                            analyzing = phase == VadPhase.ANALYZING
                        )
                    )
                }
            }
            viewModelScope.launch {
                mgr.events.collect { wavBytes -> onVadEvent(wavBytes) }
            }
        }

        val ok = capture.start()
        _state.value = _state.value.copy(
            listen = _state.value.listen.copy(
                error = if (!ok) "마이크 시작 실패 (권한·하드웨어 확인)" else null
            )
        )
        return ok
    }

    fun stopListening() {
        audioCapture?.stop()
    }

    /**
     * VAD 가 한 이벤트 캡처를 완료할 때마다 호출됨.
     *
     * 새 파이프라인:
     *   WAV → YAMNet ML 분류 (cry/laugh/crash/babble)
     *     → babble이면 Gemma Audio로 mama/baba 세분화
     *     → TemporalSmoother (투표)
     *     → ConfidenceGate
     *     → Gemma Text로 케어 메시지 생성
     *     → UI / 워치 알림
     */
    private suspend fun onVadEvent(wavBytes: ByteArray) {
        val pipeline = soundPipeline

        // ML 파이프라인 미준비 시 레거시 경로 사용
        if (pipeline == null || !pipeline.isReady) {
            onVadEventLegacy(wavBytes)
            return
        }

        gemmaMutex.withLock {
            try {
                // 1) YAMNet ML 분류 (+ Gemma babble 세분화)
                val classifyResult = withContext(Dispatchers.IO) {
                    pipeline.classify(wavBytes, tryGemmaBabble = _state.value.ai.isReady)
                }

                val primary = classifyResult.primary

                // Silence는 무시
                if (primary.label == BabySoundLabel.SILENCE ||
                    primary.label == BabySoundLabel.OTHER) {
                    _state.value = _state.value.copy(
                        listen = _state.value.listen.copy(
                            lastLabel = primary.label.tag,
                            lastConfidence = primary.confidence,
                            lastClipDurationMs = classifyResult.clipDurationMs,
                            lastInferenceMs = classifyResult.inferenceTimeMs,
                            lastHeard = null,
                            lastCareHint = null,
                            lastWordingMode = WordingMode.ABSTAIN
                        )
                    )
                    return
                }

                // 2) Gemma Text로 케어 메시지 생성
                val prior = _state.value.history.take(3).map { h ->
                    val sec = (System.currentTimeMillis() - h.timestamp) / 1000
                    val src = h.payload.subtype ?: h.payload.watchText
                    if (_state.value.outputLang == OutputLang.EN) "$src (${sec}s ago)"
                    else "$src (${sec}초 전)"
                }
                val result = withContext(Dispatchers.IO) {
                    gemma.generateCareMessage(classifyResult, _state.value.outputLang, prior)
                }
                val gate = ConfidenceGate.decide(result)

                // UI 상태 업데이트
                _state.value = _state.value.copy(
                    listen = _state.value.listen.copy(
                        lastRawResponse = result.rawJson.ifBlank { null },
                        lastClipDurationMs = classifyResult.clipDurationMs,
                        lastInferenceMs = classifyResult.inferenceTimeMs,
                        lastHeard = result.heard,
                        lastTopCandidates = result.topCandidates,
                        lastUrgency = result.urgency,
                        lastCareHint = result.careHint,
                        lastWordingMode = gate.wordingMode,
                        lastLabel = gate.finalEvent,
                        lastConfidence = gate.confidence
                    )
                )

                if (gate.wordingMode == WordingMode.ABSTAIN ||
                    gate.wordingMode == WordingMode.RELISTEN) {
                    return
                }

                // 쿨다운
                val now = System.currentTimeMillis()
                if (now - lastAudioPublishedAt < audioCooldownMs) return
                lastAudioPublishedAt = now

                val payload = buildPayloadFromCareResult(result, gate)
                publishResult(payload, classifyResult.inferenceTimeMs, notifyWatch = gate.shouldNotifyWatch)
            } finally {
                audioCapture?.onAnalyzeDone()
            }
        }
    }

    /**
     * YAMNet 미준비 시 레거시 경로 (규칙 기반 classifyByAcoustics + Gemma text).
     * ML 모델 로드 실패 시에만 사용되는 fallback.
     */
    private suspend fun onVadEventLegacy(wavBytes: ByteArray) {
        val clipDurationMs: Long = estimateWavDurationMs(wavBytes)
        val features = AudioFeatureExtractor.extract(wavBytes)

        gemmaMutex.withLock {
            try {
                val prior = _state.value.history.take(3).map { h ->
                    val sec = (System.currentTimeMillis() - h.timestamp) / 1000
                    val src = h.payload.subtype ?: h.payload.watchText
                    if (_state.value.outputLang == OutputLang.EN) "$src (${sec}s ago)"
                    else "$src (${sec}초 전)"
                }
                val (result, latency) = withContext(Dispatchers.IO) {
                    gemma.reasonNonSpeechCare(
                        wavBytes = wavBytes,
                        features = features,
                        lang = _state.value.outputLang,
                        priorEvents = prior
                    )
                }
                val gate = ConfidenceGate.decide(result)

                _state.value = _state.value.copy(
                    listen = _state.value.listen.copy(
                        lastRawResponse = result.rawJson.ifBlank { null },
                        lastClipDurationMs = clipDurationMs,
                        lastInferenceMs = latency,
                        lastHeard = result.heard,
                        lastTopCandidates = result.topCandidates,
                        lastUrgency = result.urgency,
                        lastCareHint = result.careHint,
                        lastWordingMode = gate.wordingMode,
                        lastLabel = gate.finalEvent,
                        lastConfidence = gate.confidence
                    )
                )

                if (gate.wordingMode == WordingMode.ABSTAIN ||
                    gate.wordingMode == WordingMode.RELISTEN) {
                    return
                }

                val now = System.currentTimeMillis()
                if (now - lastAudioPublishedAt < audioCooldownMs) return
                lastAudioPublishedAt = now

                val payload = buildPayloadFromCareResult(result, gate)
                publishResult(payload, latency, notifyWatch = gate.shouldNotifyWatch)
            } finally {
                audioCapture?.onAnalyzeDone()
            }
        }
    }

    private fun buildPayloadFromCareResult(
        result: CareEventResult,
        gate: com.soundcue.babycare.domain.ConfidenceDecision
    ): AlertPayload {
        val severity = when (gate.urgency) {
            Urgency.HIGH -> EventSeverity.HIGH
            Urgency.MEDIUM -> EventSeverity.MEDIUM
            Urgency.LOW -> EventSeverity.LOW
        }
        val hedgedTitle = if (gate.wordingMode == WordingMode.CAUTIOUS)
            (if (_state.value.outputLang == OutputLang.EN) "Possible ${result.selectedEvent}"
             else "${result.selectedEvent} 가능성")
        else result.selectedEvent

        return AlertPayload(
            title = hedgedTitle,
            body = result.careHint,
            watchText = shortWatchText(result.selectedEvent, _state.value.outputLang),
            severity = severity,
            inferenceSource = "Gemma 4 E2B · care-reasoning",
            subtype = result.topCandidates.firstOrNull()?.event,
            reasoning = result.heard,
            suggestion = result.careHint
        )
    }

    private fun shortWatchText(event: String, lang: OutputLang): String {
        if (lang == OutputLang.EN) return when (event.lowercase()) {
            "baby_cry_general", "baby_cry_discomfort", "baby_cry_hunger" -> "Baby crying"
            "baby_laugh" -> "Laugh"
            "baby_babble" -> "Babble"
            "burp" -> "Burp"
            "cough" -> "Cough"
            "hiccup" -> "Hiccup"
            "fart" -> "Fart"
            else -> event.take(14)
        }
        return when (event.lowercase()) {
            "baby_cry_general" -> "아기 울음"
            "baby_cry_discomfort" -> "불편 울음"
            "baby_cry_hunger" -> "배고픔 울음"
            "baby_laugh" -> "아기 웃음"
            "baby_babble" -> "옹알이"
            "burp" -> "트림"
            "cough" -> "기침"
            "hiccup" -> "딸꾹질"
            "fart" -> "방귀"
            else -> event.take(6)
        }
    }

    private fun estimateWavDurationMs(wav: ByteArray): Long {
        // WAV 헤더 44바이트 이후 PCM. 16kHz 16-bit mono.
        val pcmBytes = (wav.size - 44).coerceAtLeast(0)
        val samples = pcmBytes / 2
        return samples * 1000L / 16000L
    }

    private fun publishResult(
        payload: AlertPayload,
        latencyMs: Long,
        notifyWatch: Boolean = true
    ) {
        val now = System.currentTimeMillis()
        val newHistory = (listOf(HistoryItem(payload, now)) + _state.value.history).take(20)
        _state.value = _state.value.copy(
            currentAlert = payload,
            currentTimestamp = now,
            history = newHistory,
            ai = _state.value.ai.copy(
                lastLatencyMs = latencyMs,
                lastError = null
            )
        )

        // 이벤트 리포트 자동 기록
        val eventTag = payload.subtype
            ?: payload.watchText.lowercase().replace(" ", "_")
        eventRepo.log(EventLogEntry(
            event = eventTag,
            subtype = payload.subtype,
            source = "auto"
        ))

        // Safety 에스컬레이션: 연속 울음 3회 이상
        checkSafetyEscalation()

        // Gemma 기반 능동 제안: 최근 이벤트 보고 다음 행동 추천 순서 생성
        viewModelScope.launch { updateSuggestedOrder() }

        if (payload.inferenceSource != "ERROR" && notifyWatch) {
            AlertNotifier.notify(getApplication(), payload)
            viewModelScope.launch {
                WatchBridge.send(
                    context = getApplication(),
                    payload = payload,
                    subtype = payload.subtype,
                    reasoning = payload.reasoning
                )
            }
        }
    }

    private fun checkSafetyEscalation() {
        val recentCries = _state.value.history.take(5).count {
            it.payload.subtype?.contains("cry") == true ||
                it.payload.watchText.contains("울음")
        }
        if (recentCries >= 3) {
            val profile = profileStore.profile.value
            val lang = _state.value.outputLang
            val name = profile.babyName.ifBlank {
                if (lang == OutputLang.EN) "baby" else "아기"
            }
            val safetyPayload = AlertPayload(
                title = if (lang == OutputLang.EN) "Check on $name"
                else "${name} 상태 확인이 필요해요",
                body = if (lang == OutputLang.EN)
                    "$name has been crying repeatedly. Check if something is wrong — temperature, diaper, pain."
                else "${name}가 계속 울고 있어요. 체온이나 기저귀, 아픈 곳이 없는지 확인해 주세요.",
                watchText = if (lang == OutputLang.EN) "Check $name" else "확인 필요",
                severity = EventSeverity.HIGH,
                inferenceSource = "safety_escalation",
                subtype = "safety_check",
                suggestion = if (lang == OutputLang.EN)
                    "Check temperature, diaper, and signs of pain."
                else "체온, 기저귀, 아픔 징후를 확인하세요."
            )
            AlertNotifier.notify(getApplication(), safetyPayload)
            viewModelScope.launch {
                WatchBridge.send(
                    getApplication(), safetyPayload,
                    subtype = "safety_check",
                    reasoning = "Repeated crying detected"
                )
            }
        }
    }

    fun logManual(event: String) {
        eventRepo.logManual(event)
    }

    /**
     * Gemma가 최근 이벤트를 보고 "지금 부모가 하면 좋을 제스처" 우선순위를 제안.
     * 예: 아기가 울었다면 → call_name > hurt > hungry > mom_here 순
     *     아기가 웃었다면 → well_done > call_name > love 순
     * Gemma 미준비 시 규칙 기반 fallback.
     */
    private suspend fun updateSuggestedOrder() {
        val recentEvents = _state.value.history.take(3).map {
            it.payload.subtype ?: it.payload.watchText
        }
        val allLabels = listOf(
            "call_name", "name_here", "hungry", "awake", "hurt",
            "well_done", "love", "mom_here", "dad_here", "wait"
        )

        if (!_state.value.ai.isReady || recentEvents.isEmpty()) {
            // 이벤트 없으면 기본 순서 유지
            _state.value = _state.value.copy(
                gesture = _state.value.gesture.copy(suggestedOrder = allLabels)
            )
            return
        }

        // Gemma에 추천 순서 요청 (가벼운 텍스트 추론)
        val order = gemmaMutex.withLock {
            withContext(Dispatchers.IO) {
                runCatching {
                    val profile = profileStore.profile.value
                    val prompt = buildSuggestionPrompt(recentEvents, profile, allLabels)
                    val buf = StringBuilder()
                    gemma.engine?.createConversation()?.use { c ->
                        c.sendMessageAsync(prompt)
                            .catch { /* ignore */ }
                            .onEach { chunk ->
                                val text = chunk?.toString() ?: ""
                                buf.append(text)
                            }
                            .collect()
                    }
                    parseSuggestedOrder(buf.toString(), allLabels)
                }.getOrElse { fallbackOrder(recentEvents, allLabels) }
            }
        }

        _state.value = _state.value.copy(
            gesture = _state.value.gesture.copy(suggestedOrder = order)
        )
    }

    private fun buildSuggestionPrompt(
        recent: List<String>,
        profile: com.soundcue.babycare.data.Profile,
        labels: List<String>
    ): String {
        val events = recent.joinToString(", ")
        return """
            Recent baby events: $events
            Parent: ${profile.parentTitle}, Baby: ${profile.babyName.ifBlank { "baby" }}
            Available gesture labels: ${labels.joinToString(",")}

            Based on the recent events, rank these gesture labels from most appropriate to least.
            Output ONLY a comma-separated list of labels, nothing else.
            Example: call_name,hurt,hungry,mom_here,love,well_done,awake,name_here,dad_here,wait
        """.trimIndent()
    }

    private fun parseSuggestedOrder(raw: String, allLabels: List<String>): List<String> {
        val cleaned = raw.trim().lines().last().trim()
        val parsed = cleaned.split(",").map { it.trim() }.filter { it in allLabels }
        // 파싱된 것 + 빠진 라벨 보충
        val missing = allLabels.filter { it !in parsed }
        return (parsed + missing).distinct()
    }

    private fun fallbackOrder(recent: List<String>, allLabels: List<String>): List<String> {
        val joined = recent.joinToString(" ").lowercase()
        return when {
            "울음" in joined || "cry" in joined ->
                listOf("call_name", "name_here", "hurt", "hungry", "mom_here", "dad_here", "love", "wait", "awake", "well_done")
            "웃음" in joined || "laugh" in joined ->
                listOf("well_done", "call_name", "love", "name_here", "hungry", "awake", "mom_here", "dad_here", "hurt", "wait")
            "트림" in joined || "burp" in joined ->
                listOf("well_done", "call_name", "love", "hungry", "name_here", "awake", "mom_here", "dad_here", "hurt", "wait")
            "첫 말" in joined || "first" in joined ->
                listOf("call_name", "love", "well_done", "name_here", "mom_here", "dad_here", "hungry", "awake", "hurt", "wait")
            else -> allLabels
        }.let { order ->
            val missing = allLabels.filter { it !in order }
            (order + missing).distinct()
        }
    }

    // GemmaLiteRtProvider 내부 engine 접근용 (suggestedOrder 전용)
    private val com.soundcue.babycare.data.GemmaLiteRtProvider.engine
        get() = runCatching {
            val f = this::class.java.getDeclaredField("engine")
            f.isAccessible = true
            f.get(this)
        }.getOrNull() as? com.google.ai.edge.litertlm.Engine

    fun toggleLanguage() {
        val current = _state.value.outputLang
        _state.value = _state.value.copy(
            outputLang = if (current == OutputLang.KO) OutputLang.EN else OutputLang.KO
        )
    }

    fun testGesture(label: String) {
        viewModelScope.launch { onGesture(label) }
    }

    override fun onCleared() {
        super.onCleared()
        audioCapture?.stop()
        tts.shutdown()
        yamnet.close()
        gemma.close()
    }
}
