package ai.drivemuse.designsystem

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

object DriveColors {
    val Carbon = Color(0xFF0B0D12); val Surface = Color(0xFF141821); val High = Color(0xFF1B2130)
    val Blue = Color(0xFF5B8CFF); val Violet = Color(0xFF7C5CFC); val Cyan = Color(0xFF27D3C2)
    val Muted = Color(0xFF9CA8BD); val White = Color(0xFFF1F4FA)
}
@Composable fun DriveMuseTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = darkColorScheme(primary = DriveColors.Blue, onPrimary = Color(0xFF071127), secondary = DriveColors.Cyan, background = DriveColors.Carbon, surface = DriveColors.Surface, onSurface = DriveColors.White, surfaceVariant = DriveColors.High, onSurfaceVariant = DriveColors.Muted), content = content)
}
@Composable fun GlassSurface(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(modifier, shape = RoundedCornerShape(28.dp), color = DriveColors.Surface) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}
@Composable fun ContextPill(text: String) {
    Text(text, color = DriveColors.Cyan, fontSize = 12.sp, fontWeight = FontWeight.Medium,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(DriveColors.Cyan.copy(alpha = .09f)).padding(horizontal = 12.dp, vertical = 8.dp))
}
@Composable fun ReasonChip(text: String) {
    Text(text, color = DriveColors.Muted, fontSize = 12.sp, modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(DriveColors.High).padding(10.dp))
}
@Composable fun AgentOrb(modifier: Modifier = Modifier, active: Boolean = true) {
    Canvas(modifier.size(42.dp)) {
        val colors = if (active) listOf(DriveColors.Blue, DriveColors.Violet, DriveColors.Cyan) else listOf(DriveColors.Muted, DriveColors.Surface)
        drawCircle(Brush.linearGradient(colors), style = Stroke(3.dp.toPx()))
        drawCircle(Brush.radialGradient(colors.map { it.copy(alpha = .4f) }), radius = size.minDimension * .37f)
    }
}
/** Procedural ambient art, no remotely loaded artwork or tracking requests. */
@Composable fun AmbientArtwork(modifier: Modifier = Modifier, night: Boolean = false) {
    Canvas(modifier.fillMaxWidth().height(210.dp).clip(RoundedCornerShape(24.dp))) {
        drawRect(Brush.verticalGradient(if (night) listOf(Color(0xFF181A44), Color(0xFF483155), Color(0xFF0D1727)) else listOf(Color(0xFF283355), Color(0xFF9D6575), Color(0xFFDF9B84))))
        drawCircle(Color(0xFFF6CDB2).copy(alpha = .9f), radius = size.width * .065f, center = Offset(size.width * .73f, size.height * .37f))
        val mountains = Path().apply { moveTo(0f,size.height*.7f); lineTo(size.width*.17f,size.height*.43f); lineTo(size.width*.38f,size.height*.68f); lineTo(size.width*.58f,size.height*.49f); lineTo(size.width*.8f,size.height*.64f); lineTo(size.width,size.height*.4f); lineTo(size.width,size.height); lineTo(0f,size.height); close() }
        drawPath(mountains, Color(0xFF263444))
        val road = Path().apply { moveTo(size.width*.49f,size.height*.66f); cubicTo(size.width*.76f,size.height*.7f,size.width*.21f,size.height*.84f,size.width*.5f,size.height); lineTo(size.width*.78f,size.height); cubicTo(size.width*.4f,size.height*.82f,size.width*.82f,size.height*.71f,size.width*.53f,size.height*.66f); close() }
        drawPath(road, Color(0xFF101722))
        val line = Path().apply { moveTo(size.width*.515f,size.height*.67f); cubicTo(size.width*.8f,size.height*.71f,size.width*.3f,size.height*.83f,size.width*.65f,size.height) }
        drawPath(line, Color(0xFFEAC9B1).copy(alpha=.65f), style=Stroke(2.dp.toPx()))
    }
}
@Composable fun DriveButton(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    Button(onClick, enabled = enabled, shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text(text, fontWeight = FontWeight.SemiBold) }
}
