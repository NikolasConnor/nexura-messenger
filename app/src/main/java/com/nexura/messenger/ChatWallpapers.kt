package com.nexura.messenger

import androidx.compose.foundation.background
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import kotlin.math.cos
import kotlin.math.sin

internal fun Modifier.nexuraWallpaperPattern(theme: String): Modifier = this.drawBehind {
    val step = 29.dp.toPx()
    val line = 0.8.dp.toPx()
    val ink = when (theme) {
        "circuit" -> Color(0xFF42B8C8)
        "orbit" -> Color(0xFF8997E8)
        "forest" -> Color(0xFF75B99A)
        "ocean", "tide" -> Color(0xFF58B8C8)
        "rain" -> Color(0xFF91A8C8)
        "mono" -> Color(0xFFB0B7C2)
        "bloom" -> Color(0xFFD58BAF)
        "ember" -> Color(0xFFE18B62)
        "pixel" -> Color(0xFF83A9D6)
        else -> Color(0xFF9B83D8)
    }.copy(alpha = 0.22f)
    var row = 0
    var y = step / 2
    while (y < size.height + step) {
        var col = 0
        var x = if (row % 2 == 0) step / 2 else step
        while (x < size.width + step) {
            val p = Offset(x, y)
            when (theme) {
                "circuit" -> {
                    drawLine(ink, Offset(x-step*.22f,y), Offset(x+step*.18f,y), line)
                    drawLine(ink, Offset(x+step*.18f,y), Offset(x+step*.18f,y-step*.2f), line)
                    drawCircle(ink, step*.045f, Offset(x-step*.22f,y))
                    drawCircle(ink, step*.045f, Offset(x+step*.18f,y-step*.2f))
                }
                "orbit", "midnight" -> {
                    drawCircle(ink, step*.2f, p, style=Stroke(line))
                    drawLine(ink, Offset(x-step*.25f,y+step*.07f), Offset(x+step*.25f,y-step*.07f), line)
                    if ((row+col)%3==0) drawCircle(ink, step*.04f, Offset(x+step*.28f,y-step*.22f))
                }
                "forest" -> {
                    drawLine(ink, Offset(x,y+step*.22f), Offset(x,y-step*.2f), line)
                    drawLine(ink, Offset(x,y-step*.06f), Offset(x-step*.17f,y-step*.2f), line)
                    drawLine(ink, Offset(x,y), Offset(x+step*.18f,y-step*.18f), line)
                }
                "ocean", "tide", "rain" -> {
                    val wave = if ((row+col)%2==0) -1f else 1f
                    drawLine(ink, Offset(x-step*.23f,y+wave*step*.07f), Offset(x,y-wave*step*.07f), line)
                    drawLine(ink, Offset(x,y-wave*step*.07f), Offset(x+step*.23f,y+wave*step*.07f), line)
                    if (theme=="rain") drawLine(ink, Offset(x,y-step*.25f), Offset(x-step*.06f,y-step*.1f), line)
                }
                "bloom" -> {
                    drawCircle(ink, step*.1f, p, style=Stroke(line))
                    for (a in 0..3) {
                        val angle = a * Math.PI / 2
                        drawCircle(ink, step*.075f, Offset(x+(cos(angle)*step*.16).toFloat(),y+(sin(angle)*step*.16).toFloat()), style=Stroke(line))
                    }
                }
                "ember" -> {
                    drawLine(ink, Offset(x,y+step*.22f), Offset(x-step*.1f,y), line)
                    drawLine(ink, Offset(x-step*.1f,y), Offset(x+step*.07f,y-step*.2f), line)
                    drawLine(ink, Offset(x+step*.07f,y-step*.2f), Offset(x+step*.15f,y+step*.02f), line)
                }
                "pixel" -> {
                    val b=step*.1f
                    drawLine(ink, Offset(x-b,y-b), Offset(x+b,y-b), line)
                    drawLine(ink, Offset(x+b,y-b), Offset(x+b,y+b), line)
                    drawLine(ink, Offset(x+b,y+b), Offset(x-b,y+b), line)
                    drawLine(ink, Offset(x-b,y+b), Offset(x-b,y-b), line)
                }
                else -> {
                    drawLine(ink, Offset(x-step*.18f,y-step*.18f), Offset(x+step*.18f,y+step*.18f), line)
                    drawLine(ink, Offset(x-step*.18f,y+step*.18f), Offset(x+step*.18f,y-step*.18f), line)
                }
            }
            col++
            x += step
        }
        row++
        y += step
    }
}
