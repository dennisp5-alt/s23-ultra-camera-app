package com.dennisp.s23lab.core

/**
 * Checks that the installed model answers what it is asked. The thinking Qwen3 0.6B file has a known
 * chat-template problem on LiteRT-LM 0.18.0 and later (the user's message renders empty), and thinking
 * output would also distort timings. Five factual prompts with one expected word each.
 */
object ModelCheck {
    data class Probe(val prompt: String, val expected: List<String>)

    val PROBES = listOf(
        Probe("What is the capital of France? Answer in one word.", listOf("paris")),
        Probe("What is 2 plus 2? Answer with a number.", listOf("4", "four")),
        Probe("Which planet is the largest in our solar system? Answer in one word.", listOf("jupiter")),
        Probe("What is the opposite of hot? Answer in one word.", listOf("cold")),
        Probe("What colour is a clear daytime sky? Answer in one word.", listOf("blue")),
    )

    data class Outcome(val prompt: String, val answer: String, val onTopic: Boolean, val thinkingSeen: Boolean, val error: String? = null)

    private val THINK = Regex("<\\s*/?\\s*think\\s*>", RegexOption.IGNORE_CASE)

    fun evaluate(probe: Probe, answer: String, channels: Map<String, String> = emptyMap(), error: String? = null): Outcome {
        val lower = answer.lowercase()
        val thinking = THINK.containsMatchIn(answer) || channels.any { (k, v) -> k.contains("think", true) && v.isNotBlank() }
        // Judge only the visible answer, with any think block removed.
        val visible = lower.replace(Regex("<think>.*?</think>", RegexOption.DOT_MATCHES_ALL), " ")
        val onTopic = error == null && probe.expected.any { word -> Regex("\\b" + Regex.escape(word) + "\\b").containsMatchIn(visible) }
        return Outcome(probe.prompt, answer.trim(), onTopic, thinking, error)
    }

    data class Verdict(val passed: Boolean, val onTopic: Int, val total: Int, val thinkingSeen: Boolean, val text: String)

    fun verdict(outcomes: List<Outcome>): Verdict {
        val onTopic = outcomes.count { it.onTopic }
        val thinking = outcomes.any { it.thinkingSeen }
        val passed = outcomes.size == PROBES.size && onTopic == outcomes.size && !thinking
        val text = when {
            outcomes.isEmpty() -> "Not checked."
            passed -> "Passed: $onTopic of ${outcomes.size} answers on topic, no thinking text."
            onTopic <= 1 -> "Failed: only $onTopic of ${outcomes.size} answers on topic. The model is not receiving the question. " +
                "This matches the thinking Qwen3 file's template problem: install the no-think file (Qwen3-0.6B-int4)."
            thinking -> "Failed: thinking text appeared. Use the no-think file so timings measure the answer only."
            else -> "Partly failed: $onTopic of ${outcomes.size} answers on topic. Check the answers below."
        }
        return Verdict(passed, onTopic, outcomes.size, thinking, text)
    }
}
