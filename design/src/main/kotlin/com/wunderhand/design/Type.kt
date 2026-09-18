package com.wunderhand.design

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/** Archivo, bundled as four static weights cut from one release so they share metrics. */
val Archivo = FontFamily(
    Font(R.font.archivo_regular, FontWeight.Normal),
    Font(R.font.archivo_medium, FontWeight.Medium),
    Font(R.font.archivo_semibold, FontWeight.SemiBold),
    Font(R.font.archivo_bold, FontWeight.Bold),
)

/**
 * The type styles, by the role they play on a screen. Sizes are in `sp`, so
 * every one of them follows the phone's font-size setting.
 *
 * Eyebrows and field labels are uppercase in the design; the words are
 * uppercased where they are drawn ([Eyebrow]), so a screen reader still reads
 * them as words rather than spelling them out.
 */
object WHType {
    private fun archivo(size: Double, weight: FontWeight, trackingEm: Double = 0.0, lineHeightEm: Double? = null) =
        TextStyle(
            fontFamily = Archivo,
            fontWeight = weight,
            fontSize = size.sp,
            letterSpacing = trackingEm.em,
            lineHeight = (lineHeightEm ?: 1.25).em,
            lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None),
        )

    val ScreenTitle = archivo(28.0, FontWeight.Bold, trackingEm = -0.03, lineHeightEm = 1.1)
    val PageTitle = archivo(34.0, FontWeight.Bold, trackingEm = -0.03, lineHeightEm = 1.1)
    val Eyebrow = archivo(11.0, FontWeight.Bold, trackingEm = 0.16)
    val FieldLabel = archivo(11.0, FontWeight.Bold, trackingEm = 0.14)
    val RowName = archivo(16.5, FontWeight.SemiBold)
    val Meta = archivo(12.5, FontWeight.Normal)
    val Body = archivo(14.5, FontWeight.Normal, lineHeightEm = 1.55)
    val FieldValue = archivo(15.5, FontWeight.Normal)
    val Button = archivo(15.0, FontWeight.SemiBold)
    val EmptyTitle = archivo(20.0, FontWeight.SemiBold)
    val Bar = archivo(13.5, FontWeight.Normal, lineHeightEm = 1.35)
    val BarAction = archivo(13.5, FontWeight.SemiBold)
    val Link = archivo(14.0, FontWeight.Medium)

    // The diary
    val DiaryTitle = archivo(28.0, FontWeight.Bold, trackingEm = -0.03, lineHeightEm = 1.1)
    val DiaryTitleWide = archivo(32.0, FontWeight.Bold, trackingEm = -0.03, lineHeightEm = 1.1)
    val Summary = archivo(13.5, FontWeight.Normal, lineHeightEm = 1.4)
    val SummaryWide = archivo(15.0, FontWeight.Normal, lineHeightEm = 1.4)
    val CardName = archivo(15.5, FontWeight.SemiBold)
    val CardPrice = archivo(14.0, FontWeight.SemiBold)
    val CardMeta = archivo(13.0, FontWeight.Normal)
    val GutterTime = archivo(12.5, FontWeight.SemiBold)
    val Tag = archivo(11.5, FontWeight.SemiBold)
    val TagSmall = archivo(9.5, FontWeight.Bold, trackingEm = 0.06)
    val StripDay = archivo(10.5, FontWeight.Normal)
    val StripDate = archivo(16.0, FontWeight.SemiBold)
    val GridName = archivo(12.5, FontWeight.SemiBold)
    val GridMeta = archivo(11.5, FontWeight.Normal)
    val GridHour = archivo(11.0, FontWeight.Normal)
    val Tile = archivo(22.0, FontWeight.Medium)
    val WeekRow = archivo(16.0, FontWeight.Medium)
    val SheetName = archivo(28.0, FontWeight.Bold, trackingEm = -0.03, lineHeightEm = 1.1)
    val SheetTotal = archivo(18.0, FontWeight.Bold)
    val Medium14 = archivo(14.0, FontWeight.Normal, lineHeightEm = 1.45)
    val Semi14 = archivo(14.0, FontWeight.SemiBold)

    /** The wordmark: Archivo 600, lowercase, pulled tight. */
    fun wordmark(size: Double) = archivo(size, FontWeight.SemiBold, trackingEm = -0.035, lineHeightEm = 1.0)
}
