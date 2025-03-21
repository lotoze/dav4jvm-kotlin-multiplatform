/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package at.bitfire.dav4jvm.property

import at.bitfire.dav4jvm.Property
import at.bitfire.dav4jvm.PropertyFactory
import at.bitfire.dav4jvm.XmlUtils
import nl.adaptivity.xmlutil.QName
import nl.adaptivity.xmlutil.XmlReader
import kotlin.jvm.JvmField

data class CalendarData(
    val iCalendar: String?
) : Property {

    companion object {
        @JvmField
        val NAME = QName(XmlUtils.NS_CALDAV, "calendar-data")

        // attributes
        const val CONTENT_TYPE = "content-type"
        const val VERSION = "version"
    }


    object Factory : PropertyFactory {

        override fun getName() = NAME

        override fun create(parser: XmlReader) =
            // <!ELEMENT calendar-data (#PCDATA)>
            CalendarData(XmlUtils.readText(parser))

    }

}
