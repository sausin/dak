package app.dak.classify.bench

import kotlin.random.Random

/** One synthetic message: what the indexer sees from the provider (address, body, SIM, inbox or not). */
data class CorpusMessage(
    val address: String,
    val body: String,
    val subId: Int,
    val incoming: Boolean,
)

/**
 * A deterministic, realistic SMS corpus for the indexing benchmark and the cache/prefilter equivalence tests.
 *
 * The mix follows a heavy Indian user's inbox: bank / card / UPI alerts, OTPs, deliveries, promotions, bills, some
 * scams and fake credit alerts, and personal chat (Hinglish included). Every template gets fresh amounts, dates,
 * masked accounts, codes, references, merchants, names and short links, so bodies repeat as *templates* (as real
 * traffic does) but rarely verbatim. Personal messages are stitched from fragments, so most are unique. The same
 * [seed] always yields the same corpus.
 */
object SyntheticCorpus {

    fun generate(size: Int = 50_000, seed: Long = 20260923L): List<CorpusMessage> {
        val g = Gen(Random(seed))
        return List(size) { g.next() }
    }

    private class Gen(val r: Random) {
        private val prefixes = listOf("VM", "AX", "JD", "BZ", "VK", "AD", "TM", "JM", "CP", "BP", "QP", "IM")
        private val banks = listOf(
            "HDFCBK" to "HDFC Bank", "ICICIB" to "ICICI Bank", "SBIINB" to "SBI", "AXISBK" to "Axis Bank",
            "KOTAKB" to "Kotak Bank", "PNBSMS" to "PNB", "CANBNK" to "Canara Bank", "IDFCFB" to "IDFC FIRST Bank",
            "YESBNK" to "YES Bank", "INDUSB" to "IndusInd Bank",
        )
        private val otpServices = listOf(
            "AMAZON" to "Amazon", "FLPKRT" to "Flipkart", "PAYTMB" to "Paytm", "PHONPE" to "PhonePe", "SWIGGY" to "Swiggy",
            "ZOMATO" to "Zomato", "IRCTCS" to "IRCTC", "GOOGLE" to "Google", "MYNTRA" to "Myntra", "UBERIN" to "Uber",
        )
        private val merchants = listOf(
            "AMAZON", "SWIGGY", "ZOMATO", "FLIPKART", "BIGBASKET", "UBER INDIA", "RELIANCE FRESH", "DMART", "NETFLIX",
            "APOLLO PHARMACY", "INDIAN OIL", "MAKEMYTRIP", "BOOKMYSHOW", "JIO", "AIRTEL", "IRCTC", "MYNTRA", "BLINKIT",
        )
        private val names = listOf(
            "Rohit Sharma", "Priya Singh", "Amit Kumar", "Neha Gupta", "Rahul Verma", "Anjali Mehta", "Vikas Yadav",
            "Sneha Iyer", "Arjun Nair", "Pooja Reddy", "Karan Malhotra", "Divya Joshi", "Suresh Patil", "Meera Das",
        )
        private val firstNames = names.map { it.substringBefore(' ') }
        private val months = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
        private val couriers = listOf("DELHVY" to "Delhivery", "BLUDRT" to "BlueDart", "EKARTL" to "Ekart", "XPRESB" to "XpressBees")
        private val telcos = listOf("JIOINF" to "Jio", "AIRTEL" to "Airtel", "VIINDI" to "Vi")
        private val shortHosts = listOf("bit.ly", "amzn.in", "fkrt.it", "hdfcbk.io", "sbi.co.in", "tinyurl.com", "jio.com")
        private val scamHosts = listOf("kyc-update-now.xyz", "sbi-rewards.top", "hdfc-kyc.info", "pm-kisan.click", "refund-it.tk")

        fun next(): CorpusMessage {
            val roll = r.nextInt(1000)
            return when {
                roll < 230 -> bankAlert()
                roll < 330 -> upi()
                roll < 530 -> otp()
                roll < 600 -> delivery()
                roll < 720 -> promo()
                roll < 750 -> bill()
                roll < 770 -> scam()
                roll < 780 -> fakeCredit()
                roll < 800 -> telco()
                roll < 990 -> personal()
                else -> sent()
            }
        }

        // --- volatile fields -------------------------------------------------------------------------------------

