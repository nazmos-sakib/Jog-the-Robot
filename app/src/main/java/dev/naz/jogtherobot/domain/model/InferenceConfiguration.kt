package dev.naz.jogtherobot.domain.model

import dev.naz.jogtherobot.utils.DelegateOption
import dev.naz.jogtherobot.utils.PrecisionOption
import dev.naz.jogtherobot.utils.RuntimeOption

data class InferenceConfiguration(
    val runtime: RuntimeOption,
    val delegate: DelegateOption,
    val precision: PrecisionOption
)