package com.tanuh.edgesdk.postprocess

import com.tanuh.edgesdk.config.OutputSpec
import kotlin.math.exp

/** One model's decoded answer. */
data class ModelVerdict(
    val model: String,
    val label: String,
    val positive: Boolean?,
    /** sigmoid probability (binary) or softmax probability of the top class. */
    val probability: Double?,
    val raw: FloatArray,
)

/** Combined answer over all models that ran. */
data class CombinedVerdict(val rule: String, val label: String, val positive: Boolean?, val detail: String)

/**
 * Output decoding, matching avni's Decoders.kt semantics:
 *  - sigmoid-binary  : p = sigmoid(logit[0]); positive if p >= threshold (threshold_inclusive) else p > threshold
 *  - softmax-argmax  : top class of softmax(logits)
 *  - raw             : pass-through
 * and the ensemble rule (avni's `combine`): unanimous-and (avni default for the 3 MViT2 folds),
 * any-or, majority, mean-probability.
 */
object Decoders {

    fun sigmoid(x: Float): Double = 1.0 / (1.0 + exp(-x.toDouble()))

    fun decode(model: String, out: FloatArray, spec: OutputSpec): ModelVerdict = when (spec.decoder) {
        "sigmoid-binary" -> {
            require(out.isNotEmpty()) { "$model produced an empty output" }
            val p = sigmoid(out[0])
            val pos = if (spec.thresholdInclusive) p >= spec.threshold else p > spec.threshold
            ModelVerdict(model, spec.labels.getOrElse(if (pos) 1 else 0) { if (pos) "Positive" else "Negative" }, pos, p, out)
        }
        "softmax-argmax" -> {
            var best = 0
            for (i in out.indices) if (out[i] > out[best]) best = i
            val max = out[best].toDouble()
            val sum = out.sumOf { exp(it - max) }
            ModelVerdict(model, spec.labels.getOrElse(best) { best.toString() }, null, 1.0 / sum, out)
        }
        else -> ModelVerdict(model, "raw", null, null, out)
    }

    /** Combines the verdicts of one pipeline's models with that pipeline's `combine` rule. */
    fun combine(verdicts: List<ModelVerdict>, spec: OutputSpec, rule: String): CombinedVerdict? {
        if (rule == "none" || verdicts.size < 2 && rule != "mean-probability") return null
        val neg = spec.labels.getOrElse(0) { "Negative" }
        val pos = spec.labels.getOrElse(1) { "Positive" }
        val flags = verdicts.mapNotNull { it.positive }
        val n = flags.count { it }
        return when (rule) {
            "unanimous-and" -> flags.all { it }.let { CombinedVerdict(rule, if (it) pos else neg, it, "$n/${flags.size} positive; all must agree") }
            "any-or" -> flags.any { it }.let { CombinedVerdict(rule, if (it) pos else neg, it, "$n/${flags.size} positive; any is enough") }
            "majority" -> (n * 2 > flags.size).let { CombinedVerdict(rule, if (it) pos else neg, it, "$n/${flags.size} positive; majority") }
            "mean-probability" -> {
                val mean = verdicts.mapNotNull { it.probability }.average()
                val p = if (spec.thresholdInclusive) mean >= spec.threshold else mean > spec.threshold
                CombinedVerdict(rule, if (p) pos else neg, p, "mean p=%.4f vs threshold %.2f".format(mean, spec.threshold))
            }
            else -> null
        }
    }
}