        private fun digits(n: Int) = buildString { repeat(n) { append('0' + r.nextInt(10)) } }
        private fun <T> pick(list: List<T>): T = list[r.nextInt(list.size)]
        private fun sim() = if (r.nextInt(4) == 0) 2 else 1
        private fun acct() = "XX" + digits(4)
        private fun ref() = digits(12)
        private fun code() = digits(if (r.nextBoolean()) 6 else 4)
        private fun date() = "%02d-%s-%02d".format(1 + r.nextInt(28), pick(months), 22 + r.nextInt(5))
        private fun numDate() = "%02d-%02d-%02d".format(1 + r.nextInt(28), 1 + r.nextInt(12), 22 + r.nextInt(5))
        private fun time() = "%02d:%02d".format(r.nextInt(24), r.nextInt(60))
        private fun mobile() = "+91" + (6 + r.nextInt(4)) + digits(9)
        private fun link(host: String = pick(shortHosts)) = "https://$host/" + buildString {
            repeat(6 + r.nextInt(4)) { append("abcdefghijkmnpqrstuvwxyzABCDEFGH23456789"[r.nextInt(40)]) }
        }

        private fun amount(max: Int = 60_000): String {
            val rupees = 1 + r.nextInt(max)
            val grouped = indian(rupees)
            return when (r.nextInt(4)) {
                0 -> grouped
                1 -> "$grouped.00"
                2 -> "$rupees.${digits(2)}"
                else -> rupees.toString()
            }
        }

        private fun indian(n: Int): String {
            val s = n.toString()
            if (s.length <= 3) return s
            val head = s.dropLast(3)
            val grouped = head.reversed().chunked(2).joinToString(",").reversed()
            return "$grouped,${s.takeLast(3)}"
        }

        private fun rs() = pick(listOf("Rs.", "Rs ", "INR ", "₹"))
        private fun header(h: String, suffix: String = pick(listOf("", "", "-S", "-T"))) = "${pick(prefixes)}-$h$suffix"

        // --- message families ------------------------------------------------------------------------------------

        private fun bankAlert(): CorpusMessage {
            val (h, bank) = pick(banks)
            val body = when (r.nextInt(8)) {
                0 -> "${rs()}${amount()} debited from A/c ${acct()} on ${numDate()} at ${pick(merchants)}. Avl bal ${rs()}${amount(400_000)}. Not you? Call 18002586161"
                1 -> "Your $bank a/c ${acct()} credited with ${rs()}${amount(150_000)} on ${date()} by NEFT. Avl bal ${rs()}${amount(400_000)}"
                2 -> "${rs()}${amount(20_000)} spent on your $bank Credit Card ${acct()} at ${pick(merchants)} on ${date()}. Avl limit ${rs()}${amount(300_000)}"
                3 -> "Dear Customer, ${rs()}${amount(25_000)} withdrawn from ATM using $bank Debit Card ${acct()} on ${date()} ${time()}. Avl Bal ${rs()}${amount(200_000)}"
                4 -> "$bank: ${rs()}${amount(90_000)} credited to a/c ${acct()} via IMPS on ${numDate()}. Ref no ${ref()}. Avl bal ${rs()}${amount(500_000)}"
                5 -> "Payment of ${rs()}${amount(5_000)} made to ${pick(merchants)} via $bank Debit Card ending ${digits(4)}. Avl Bal ${rs()}${amount(100_000)}"
                6 -> "A/c ${acct()} debited ${rs()}${amount(40_000)} towards EMI on ${date()}. Avl Bal ${rs()}${amount(80_000)} - $bank"
                else -> "Your a/c no. ${acct()} is credited by ${rs()}${amount(120_000)} on ${date()} (Salary). Available balance is ${rs()}${amount(600_000)}. -$bank"
            }
            return CorpusMessage(header(h), body, sim(), incoming = true)
        }

        private fun upi(): CorpusMessage {
            val (h, bank) = pick(banks)
            val body = when (r.nextInt(4)) {
                0 -> "You have received ${rs()}${amount(20_000)} via UPI from ${pick(names)}. UPI Ref No ${ref()}. -$bank"
                1 -> "${rs()}${amount(8_000)} debited from A/c ${acct()} on ${date()} to VPA ${pick(firstNames).lowercase()}${digits(2)}@okaxis (UPI Ref No ${ref()}). Not you? SMS BLOCK to 9215676766"
                2 -> "Sent ${rs()}${amount(3_000)} from $bank A/C *${digits(4)} To ${pick(merchants)} On ${numDate()} Ref ${ref()}. Not You? Call 18002586161"
                else -> "Dear UPI user A/C X${digits(4)} debited by ${amount(2_500)} on date ${date()} trf to ${pick(names)} Refno ${ref()}. If not u? call 1800111109. -$bank"
            }
            return CorpusMessage(header(h), body, sim(), incoming = true)
        }

