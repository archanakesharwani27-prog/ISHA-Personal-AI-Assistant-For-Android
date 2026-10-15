package com.aura.assistant.ai

import android.content.Context
import android.net.Uri
import android.provider.ContactsContract
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Caller Identification & Spam Detection Engine for ISHA.
 *
 * Multi-Tier Pipeline:
 * 1. TRAI Regulatory Prefix Recognition (140 = Telemarketer, 160 = Transactional).
 * 2. Device Address Book & ISHA Contact Memory Check (0ms instant).
 * 3. Indian Telecom Circle & Operator Inference from National Numbering Plan (0ms).
 * 4. Live Reverse Web Search via DuckDuckGo Lite for Spam / Fraud reports & Business listings.
 * 5. In-Memory Concurrent Cache for zero-latency repeated lookups.
 */
object IshaCallerLookupEngine {

    private const val TAG = "IshaCallerLookup"

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(4, TimeUnit.SECONDS)
            .callTimeout(5, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    // In-memory cache for fast 0ms retrieval on repeated rings or queries
    private val lookupCache = ConcurrentHashMap<String, CallerIdentityResult>()

    data class CallerIdentityResult(
        val rawNumber: String,
        val cleanDigits: String,
        val formattedDisplay: String,
        val source: String, // "contacts", "memory", "trai_regulation", "web_search", "carrier_inference", "error"
        val displayName: String?,
        val isSpam: Boolean,
        val spamCategory: String? = null, // "Telemarketing", "Suspected Scam / Fraud", "Robocall", "Harassment"
        val spamRisk: String = "None", // "High", "Moderate", "Low", "None"
        val businessName: String? = null, // e.g. "Amazon Delivery", "HDFC Bank", "Zomato"
        val operatorAndCircle: String? = null, // e.g. "Airtel • Delhi NCR"
        val details: String,
        val recommendation: String
    )

    /**
     * Get already cached caller identity if available (instant 0ms, non-blocking).
     */
    fun getCached(number: String): CallerIdentityResult? {
        val clean = cleanPhoneNumber(number)
        return lookupCache[clean]
    }

    /**
     * Main entry point to identify any incoming or user-queried phone number.
     * Safe to call from coroutines or background threads.
     */
    suspend fun identifyCaller(context: Context, rawNumber: String): CallerIdentityResult = withContext(Dispatchers.IO) {
        val clean = cleanPhoneNumber(rawNumber)
        if (clean.isBlank()) {
            return@withContext CallerIdentityResult(
                rawNumber = rawNumber,
                cleanDigits = "",
                formattedDisplay = "Unknown",
                source = "error",
                displayName = null,
                isSpam = false,
                details = "No phone number provided.",
                recommendation = "Provide a valid phone number."
            )
        }

        // 1. Check in-memory cache
        lookupCache[clean]?.let { return@withContext it }

        // 2. Check Device Contacts
        val localContact = lookupLocalContact(context, clean)
        if (localContact != null) {
            val result = CallerIdentityResult(
                rawNumber = rawNumber,
                cleanDigits = clean,
                formattedDisplay = formatNumberDisplay(clean),
                source = "contacts",
                displayName = localContact,
                isSpam = false,
                spamRisk = "None",
                details = "Saved in your phone contacts as '$localContact'.",
                recommendation = "Safe to answer — known contact."
            )
            lookupCache[clean] = result
            return@withContext result
        }

        // 3. Check ISHA Contact Memory (contacts taught to Isha in chat)
        val memoryMatch = IshaContactMemoryManager.resolveIncomingNumber(context, clean)
        if (memoryMatch != null) {
            val label = if (memoryMatch.isOldNumber) "${memoryMatch.name} (Purana Number)" else memoryMatch.name
            val result = CallerIdentityResult(
                rawNumber = rawNumber,
                cleanDigits = clean,
                formattedDisplay = formatNumberDisplay(clean),
                source = "memory",
                displayName = label,
                isSpam = false,
                spamRisk = "None",
                details = "Recognized from ISHA Memory as '$label'.",
                recommendation = "Safe to answer — previously identified by you."
            )
            lookupCache[clean] = result
            return@withContext result
        }

        // 4. Check TRAI Regulatory Prefixes (India Specific)
        val traiResult = checkTraiPrefix(clean)
        if (traiResult != null) {
            lookupCache[clean] = traiResult
            return@withContext traiResult
        }

        // 5. Telecom Circle & Operator static inference (India NNP)
        val carrierCircle = inferCarrierAndCircle(clean)

        // 6. Real-time Live Web Search & Spam Directory Lookup
        val webResult = performOnlineSpamAndBusinessSearch(clean, carrierCircle)
        lookupCache[clean] = webResult
        return@withContext webResult
    }

    /**
     * Cleans phone number to last 10 digits or normalized E.164.
     */
    fun cleanPhoneNumber(number: String): String {
        val digits = number.replace(Regex("[^0-9+]"), "").trim()
        val withoutPlus = digits.removePrefix("+")
        // Strip country code 91 if 12 digits
        return if (withoutPlus.length == 12 && withoutPlus.startsWith("91")) {
            withoutPlus.substring(2)
        } else if (withoutPlus.length > 10 && withoutPlus.startsWith("0")) {
            withoutPlus.removePrefix("0")
        } else {
            withoutPlus
        }
    }

    private fun formatNumberDisplay(clean: String): String {
        return if (clean.length == 10) {
            "+91 ${clean.substring(0, 5)} ${clean.substring(5)}"
        } else {
            clean
        }
    }

    /**
     * Looks up contact name from Android Contacts Provider.
     */
    private fun lookupLocalContact(context: Context, cleanNumber: String): String? {
        val last10 = cleanNumber.takeLast(10)
        try {
            val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(last10))
            context.contentResolver.query(
                uri,
                arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME),
                null, null, null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(ContactsContract.PhoneLookup.DISPLAY_NAME)
                    if (idx >= 0) {
                        val name = cursor.getString(idx)
                        if (!name.isNullOrBlank()) return name
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Contacts lookup error: ${e.message}")
        }
        return null
    }

    /**
     * Instantly resolves caller carrier, circle, and TRAI regulatory status without any network call (0ms).
     */
    fun getInstantProfile(rawNumber: String): CallerIdentityResult? {
        val clean = cleanPhoneNumber(rawNumber)
        if (clean.isBlank()) return null
        lookupCache[clean]?.let { return it }
        val trai = checkTraiPrefix(clean)
        if (trai != null) return trai
        val carrierCircle = inferCarrierAndCircle(clean)
        return CallerIdentityResult(
            rawNumber = rawNumber,
            cleanDigits = clean,
            formattedDisplay = formatNumberDisplay(clean),
            source = "carrier_inference",
            displayName = null,
            isSpam = false,
            spamRisk = "None",
            operatorAndCircle = carrierCircle,
            details = "Mobile line from $carrierCircle.",
            recommendation = "Normal unknown caller."
        )
    }

    /**
     * Detects official TRAI allocated regulatory prefixes in India:
     * - 140 series: Mandated strictly for Commercial / Telemarketing calls.
     * - 160 series: Mandated strictly for Transactional / Service calls (Banks, OTPs, Delivery).
     * - 1800 series: Toll-Free.
     * - 1860 series: Commercial Local Toll.
     */
    fun checkTraiPrefix(clean: String): CallerIdentityResult? {
        val formatted = formatNumberDisplay(clean)
        return when {
            clean.startsWith("140") -> {
                CallerIdentityResult(
                    rawNumber = clean,
                    cleanDigits = clean,
                    formattedDisplay = formatted,
                    source = "trai_regulation",
                    displayName = "Telemarketer (140 Series)",
                    isSpam = true,
                    spamCategory = "Commercial / Telemarketing",
                    spamRisk = "High",
                    operatorAndCircle = "TRAI Registered Commercial Line",
                    details = "This number begins with '140', officially designated by TRAI for commercial marketing & sales calls in India.",
                    recommendation = "Can be ignored or rejected safely if you do not want promotional calls."
                )
            }
            clean.startsWith("160") -> {
                CallerIdentityResult(
                    rawNumber = clean,
                    cleanDigits = clean,
                    formattedDisplay = formatted,
                    source = "trai_regulation",
                    displayName = "Service / Bank / OTP Call (160 Series)",
                    isSpam = false,
                    spamRisk = "None",
                    businessName = "Transactional / Service Line",
                    operatorAndCircle = "TRAI Registered Transactional Line",
                    details = "This number begins with '160', officially designated by TRAI for transactional banking, delivery, or essential customer service updates.",
                    recommendation = "Legitimate service or notification call."
                )
            }
            clean.startsWith("1800") -> {
                CallerIdentityResult(
                    rawNumber = clean,
                    cleanDigits = clean,
                    formattedDisplay = formatted,
                    source = "trai_regulation",
                    displayName = "Toll-Free Customer Care",
                    isSpam = false,
                    spamRisk = "Low",
                    businessName = "National Toll-Free Service",
                    operatorAndCircle = "National Toll-Free (India)",
                    details = "Official 1800 Toll-Free customer service line.",
                    recommendation = "Likely customer support or enterprise service line."
                )
            }
            else -> null
        }
    }

    /**
     * Infers telecom operator and telecom circle based on standard Indian National Numbering Plan (NNP).
     */
    fun inferCarrierAndCircle(clean: String): String {
        if (clean.length < 4) return "Indian Cellular Network"
        val prefix4 = clean.take(4)
        val prefix2 = clean.take(2)

        val circle = when {
            // Delhi NCR
            prefix4 in listOf("9810", "9811", "9818", "9891", "9910", "9958", "9971", "9999", "9871", "9873", "8800", "8826", "7042", "7011") -> "Delhi NCR"
            // Mumbai
            prefix4 in listOf("9820", "9821", "9833", "9869", "9892", "9920", "9930", "9969", "9987", "9819", "9867", "9320", "7045") -> "Mumbai"
            // UP East
            prefix4 in listOf("9839", "9415", "9450", "9451", "9452", "9935", "9936", "9838", "9792", "8004", "8005", "7388", "7318", "9140", "6388") -> "Uttar Pradesh (East)"
            // UP West
            prefix4 in listOf("9837", "9412", "9897", "9927", "9410", "9411", "9719", "9759", "9760", "8449", "7500", "7535") -> "Uttar Pradesh (West)"
            // Maharashtra & Goa
            prefix4 in listOf("9822", "9823", "9850", "9860", "9890", "9922", "9923", "9960", "9970", "9975", "7030", "9422", "9423") -> "Maharashtra & Goa"
            // Bihar & Jharkhand
            prefix4 in listOf("9835", "9934", "9939", "9431", "9430", "9771", "9709", "8877", "7070", "6200", "6201") -> "Bihar & Jharkhand"
            // Kolkata & West Bengal
            prefix4 in listOf("9830", "9831", "9832", "9836", "9903", "9433", "9434", "7003", "7001") -> "Kolkata & West Bengal"
            // Karnataka & Bangalore
            prefix4 in listOf("9844", "9845", "9880", "9886", "9900", "9945", "9980", "9448", "9449", "7022") -> "Karnataka"
            // Tamil Nadu & Chennai
            prefix4 in listOf("9840", "9841", "9884", "9940", "9941", "9444", "9445", "7299", "9842", "9843", "9865", "9942", "9943") -> "Tamil Nadu"
            // Gujarat
            prefix4 in listOf("9824", "9825", "9879", "9898", "9904", "9909", "9924", "9925", "9974", "9978", "9426", "9427") -> "Gujarat"
            // Rajasthan
            prefix4 in listOf("9828", "9829", "9887", "9928", "9929", "9982", "9983", "9413", "9414") -> "Rajasthan"
            // Madhya Pradesh & Chhattisgarh
            prefix4 in listOf("9826", "9827", "9893", "9926", "9977", "9981", "9993", "9425", "9424") -> "Madhya Pradesh & CG"
            // Andhra Pradesh & Telangana
            prefix4 in listOf("9848", "9849", "9866", "9885", "9908", "9948", "9949", "9959", "9985", "9989", "9440", "9441") -> "Andhra Pradesh & Telangana"
            // Punjab & Haryana
            prefix4 in listOf("9814", "9815", "9855", "9872", "9876", "9878", "9888", "9914", "9915", "9988", "9417", "9812", "9813", "9896") -> "Punjab / Haryana"
            else -> "India (National)"
        }

        val operator = when {
            prefix2 in listOf("62", "63", "70", "79", "80", "81", "82", "83", "84", "85", "86", "87", "88", "89") && prefix4.startsWith("6") -> "Jio"
            prefix4.startsWith("700") || prefix4.startsWith("701") || prefix4.startsWith("702") -> "Jio"
            prefix4.startsWith("981") || prefix4.startsWith("989") || prefix4.startsWith("991") || prefix4.startsWith("995") || prefix4.startsWith("997") -> "Airtel"
            prefix4.startsWith("982") || prefix4.startsWith("983") || prefix4.startsWith("992") || prefix4.startsWith("993") -> "Vi / Airtel"
            prefix4.startsWith("941") || prefix4.startsWith("942") || prefix4.startsWith("943") || prefix4.startsWith("944") || prefix4.startsWith("945") -> "BSNL"
            else -> "Cellular Network"
        }

        return "$operator • $circle"
    }

    /**
     * Executes DuckDuckGo Lite search in background to check if the number is reported on public web directories,
     * scam forums, or business catalogs.
     */
    private fun performOnlineSpamAndBusinessSearch(clean: String, carrierCircle: String): CallerIdentityResult {
        val formatted = formatNumberDisplay(clean)
        val query = "\"$clean\" OR \"+91$clean\""

        try {
            val formBody = FormBody.Builder()
                .add("q", query)
                .build()

            val request = Request.Builder()
                .url("https://lite.duckduckgo.com/lite/")
                .post(formBody)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 12; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36")
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "en-US,en;q=0.9,hi;q=0.8")
                .build()

            val response = httpClient.newCall(request).execute()
            if (response.isSuccessful) {
                val html = response.body?.string() ?: ""
                val snippetPattern = Regex("""<td[^>]*class=['"]result-snippet['"][^>]*>(.*?)</td>""", RegexOption.DOT_MATCHES_ALL)
                val linkPattern = Regex("""<a\b([^>]*\bclass=['"]result-link['"][^>]*)>(.*?)</a>""", RegexOption.DOT_MATCHES_ALL)

                val snippets = snippetPattern.findAll(html).map { cleanHtml(it.groupValues[1]) }.filter { it.length > 15 }.take(5).toList()
                val titles = linkPattern.findAll(html).map { cleanHtml(it.groupValues[2]) }.filter { it.isNotBlank() }.take(5).toList()

                val combinedText = (titles + snippets).joinToString(" ").lowercase()

                // Check for Spam / Fraud signals
                val spamKeywords = listOf(
                    "spam", "scam", "fraud", "telemarketer", "harassment", "fake", "robocall",
                    "lottery", "personal loan", "credit card offer", "debt collection", "police complaint",
                    "reported as spam", "pesky call", "blocked 100 times", "negative rating"
                )
                val spamHits = spamKeywords.filter { combinedText.contains(it) }

                // Check for Business / Delivery signals
                val businessKeywords = mapOf(
                    "Amazon" to listOf("amazon", "ats", "easy ship"),
                    "Flipkart" to listOf("flipkart", "ekart"),
                    "Delhivery" to listOf("delhivery", "courier", "parcel delivery"),
                    "Blue Dart" to listOf("blue dart", "bluedart"),
                    "Shadowfax" to listOf("shadowfax"),
                    "Zomato" to listOf("zomato", "delivery partner"),
                    "Swiggy" to listOf("swiggy", "instamart"),
                    "Blinkit" to listOf("blinkit", "grofers"),
                    "Zepto" to listOf("zepto"),
                    "State Bank of India (SBI)" to listOf("state bank of india", "sbi card", "sbi bank"),
                    "HDFC Bank" to listOf("hdfc bank", "hdfc credit"),
                    "ICICI Bank" to listOf("icici bank", "icici credit"),
                    "Axis Bank" to listOf("axis bank"),
                    "Kotak Mahindra Bank" to listOf("kotak mahindra", "kotak bank"),
                    "Airtel Customer Service" to listOf("airtel customer", "airtel dth", "airtel broadband"),
                    "Jio Customer Service" to listOf("jio customer", "reliance jio", "jio fiber")
                )

                var matchedBusiness: String? = null
                for ((brand, keywords) in businessKeywords) {
                    if (keywords.any { combinedText.contains(it) }) {
                        matchedBusiness = brand
                        break
                    }
                }

                // High Risk Spam
                if (spamHits.isNotEmpty()) {
                    val spamCategory = when {
                        combinedText.contains("fraud") || combinedText.contains("scam") -> "Suspected Scam / Fraud"
                        combinedText.contains("loan") || combinedText.contains("credit card") -> "Telemarketing / Financial Offers"
                        combinedText.contains("robocall") -> "Automated Robocall"
                        else -> "Unsolicited Telemarketing"
                    }
                    val snippetPreview = snippets.firstOrNull()?.take(140) ?: "Reported as unwanted spam by users online."
                    return CallerIdentityResult(
                        rawNumber = clean,
                        cleanDigits = clean,
                        formattedDisplay = formatted,
                        source = "web_search",
                        displayName = "⚠️ $spamCategory",
                        isSpam = true,
                        spamCategory = spamCategory,
                        spamRisk = if (spamHits.size >= 2) "High" else "Moderate",
                        operatorAndCircle = carrierCircle,
                        details = "Reported online as $spamCategory ($carrierCircle). Matched signals: ${spamHits.joinToString(", ")}. Web snippet: \"$snippetPreview\"",
                        recommendation = "Reject or ignore call. Do not share OTPs, passwords, or personal details."
                    )
                }

                // Recognized Business or Delivery
                if (matchedBusiness != null) {
                    val snippetPreview = snippets.firstOrNull()?.take(140) ?: "Associated with $matchedBusiness services."
                    return CallerIdentityResult(
                        rawNumber = clean,
                        cleanDigits = clean,
                        formattedDisplay = formatted,
                        source = "web_search",
                        displayName = matchedBusiness,
                        isSpam = false,
                        spamRisk = "Low",
                        businessName = matchedBusiness,
                        operatorAndCircle = carrierCircle,
                        details = "Identified online with $matchedBusiness ($carrierCircle). \"$snippetPreview\"",
                        recommendation = "Likely legitimate service, delivery, or support communication."
                    )
                }

                // Directory Listing Found (General Title)
                val relevantTitle = titles.firstOrNull { t ->
                    !t.contains("DuckDuckGo", ignoreCase = true) && t.length > 5 && !t.contains("Search")
                }
                if (relevantTitle != null) {
                    return CallerIdentityResult(
                        rawNumber = clean,
                        cleanDigits = clean,
                        formattedDisplay = formatted,
                        source = "web_search",
                        displayName = relevantTitle.take(35),
                        isSpam = false,
                        spamRisk = "Low",
                        operatorAndCircle = carrierCircle,
                        details = "Found in online public directories: '$relevantTitle' ($carrierCircle).",
                        recommendation = "Publicly listed contact or business."
                    )
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Online search lookup failed: ${e.message}")
        }

        // Fallback: Clean Carrier & Circle Profile
        return CallerIdentityResult(
            rawNumber = clean,
            cleanDigits = clean,
            formattedDisplay = formatted,
            source = "carrier_inference",
            displayName = null,
            isSpam = false,
            spamRisk = "Low",
            operatorAndCircle = carrierCircle,
            details = "Personal or unlisted mobile number ($carrierCircle). Not listed in spam databases or public business directories.",
            recommendation = "Answer with caution if expecting a personal call, or let it ring."
        )
    }

    private fun cleanHtml(raw: String): String {
        return raw.replace(Regex("<[^>]*>"), "")
            .replace("&quot;", "\"")
            .replace("&#x27;", "'")
            .replace("&#039;", "'")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&nbsp;", " ")
            .trim()
    }
}
