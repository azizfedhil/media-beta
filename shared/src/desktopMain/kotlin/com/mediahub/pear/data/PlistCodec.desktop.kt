package com.mediahub.pear.data

import com.dd.plist.NSArray
import com.dd.plist.NSData
import com.dd.plist.NSDate
import com.dd.plist.NSDictionary
import com.dd.plist.NSNumber
import com.dd.plist.NSObject
import com.dd.plist.NSString
import com.dd.plist.PropertyListParser
import java.util.Date

actual object PlistCodec {

    actual fun decode(bytes: ByteArray): Map<String, PrefValue> {
        val root = try {
            PropertyListParser.parse(bytes)
        } catch (e: Exception) {
            throw BackupException.Unreadable("Unable to parse property list: ${e.message}", e)
        }

        val dict = root as? NSDictionary ?: throw BackupException.Unreadable("Root element of property list is not a dictionary.")
        val out = mutableMapOf<String, PrefValue>()

        for (key in dict.allKeys()) {
            val obj = dict.objectForKey(key) ?: continue
            nsObjectToPrefValue(obj)?.let { out[key] = it }
        }
        return out
    }

    actual fun encode(values: Map<String, PrefValue>): ByteArray {
        val root = NSDictionary()
        for ((k, v) in values) {
            root.put(k, prefValueToNSObject(v))
        }
        return root.toXMLPropertyList().encodeToByteArray()
    }

    private fun nsObjectToPrefValue(obj: NSObject): PrefValue? {
        return when (obj) {
            is NSString -> PrefValue.Text(obj.content)
            is NSNumber -> {
                if (obj.isBoolean) {
                    PrefValue.Flag(obj.boolValue())
                } else if (obj.isInteger) {
                    PrefValue.Whole(obj.longValue())
                } else {
                    PrefValue.Real(obj.doubleValue())
                }
            }
            is NSData -> PrefValue.Blob(obj.bytes())
            is NSDate -> PrefValue.Moment(obj.date.time)
            is NSArray -> {
                val list = obj.array
                val strings = list.mapNotNull { (it as? NSString)?.content }
                if (strings.size == list.size) {
                    PrefValue.TextList(strings)
                } else null
            }
            is NSDictionary -> {
                val map = mutableMapOf<String, PrefValue>()
                for (k in obj.allKeys()) {
                    val child = obj.objectForKey(k) ?: continue
                    nsObjectToPrefValue(child)?.let { map[k] = it }
                }
                PrefValue.Dict(map)
            }
            else -> null
        }
    }

    private fun prefValueToNSObject(v: PrefValue): NSObject {
        return when (v) {
            is PrefValue.Text -> NSString(v.value)
            is PrefValue.Flag -> NSNumber(v.value)
            is PrefValue.Whole -> NSNumber(v.value)
            is PrefValue.Real -> NSNumber(v.value)
            is PrefValue.Blob -> NSData(v.toByteArray())
            is PrefValue.Moment -> NSDate(Date(v.epochMillis))
            is PrefValue.TextList -> {
                val items = v.value.map { NSString(it) }.toTypedArray()
                NSArray(*items)
            }
            is PrefValue.Dict -> {
                val dict = NSDictionary()
                for ((k, child) in v.value) {
                    dict.put(k, prefValueToNSObject(child))
                }
                dict
            }
        }
    }
}