        private fun otp(): CorpusMessage {
            val (h, brand) = if (r.nextInt(3) == 0) pick(banks) else pick(otpServices)
            val c = code()
            val body = when (r.nextInt(9)) {
                0 -> "$c is your OTP for login to $brand. Valid for ${5 + r.nextInt(10)} minutes. Do not share this OTP with anyone."
                1 -> "Your OTP is $c for txn of ${rs()}${amount(20_000)} at ${pick(merchants)} on card ${acct()}. OTP valid 5 mins. Do not share."
                2 -> "Use $c as your one time password to verify your mobile number on $brand. Do not share with anyone."
                3 -> "Your verification code is $c. Never share this code with anyone including $brand staff."
                4 -> "<#> $c is your $brand verification code. ${randomHash()}"
                5 -> "आपका ओटीपी $c है कृपया इसे किसी के साथ साझा न करें - $brand"
                6 -> "Dear Customer, $c is the OTP to add beneficiary ${pick(names)} on $brand NetBanking. Do not share it with anyone."
                7 -> "Your $brand login code: $c\n\n@${brand.lowercase()}.com #$c"
                else -> "aapka $brand otp $c hai kripya kisi ke saath share na kare"
            }
            return CorpusMessage(header(h), body, sim(), incoming = true)
        }

        private fun randomHash() = buildString { repeat(11) { append("abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789+/"[r.nextInt(64)]) } }

        private fun delivery(): CorpusMessage {
            val (h, courier) = pick(couriers)
            val body = when (r.nextInt(4)) {
                0 -> "Your order from ${pick(merchants)} has been shipped via $courier. Tracking ID ${digits(12)}. Track: ${link()}"
                1 -> "$courier: Your package AWB ${digits(10)} is out for delivery today. OTP for delivery is ${digits(4)}."
                2 -> "Your ${pick(merchants)} order #OD${digits(15)} has been delivered. Rate your experience: ${link()}"
                else -> "Shipment ${digits(11)} from ${pick(merchants)} has been dispatched and will reach you by ${date()}."
            }
            return CorpusMessage(header(h), body, sim(), incoming = true)
        }

        private fun promo(): CorpusMessage {
            val (h, brand) = if (r.nextBoolean()) pick(otpServices) else pick(telcos)
            val pct = 10 + 5 * r.nextInt(15)
            val body = when (r.nextInt(6)) {
                0 -> "Flat $pct% off on all electronics this weekend only! Shop now on $brand ${link()} T&C apply"
                1 -> "$brand: Get ${rs()}${amount(500)} cashback on your next order, use code SAVE${digits(2)}. Offer valid till ${date()}."
                2 -> "Big sale is live on $brand! Up to $pct% off on top brands. Hurry, limited period offer ${link()}"
                3 -> "Exclusive discount coupon worth ${rs()}${amount(1_000)} waiting for you at $brand, shop today! ${link()}"
                4 -> "Dear ${pick(firstNames)}, pre-approved offer: ${brand} Pay Later limit of ${rs()}${amount(50_000)} is ready. Activate now ${link()}"
                else -> "$brand Gold: Unlimited free delivery this month, limited time offer. Join now ${link()}"
            }
            return CorpusMessage(header(h, "-P"), body, sim(), incoming = true)
        }

        private fun bill(): CorpusMessage {
            val (h, bank) = pick(banks)
            val body = when (r.nextInt(3)) {
                0 -> "Your $bank Credit Card ${acct()} statement: Total amount due ${rs()}${amount(80_000)}, minimum amount due ${rs()}${amount(4_000)}, due on ${date()}."
                1 -> "Payment due reminder: ${rs()}${amount(30_000)} for card ending ${digits(4)} is due on ${date()}. Kindly pay to avoid late fee. -$bank"
                else -> "Electricity bill of ${rs()}${amount(6_000)} for CA no ${digits(10)} is due on ${date()}. Pay via ${link()}"
            }
            return CorpusMessage(header(h), body, sim(), incoming = true)
        }

