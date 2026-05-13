package com.poyka.ripdpi.platform

import android.content.Context
import androidx.annotation.XmlRes
import com.poyka.ripdpi.R
import org.xmlpull.v1.XmlPullParser

object LocalesConfig {
    fun parse(
        context: Context,
        @XmlRes resId: Int = R.xml.locales_config,
    ): List<String> {
        val parser = context.resources.getXml(resId)
        val tags = mutableListOf<String>()
        try {
            while (parser.eventType != XmlPullParser.END_DOCUMENT) {
                parser.readLocaleTag()?.let(tags::add)
                parser.next()
            }
        } finally {
            parser.close()
        }
        return tags
    }

    private fun XmlPullParser.readLocaleTag(): String? {
        if (eventType != XmlPullParser.START_TAG || name != "locale") {
            return null
        }
        return getAttributeValue(
            "http://schemas.android.com/apk/res/android",
            "name",
        )?.takeIf(String::isNotBlank)
    }
}
