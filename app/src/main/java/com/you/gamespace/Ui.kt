package com.you.gamespace

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

object Neon {
    val Bg = Color(0xFF07070D)
    val Card = Color(0xFF12121F)
    val Cyan = Color(0xFF00E5FF)
    val Magenta = Color(0xFFFF2BD6)
    val Green = Color(0xFF39FF88)
    val Amber = Color(0xFFFFC857)
    val Red = Color(0xFFFF4D6D)
    val Text = Color(0xFFE8E8F8)
    val Muted = Color(0xFF8B8BAA)
    val Line = Color(0xFF2A2A48)
    val Gradient = Brush.horizontalGradient(listOf(Cyan, Magenta))
}

@Composable
fun NeonTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Neon.Cyan,
            onPrimary = Neon.Bg,
            secondary = Neon.Magenta,
            background = Neon.Bg,
            surface = Neon.Card,
            onBackground = Neon.Text,
            onSurface = Neon.Text
        ),
        content = content
    )
}

@Composable
fun NeonCard(
    modifier: Modifier = Modifier,
    glow: Boolean = false,
    content: @Composable ColumnScope.() -> Unit
) {
    val shape = RoundedCornerShape(18.dp)
    val borderBrush: Brush = if (glow) Neon.Gradient else SolidColor(Neon.Line)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Neon.Card)
            .border(1.dp, borderBrush, shape)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        content = content
    )
}

@Composable
fun SectionTitle(text: String) {
    Text(
        text.uppercase(),
        color = Neon.Cyan,
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 2.sp
    )
}

@Composable
fun NeonButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    filled: Boolean = false
) {
    val shape = RoundedCornerShape(14.dp)
    var m = modifier
    if (filled) m = m.shadow(12.dp, shape, ambientColor = Neon.Cyan, spotColor = Neon.Magenta)
    m = m.clip(shape)
    m = if (filled) m.background(Neon.Gradient)
    else m.background(Neon.Card).border(1.dp, Neon.Cyan.copy(alpha = 0.7f), shape)
    if (!enabled) m = m.alpha(0.4f)
    Box(
        modifier = m.clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text,
            color = if (filled) Neon.Bg else Neon.Cyan,
            fontWeight = FontWeight.Bold,
            fontSize = 14.sp,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
fun NeonChip(text: String, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(50)
    Text(
        text,
        modifier = Modifier
            .clip(shape)
            .background(if (selected) Neon.Cyan.copy(alpha = 0.15f) else Color.Transparent)
            .border(1.dp, if (selected) Neon.Cyan else Neon.Line, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        color = if (selected) Neon.Cyan else Neon.Muted,
        fontSize = 13.sp,
        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
    )
}

@Composable
fun ChipRow(options: List<Pair<String, String>>, selected: String, onSelect: (String) -> Unit) {
    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        options.forEach { (value, label) ->
            NeonChip(label, value == selected) { onSelect(value) }
        }
    }
}

@Composable
fun SwitchRow(title: String, subtitle: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = Neon.Text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Text(subtitle, color = Neon.Muted, fontSize = 11.sp)
        }
        Spacer(Modifier.width(8.dp))
        Switch(
            checked = checked,
            onCheckedChange = onChecked,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Neon.Bg,
                checkedTrackColor = Neon.Cyan,
                uncheckedThumbColor = Neon.Muted,
                uncheckedTrackColor = Neon.Bg,
                uncheckedBorderColor = Neon.Line
            )
        )
    }
}

@Composable
fun NeonSlider(value: Float, onChange: (Float) -> Unit, range: ClosedFloatingPointRange<Float>, steps: Int) {
    Slider(
        value = value,
        onValueChange = onChange,
        valueRange = range,
        steps = steps,
        colors = SliderDefaults.colors(
            thumbColor = Neon.Cyan,
            activeTrackColor = Neon.Cyan,
            inactiveTrackColor = Neon.Line
        )
    )
}

@Composable
fun StatusPill(text: String, color: Color, onClick: () -> Unit) {
    val shape = RoundedCornerShape(50)
    Row(
        modifier = Modifier
            .clip(shape)
            .border(1.dp, color.copy(alpha = 0.8f), shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(6.dp))
        Text(text, color = color, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
    }
}

@Composable
fun RowScope.NeonTab(text: String, selected: Boolean, onClick: () -> Unit) {
    Column(
        modifier = Modifier.weight(1f).clickable(onClick = onClick).padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text,
            color = if (selected) Neon.Cyan else Neon.Muted,
            fontWeight = FontWeight.Bold,
            fontSize = 12.sp,
            letterSpacing = 1.sp
        )
        Spacer(Modifier.height(6.dp))
        val line: Brush = if (selected) Neon.Gradient else SolidColor(Color.Transparent)
        Box(Modifier.height(2.dp).fillMaxWidth(0.5f).background(line))
    }
}