        private fun scam(): CorpusMessage {
            val body = when (r.nextInt(5)) {
                0 -> "Dear customer your account will be suspended today, update your KYC immediately ${link(pick(scamHosts))}"
                1 -> "Congratulations! You have won a lucky draw prize of ${rs()}${amount(2_500_000)}. Click here to claim now ${link(pick(scamHosts))}"
                2 -> "You are eligible for an instant loan of ${rs()}${amount(500_000)} with low interest. Apply now ${link(pick(scamHosts))}"
                3 -> "Your PAN card will be blocked, verify now at ${link(pick(scamHosts))} or call ${mobile()}"
                else -> "Your electricity connection will be disconnected tonight at 9.30 pm. Call officer ${mobile()} immediately."
            }
            val sender = if (r.nextBoolean()) mobile() else "${pick(prefixes)}-${pick(listOf("KYCUPD", "RWRDPT", "ALRTSM"))}"
            return CorpusMessage(sender, body, sim(), incoming = true)
        }

        private fun fakeCredit(): CorpusMessage {
            val bank = pick(banks).second
            val body = when (r.nextInt(3)) {
                0 -> "Your A/c ${acct()} credited with ${rs()}${amount(50_000)} on ${date()} by ${pick(names)}. Avl bal ${rs()}${amount(90_000)} -$bank"
                1 -> "Bhai galti se aapke account me ${amount(20_000)} rupees bhej diye, please wapas kar do ${mobile()} pe GPay"
                else -> "Sir I sent ${rs()}${amount(15_000)} to your number by mistake, please return it urgently to ${pick(firstNames).lowercase()}@ybl"
            }
            return CorpusMessage(mobile(), body, sim(), incoming = true)
        }

        private fun telco(): CorpusMessage {
            val (h, telco) = pick(telcos)
            val body = when (r.nextInt(3)) {
                0 -> "$telco: Your plan expires in ${1 + r.nextInt(5)} days, recharge now to enjoy uninterrupted service. ${link()}"
                1 -> "$telco: ${rs()}${amount(999)} recharge successful for ${digits(10)}, valid till ${date()}. Data balance ${r.nextInt(3)}.${digits(2)} GB."
                else -> "${r.nextInt(90) + 10}% of your daily data pack used. Recharge with data pack now. -$telco"
            }
            return CorpusMessage(header(h), body, sim(), incoming = true)
        }

        private val openers = listOf("Hey", "Hi", "Bhai", "Yaar", "Oye", "Hello", "Arre", "Dude", "Sun", "Listen")
        private val middles = listOf(
            "are you free this evening", "kal exam hai kya", "dinner is ready come home soon", "can you pick up milk",
            "kal party mein aa raha hai na", "thanks for helping me move", "where are you we are waiting",
            "mujhe wo book wapas de dena", "did you finish the assignment", "ghar aa jao jaldi", "let's plan a trip next month",
            "meeting shifted to", "call me when you reach", "happy birthday have an amazing day", "maa ne bola sabzi le aana",
            "train is late by", "I'll be there by", "send me the photos from", "cricket match dekha kya", "office se nikal gaya",
        )
        private val closers = listOf(
            "", "", "please", "ok?", "love you", "bye", "see you", "jaldi reply kar", "thanks!", ":)", "call karna", "haha",
        )

        private fun personal(): CorpusMessage {
            val parts = ArrayList<String>()
            parts += pick(openers) + (if (r.nextBoolean()) " " + pick(firstNames) else "")
            repeat(1 + r.nextInt(3)) {
                var m = pick(middles)
                if (r.nextInt(3) == 0) m += " " + pick(listOf(time(), "${r.nextInt(12) + 1} baje", "tomorrow", "sunday", date()))
                parts += m
            }
            if (r.nextInt(10) == 0) parts += "sent you ${amount(3_000)} on gpay for the tickets"
            if (r.nextInt(12) == 0) parts += "check this ${link(pick(listOf("youtu.be", "instagram.com", "maps.app.goo.gl")))}"
            parts += pick(closers)
            val body = parts.filter { it.isNotEmpty() }.joinToString(if (r.nextBoolean()) ", " else ". ")
            return CorpusMessage(mobile(), body, sim(), incoming = true)
        }

        private fun sent(): CorpusMessage = personal().copy(incoming = false)
    }
}
