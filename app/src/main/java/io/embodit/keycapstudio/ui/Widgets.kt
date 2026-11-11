package io.embodit.keycapstudio.ui

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.util.Log
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.pico.spatial.ui.design.Button
import com.pico.spatial.ui.design.ButtonDefaults
import com.pico.spatial.ui.design.Icon
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.SegmentControl
import com.pico.spatial.ui.design.SegmentControlDefaults
import com.pico.spatial.ui.design.SegmentItem
import com.pico.spatial.ui.design.Slider
import com.pico.spatial.ui.design.Text
import io.embodit.keycapstudio.domain.Lang
import java.util.Locale

/** Resources for the language chosen in the app (provided around both windows; TabBar labels are resolved before). */
val LocalKsResources = staticCompositionLocalOf<Resources> { error("LocalKsResources not provided") }

fun localizedResources(context: Context, lang: Lang): Resources {
    val tag = when (lang) { Lang.SYSTEM -> return context.resources; Lang.ZH -> "zh-CN"; Lang.EN -> "en" }
    val c = Configuration(context.resources.configuration); c.setLocale(Locale.forLanguageTag(tag))
    return context.createConfigurationContext(c).resources
}

@Composable @ReadOnlyComposable
fun rs(@StringRes id: Int): String = LocalKsResources.current.getString(id)

@Composable @ReadOnlyComposable
fun rs(@StringRes id: Int, vararg args: Any): String = LocalKsResources.current.getString(id, *args)

/** D13 overflow log: every fixed-line Text reports `OVERFLOW <panel>/<control>` instead of hiding it. */
fun fits(name: String): (TextLayoutResult) -> Unit = { r -> if (r.hasVisualOverflow) Log.w("KeycapStudio", "OVERFLOW $name") }

val RowH = 48.dp

@Composable
fun Label(text: String, name: String, modifier: Modifier = Modifier, secondary: Boolean = false, style: androidx.compose.ui.text.TextStyle = PicoTheme.typography.bodyMedium, color: Color? = null, align: TextAlign? = null) {
    Text(text = text, modifier = modifier, style = style, color = color ?: if (secondary) PicoTheme.colorScheme.labelSecondary else PicoTheme.colorScheme.labelPrimary,
        textAlign = align ?: TextAlign.Unspecified, onTextLayout = fits(name))
}

@Composable
fun SectionTitle(text: String, name: String) = Label(text, name, Modifier.padding(top = 4.dp), style = PicoTheme.typography.titleSmall)

/** Text button ≥ 48 dp, rounded pill. [primary] uses the interaction role. */
@Composable
fun KButton(text: String, name: String, modifier: Modifier = Modifier, enabled: Boolean = true, primary: Boolean = false, @DrawableRes icon: Int? = null, onClick: () -> Unit) {
    val c = PicoTheme.colorScheme
    Button(
        onClick = onClick, modifier = modifier.heightIn(min = RowH), enabled = enabled,
        size = ButtonDefaults.Regular.copy(height = RowH, minWidth = 88.dp), shape = CircleShape,
        colors = if (primary) ButtonDefaults.buttonColors(containerColor = c.interaction, contentColor = c.labelPrimaryLight)
        else ButtonDefaults.buttonColors(containerColor = c.fillTertiary, contentColor = c.labelPrimary),
    ) {
        if (icon != null) { Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(20.dp)); Spacer(Modifier.width(6.dp)) }
        Text(text = text, maxLines = 1, softWrap = false, overflow = TextOverflow.Visible, onTextLayout = fits("button/$name"))
    }
}

/** Top-bar action: icon over a word (B19: icons carry words), 64 dp tall. */
@Composable
fun ToolButton(text: String, @DrawableRes icon: Int, name: String, modifier: Modifier = Modifier, enabled: Boolean = true, on: Boolean = false, onClick: () -> Unit) {
    val c = PicoTheme.colorScheme
    Button(
        onClick = onClick, modifier = modifier.height(64.dp), enabled = enabled,
        size = ButtonDefaults.Regular.copy(height = 64.dp, minWidth = 48.dp), shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.buttonColors(containerColor = if (on) c.interaction else c.fillTertiary, contentColor = if (on) c.labelPrimaryLight else c.labelPrimary),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(painterResource(icon), contentDescription = text, modifier = Modifier.size(22.dp))
            Text(text = text, style = PicoTheme.typography.labelMedium, maxLines = 1, softWrap = false, overflow = TextOverflow.Visible, onTextLayout = fits("toolbar/$name"))
        }
    }
}

/** Options inside one page (never page switching: that is the native TabBar, D8). */
@Composable
fun <T> Seg(items: List<Pair<T, String>>, selected: T, name: String, modifier: Modifier = Modifier.fillMaxWidth(), height: Dp = RowH, onSelect: (T) -> Unit) {
    // gate:allow tabs-segment options within one page (layout / style / material), pages switch with the native TabBar
    SegmentControl(modifier = modifier.height(height), backgroundColor = PicoTheme.colorScheme.fillTertiary) {
        items.forEach { (value, text) ->
            SegmentItem(
                selected = value == selected, onClick = { onSelect(value) },
                modifier = Modifier.weight(1f).fillMaxHeight(),
                textStyle = PicoTheme.typography.labelLarge,
                colors = SegmentControlDefaults.colors(
                    itemBackgroundColor = Color.Transparent, itemContentColor = PicoTheme.colorScheme.labelPrimary,
                    selectedItemBackgroundColor = PicoTheme.colorScheme.fillSecondary, selectedItemContentColor = PicoTheme.colorScheme.labelPrimary,
                ),
                title = { Text(text = text, maxLines = 1, softWrap = false, overflow = TextOverflow.Visible, onTextLayout = fits("seg/$name")) },
            )
        }
    }
}

/** Slider row: label, slider ≥ 100 dp wide and 48 dp tall (B19), value text. */
@Composable
fun SliderRow(label: String, value: Float, range: ClosedFloatingPointRange<Float>, valueText: String, name: String, onChange: (Float) -> Unit, onDone: () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().height(RowH), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Label(label, "slider/$name", Modifier.width(112.dp))
        Slider(value, onChange, Modifier.weight(1f).height(48.dp), range, onDone)
        Label(valueText, "slider/$name/value", Modifier.width(56.dp), align = TextAlign.End)
    }
}

/** A colour swatch, 48 dp (palette grid, used colours). */
@Composable
fun Swatch(color: Int, selected: Boolean, name: String, size: Dp = 44.dp, onClick: () -> Unit) {
    val c = PicoTheme.colorScheme
    Box(
        Modifier.size(size).clip(RoundedCornerShape(10.dp)).background(Color(color))
            .border(if (selected) 3.dp else 1.dp, if (selected) c.interaction else c.dividerLine, RoundedCornerShape(10.dp))
            .clickable { Log.i("KeycapStudio", "TAP swatch $name"); onClick() }
            .semantics { contentDescription = name; role = Role.Button; this.selected = selected },
    )
}

@Composable
fun Toggle(on: Boolean, label: String, name: String, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = RowH).clickable { onChange(!on) }, verticalAlignment = Alignment.CenterVertically) {
        Label(label, "switch/$name", Modifier.weight(1f))
        com.pico.spatial.ui.design.Switch(checked = on, onCheckedChange = onChange)
    }
}

/** Wraps a row of controls with a consistent gap. */
@Composable
fun Line(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) =
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp), content = content)
