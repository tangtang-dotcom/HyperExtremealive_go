package com.frederic22.hyperextreme.ui

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.frederic22.hyperextreme.alive.AodBrightness
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.util.Locale
import kotlin.math.roundToInt

private val brightnessTicks = (0..20).map { it * AodBrightness.STEP }
private fun factorText(x: Float): String = String.format(Locale.ROOT, "%.1f", x)

@Composable
internal fun AodBrightnessControl(value: Float, onChange: (Float) -> Unit) {
    val level = AodBrightness.normalize(value)
    val dark = AodBrightness.aodDarkFactor(level)
    val bright = AodBrightness.aodBrightFactor(level)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween) {
            Text("息屏亮度", style = MiuixTheme.textStyles.body1)
            Text(if(level == 0f) "0 · 不修改" else "暗处 ×${factorText(dark)}  亮处 ×${factorText(bright)}",
                style = MiuixTheme.textStyles.body1, modifier = Modifier.testTag("aod-brightness-value"))
        }
        Slider(value = level, valueRange = 0f..5f, steps = 19,
            showKeyPoints = true, keyPoints = brightnessTicks, magnetThreshold = 0f,
            onValueChange = { onChange((it / AodBrightness.STEP).roundToInt() * AodBrightness.STEP) },
            modifier = Modifier.fillMaxWidth().testTag("aod-brightness-slider"))
        // 档位表：每个整数档对应的实际倍数（暗处），小档 0.25 会在上方实时数值里连续显示。
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            for(tick in 0..5) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(tick.toString(), style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    Text(if(tick == 0) "不改" else "×${factorText(AodBrightness.aodDarkFactor(tick.toFloat()))}",
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                }
            }
        }
        Text("0=不修改；越暗乘得越多：1档暗处×6…5档×26，亮处×2.4…×8；AOD→锁屏的过渡自动缓动，不爆闪。",
            style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
    }
}
