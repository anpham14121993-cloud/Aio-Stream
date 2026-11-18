package com.nuvio.tv.core.util

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

private val VIETNAMESE_LOCALE = Locale.forLanguageTag("vi-VN")

/**
 * Pins the application interface strictly to Vietnamese (`vi-VN`).
 */
fun processLocale(@Suppress("UNUSED_PARAMETER") tag: String?, @Suppress("UNUSED_PARAMETER") systemLocale: Locale): Locale =
    VIETNAMESE_LOCALE

fun Context.withAppLocale(): Context {
    val configuration = Configuration(resources.configuration)
    configuration.setLocale(VIETNAMESE_LOCALE)
    return createConfigurationContext(configuration)
}
