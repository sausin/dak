package app.dak.classify.gen

import app.dak.core.model.Category

/**
 * Hand-written seed corpus used to train the bundled Naive Bayes model weights
 * (`src/main/resources/app/dak/classify/model-weights.json`). Mixes English, Hindi and Hinglish,
 * matching the kind of Indian SMS traffic the app actually sees.
 */
internal object SeedCorpus {

    val examples: List<Pair<Category, String>> = listOf(
        // OTP
        Category.OTP to "123456 is your OTP for login to HDFC NetBanking. Valid for 10 minutes. Do not share this OTP with anyone.",
        Category.OTP to "Your OTP is 482910 for txn of Rs 2500 at Amazon. OTP valid 5 mins.",
        Category.OTP to "Use 774521 as your one time password to verify your mobile number. Do not share with anyone.",
        Category.OTP to "Your verification code is 903214. Never share this code with anyone including bank staff.",
        Category.OTP to "553221 is the OTP for your Paytm login. Valid for 3 minutes.",
        Category.OTP to "आपका ओटीपी 665544 है कृपया इसे किसी के साथ साझा न करें",
        Category.OTP to "आपका सत्यापन कोड 112233 है यह 10 मिनट के लिए मान्य है",
        Category.OTP to "aapka otp 990011 hai kripya kisi ke saath share na kare",
        Category.OTP to "Login OTP for your account is 445566, valid for 10 min. Do not share with anyone for security reasons.",
        Category.OTP to "Your Amazon OTP is 221100. Do not share this OTP with anyone, including Amazon associates.",
        Category.OTP to "678901 is your verification code for IRCTC login. Valid for 5 minutes.",
        Category.OTP to "Code: 4321 to verify your PhonePe account. Do not share.",
        Category.OTP to "Your security code for Google account is 991122",
        Category.OTP to "OTP for your Flipkart order confirmation is 334455",
        Category.OTP to "Aapka Swiggy login OTP hai 776655, kisi se share mat kare",

        // TRANSACTION
        Category.TRANSACTION to "Rs 4500.00 debited from A/c XX1234 on 12-03-24 at AMAZON. Avl bal Rs 12,340.00",
        Category.TRANSACTION to "Your HDFC Bank a/c XX5678 credited with Rs 25,000.00 on 10-Sep-25 by NEFT. Avl bal Rs 45,600.00",
        Category.TRANSACTION to "Rs.850 spent on your ICICI Bank Credit Card XX9012 at SWIGGY on 05-Aug. Avl limit Rs 50,000",
        Category.TRANSACTION to "You have received Rs 1200 via UPI from Rohit Sharma. UPI Ref No 234567891023.",
        Category.TRANSACTION to "Payment of Rs 599 made to Netflix via SBI Debit Card ending 4321. Avl Bal Rs 8,900",
        Category.TRANSACTION to "Your Axis Bank account is credited with INR 15,000 salary. Avl Bal INR 32,450.",
        Category.TRANSACTION to "Rs 2,300 withdrawn from ATM using Kotak Debit Card XX1122 on 01-Sep-25.",
        Category.TRANSACTION to "Your order from Amazon has been shipped and is out for delivery. Tracking ID DL123456789IN",
        Category.TRANSACTION to "Your Flipkart order #OD123456 has been delivered. Tracking ID FKX998877",
        Category.TRANSACTION to "Delhivery: Your package AWB 887766 is out for delivery today.",
        Category.TRANSACTION to "BlueDart shipment BD556677 has been dispatched from Mumbai hub.",
        Category.TRANSACTION to "Paytm: Rs 500 added to your wallet successfully. Ref 998877665544.",
        Category.TRANSACTION to "PNB a/c XX7890 debited Rs 3,400 towards EMI on 03-Sep-25. Avl Bal Rs 21,000.",
        Category.TRANSACTION to "Canara Bank: Rs 10,000 credited to a/c XX4567 via IMPS. Avl bal Rs 55,780.",
        Category.TRANSACTION to "IRCTC: Your PNR 4455667788 ticket booking confirmed for train 12345 on 15-Sep-25.",
        Category.TRANSACTION to "IndiGo: Your flight 6E-2145 booking is confirmed, PNR ABCDEF, departs 09:30 on 20-Sep.",
        Category.TRANSACTION to "Jio: Rs 249 recharge successful for your number, valid till 15-Oct-25.",
        Category.TRANSACTION to "Zomato order delivered! Hope you enjoyed your meal from Pizza Hub.",

        // PROMOTION
        Category.PROMOTION to "Flat 50% off on all electronics this weekend only! Shop now on Amazon.",
        Category.PROMOTION to "Jio: Recharge now with Rs 399 plan and get double data offer, valid till Sunday.",
        Category.PROMOTION to "Airtel: Your plan expires in 2 days, recharge now to enjoy uninterrupted service.",
        Category.PROMOTION to "Get 20% cashback on your next Paytm transaction, use code SAVE20.",
        Category.PROMOTION to "Big Billion Days sale is live on Flipkart! Up to 80% off on top brands.",
        Category.PROMOTION to "Swiggy: Use code FEAST50 to get 50% off up to Rs 100 on your next order.",
        Category.PROMOTION to "Vi: Special data pack offer just for you, recharge with Rs 199 today.",
        Category.PROMOTION to "Exclusive discount coupon worth Rs 500 waiting for you, shop today!",
        Category.PROMOTION to "IndiGo: Book now and get flat Rs 1000 off on domestic flights, offer ends soon.",
        Category.PROMOTION to "Zomato Gold: Unlimited free delivery this month, limited time offer.",
        Category.PROMOTION to "HDFC Bank: Flat 10% instant discount on your credit card at select merchants this festive season.",
        Category.PROMOTION to "Amazon: Great Indian Festival sale starts now, grab deals up to 70% off.",

        // SPAM
        Category.SPAM to "Congratulations! You have won a lottery of Rs 25,00,000. Click here to claim your prize now.",
        Category.SPAM to "Your PAN card will be blocked, update your KYC immediately by clicking this link http://kyc-verify.xyz",
        Category.SPAM to "URGENT: Your bank account will be suspended. Verify your details now at http://secure-bank-kyc.tk",
        Category.SPAM to "You are eligible for an instant pre-approved loan of Rs 5,00,000 with low interest, apply now.",
        Category.SPAM to "Lucky winner! You have been selected for a free iPhone, click here to claim now.",
        Category.SPAM to "Your Aadhaar card is about to expire, update immediately to avoid suspension http://aadhar-kyc.info",
        Category.SPAM to "Congratulations you have won a lucky draw prize of Rs 10 lakh, contact us immediately to claim.",
        Category.SPAM to "Dear customer your account has been suspended due to KYC pending, click here to verify now.",
        Category.SPAM to "Get instant loan approved in 5 minutes with zero documentation, apply now on this link.",
        Category.SPAM to "Your parcel is on hold due to unpaid customs fee, click here to pay and release your parcel.",

        // PERSONAL
        Category.PERSONAL to "Hey are you free this evening? Let's catch up for coffee near the office.",
        Category.PERSONAL to "Bhai kal exam hai kya, mujhe yaad dila dena please",
        Category.PERSONAL to "Mom said dinner is ready, come home soon",
        Category.PERSONAL to "Happy birthday yaar! Have an amazing day, let's meet this weekend",
        Category.PERSONAL to "Can you pick up milk on your way home? We are out of it",
        Category.PERSONAL to "Kal party mein aa raha hai na? Sabko bata diya hai time 8 baje",
        Category.PERSONAL to "Thanks for helping me move yesterday, really appreciate it",
        Category.PERSONAL to "Where are you? We have been waiting at the restaurant for 20 minutes",
        Category.PERSONAL to "Mujhe wo book wapas de dena jab time mile, koi jaldi nahi hai",
        Category.PERSONAL to "Did you finish the assignment? I still have two questions left",
        Category.PERSONAL to "Ghar aa jao jaldi, papa bula rahe hai",
        Category.PERSONAL to "Let's plan a trip next month, I found some great deals on hotels",
    )
}
