package com.wunderhand.design

import androidx.compose.ui.graphics.Color

/**
 * The `.desk` palette the pro side of the web ships, which is also the iOS
 * app's. Named as the design names them, so a colour in a handoff can be
 * found here by its name.
 */
object WHColors {
    val Bg = Color(0xFFF6F5F4)
    val Surface = Color.White
    val Ink = Color(0xFF302D2C)
    val Muted = Color(0xFF6B6663)
    /** The small uppercase label over a group. Darkened from 0x736D6A, which
     *  fell short of 4.5:1 on the ground — found by the iOS accessibility audit. */
    val Eyebrow = Color(0xFF6B6562)
    val Divider = Color(0xFF201E1D).copy(alpha = 0.10f)

    /** The one filled action on a screen, and a refusal's words. */
    val Accent = Color(0xFFB03A22)
    val Accent100 = Color(0xFFFBE9E4)
    val Accent300 = Color(0xFFE8B8AA)
    val Accent500 = Color(0xFFC4553A)
    val Accent800 = Color(0xFF9A321D)

    val Neutral200 = Color(0xFFECEAE7)
    val Neutral300 = Color(0xFFE4E1DE)
    /** As 0xA09B99 it was 2.5:1 on the ground; this is 4.6:1. */
    val Neutral500 = Color(0xFF736E6B)
    val Neutral700 = Color(0xFF68635F)
    val Neutral800 = Color(0xFF524E4C)

    val Block = Color(0xFFF4F1EF)
    val BlockUnpaid = Color(0xFFFBEEE9)
    val Break = Color(0xFFF0EEEC)
    val Well = Color(0xFFECEAE7)
    val InkSoft = Color(0xFFB3ACA8)
    val InkWarm = Color(0xFFE08C72)

    /** Shadows are ink, not black. */
    val Shadow = Color(0xFF201E1D)
}
