package com.soundcue.babycare.data

import com.soundcue.babycare.domain.CareEventResult
import com.soundcue.babycare.domain.ConfidenceDecision
import com.soundcue.babycare.domain.ToolAction
import com.soundcue.babycare.domain.Urgency
import com.soundcue.babycare.domain.WordingMode

/**
 * Gemma 의 care reasoning 결과에 신뢰도 임계값·abstain 정책을 적용.
 * 비음성 인식이 공식 강점이 아니므로 거짓 확실성 대신 "확신 못 함" 을 정직하게 표현한다.
 */
object ConfidenceGate {

    // 임계값 대폭 낮춤 (Gemma 4 비음성 분류가 원래 낮은 신뢰도 반환)
    private const val STRONG: Float = 0.50f
    private const val CAUTIOUS: Float = 0.25f
    private const val RELISTEN_FLOOR: Float = 0.08f

    fun decide(result: CareEventResult): ConfidenceDecision {
        val conf = result.selectedConfidence
        val ev = result.selectedEvent.lowercase()

        val isSilence = ev == "silence"

        val wording: WordingMode = when {
            isSilence -> WordingMode.ABSTAIN
            conf < RELISTEN_FLOOR -> WordingMode.RELISTEN
            conf < CAUTIOUS -> WordingMode.CAUTIOUS  // 이전엔 ABSTAIN, 지금은 공개
            conf < STRONG -> WordingMode.CAUTIOUS
            else -> WordingMode.STRONG
        }

        val notifyWatch = when (wording) {
            WordingMode.STRONG, WordingMode.CAUTIOUS -> true  // cautious도 알림 보냄
            else -> false
        }

        val requestRelisten = wording == WordingMode.RELISTEN

        return ConfidenceDecision(
            finalEvent = if (wording == WordingMode.ABSTAIN) "silence"
            else result.selectedEvent,  // 실제 모델이 고른 라벨 존중
            confidence = conf,
            wordingMode = wording,
            shouldNotifyWatch = notifyWatch,
            shouldRequestRelisten = requestRelisten,
            urgency = result.urgency
        )
    }
}
