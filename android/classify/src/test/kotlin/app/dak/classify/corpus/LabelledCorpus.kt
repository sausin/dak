package app.dak.classify.corpus

import app.dak.core.model.Category
import app.dak.core.model.Category.OTP
import app.dak.core.model.Category.PERSONAL
import app.dak.core.model.Category.PROMOTION
import app.dak.core.model.Category.SPAM
import app.dak.core.model.Category.TRANSACTION

/**
 * A broad, hand-labelled corpus of realistic Indian SMS traffic (English, Hinglish, Hindi) for the classifier's
 * regression test ([LabelledCorpusTest]). Every name, number, header and domain is made up, and brands are mostly
 * fictional on purpose: a courier, merchant or bank the bundle has never heard of must be categorised exactly like a
 * well-known one, because categorisation keys on the structure and vocabulary of a message type, never on who sent it.
 *
 * Senders mix DLT headers with each traffic suffix (`-T` transactional, `-S` service, `-P` promotional, `-G`
 * government) and without one, operator short codes, and 10-digit mobiles (some saved as contacts, see
 * [CONTACTS]).
 *
 * Category decisions for the ambiguous families:
 * - Order / delivery / invoice / booking / ticket / PNR / appointment updates are TRANSACTION, whatever "rate us"
 *   or feedback link follows.
 * - Carrier notices about the user's own line (recharge done, data used, plan/validity expiring, bill due) are
 *   TRANSACTION (the "alerts" tab); "now available to take calls" and missed-call alerts are about a person, so
 *   PERSONAL.
 * - Bank service messages without an amount (card or cheque book dispatched, KYC reminders from a registered
 *   route, request registered) are TRANSACTION.
 * - Government (`-G`) notices are TRANSACTION unless they carry a code (OTP).
 * - Promotions dressed as transactions ("cashback credited* T&C", "order now") are PROMOTION.
 * - Bank-alert-shaped text from a private mobile number is SPAM (Indian banks only send from DLT headers).
 */
internal object LabelledCorpus {

    data class Case(val expected: Category, val address: String, val body: String)

    /** Mobile numbers the corpus treats as saved contacts. */
    val CONTACTS: Set<String> = setOf("+919876500001", "9876500002", "+919876500003", "9876500004")

    private fun c(expected: Category, address: String, body: String) = Case(expected, address, body)

    val otp: List<Case> = listOf(
        c(OTP, "VM-QBANKX-T", "482913 is your OTP for login to Quill Bank NetBanking. Valid for 5 mins. Do not share it with anyone."),
        c(OTP, "JD-NIMBUS-T", "Dear Customer, OTP for txn of Rs 2,499.00 at SHOPKART on Nimbus Bank card XX4411 is 773201. Valid till 10:42. Do not share."),
        c(OTP, "AX-ZYPAYS-S", "Your ZyPay verification code is 918273. Never share this code with anyone."),
        c(OTP, "VK-KRTMRT", "Use 5521 as your one time password to verify your mobile number on KartMart."),
        c(OTP, "BZ-FOODLY-T", "Aapka Foodly login OTP hai 667788, kisi ke saath share mat karna."),
        c(OTP, "VM-ORBITT-T", "आपका ओटीपी 435261 है। इसे किसी के साथ साझा न करें। - Orbit Travels"),
        c(OTP, "TX-GOVPOR-G", "OTP for your application on the citizen services portal is 552910. Valid for 10 minutes."),
        c(OTP, "57575", "Your code is 8812. Enter it in the app to continue."),
        c(OTP, "VM-RIDEGO-S", "<#> 3321 is your RideGo login code. Do not share. aB3dE9fG1hJ"),
        c(OTP, "AD-TICKTS-T", "Your one-time password for booking cancellation is 776655. It expires in 3 minutes."),
        c(OTP, "VM-INSURX-T", "123987 is the OTP to verify your policy renewal of Rs 8,450. Do not disclose to anyone."),
        c(OTP, "JM-QBANKX-S", "OTP 901234 for adding beneficiary Ravi K to your Quill Bank account. If not initiated by you, call 1800-000-0000."),
        c(OTP, "VM-EDUPRO-T", "Your EduPro security code is 664411. It is valid for 15 minutes."),
        c(OTP, "CP-SHOPIX-T", "Shopix: 290183 is your OTP to confirm delivery of order 40211. Share it only with the delivery agent at your door."),
        c(OTP, "VM-DIGILK-G", "Your verification code for document access is 339201. Do not share it with anyone."),
    )

    val bankTransactions: List<Case> = listOf(
        c(TRANSACTION, "VM-QBANKX-S", "Rs 4,500.00 debited from A/c XX1234 on 12-03-26 to VPA shop@okquill UPI Ref 612345678901. Not you? Call 18000000000"),
        c(TRANSACTION, "JD-NIMBUS-T", "Your A/c X5073 is credited with INR 25,000.00 on 10-Sep-26 by NEFT from ACME CORP. Avl Bal INR 45,600.12"),
        c(TRANSACTION, "AX-NIMBCC-T", "Rs.850 spent on your Nimbus Bank Credit Card ending 9012 at CAFE BREW on 05-Aug. Avl limit Rs 50,000."),
        c(TRANSACTION, "VM-QBANKX-T", "You have received Rs 1,200 from Rohit S via UPI. UPI Ref No 234567891023. Bal Rs 3,410.50"),
        c(TRANSACTION, "BZ-ZYPAYS-S", "Paid Rs 599 to StreamFlix using ZyPay UPI. Txn ID ZP88776655."),
        c(TRANSACTION, "VK-QBANKX", "Rs 2,300 withdrawn at ATM from A/c XX1122 on 01-Sep-26. Avl Bal Rs 18,220."),
        c(TRANSACTION, "VM-LENDIX-T", "EMI of Rs 3,400 for loan a/c XX7890 debited on 03-Sep-26. Next EMI due 03-Oct-26."),
        c(TRANSACTION, "JD-WALLTY-S", "Rs 500 added to your Wallety wallet successfully. Ref 998877665544."),
        c(TRANSACTION, "AD-NIMBUS-T", "Your salary of INR 62,000 has been credited to a/c **4455. Avl bal INR 70,112.40"),
        c(TRANSACTION, "VM-QBANKX-S", "आपके खाते XX3344 से रु 1,500 डेबिट किए गए। उपलब्ध शेष रु 9,870."),
        c(TRANSACTION, "VM-QBANKX-S", "Refund of Rs 349 for your cancelled order has been credited to your card XX6612."),
        c(TRANSACTION, "JD-NIMBCC-T", "Payment of Rs 12,000 received towards your Nimbus Credit Card XX9012. Thank you."),
        c(TRANSACTION, "VM-MUTUAL-S", "Your SIP of Rs 5,000 in Growth Fund has been processed. Units allotted: 42.113. Folio 88221133."),
        c(TRANSACTION, "AX-QBANKX-T", "Cashback of Rs 25 credited to your a/c XX1234 for UPI txn Ref 551234567890."),
        c(TRANSACTION, "VM-ZYPAYS-T", "Rs 1,000 sent to Anita (anita@zyp) from A/c XX2211. UPI Ref 776655443322. Not you? Report at 1800-000-1111"),
    )

    val bankService: List<Case> = listOf(
        c(TRANSACTION, "VM-QBANKX-S", "Your new Debit Card ending 4411 has been dispatched via courier, AWB 55112233. It will reach you in 5-7 working days."),
        c(TRANSACTION, "JD-NIMBUS-S", "Your cheque book request for A/c XX5073 has been processed and the cheque book dispatched to your registered address."),
        c(TRANSACTION, "AX-NIMBUS-T", "Dear Customer, your periodic KYC update is due. Please visit your nearest branch with valid ID proof or update via net banking. Ignore if already done."),
        c(TRANSACTION, "VM-QBANKX-S", "Your KYC has been updated successfully. Thank you for banking with Quill Bank."),
        c(TRANSACTION, "VM-QBANKX-T", "Your mobile number has been successfully updated for A/c XX1234. If not done by you, call 18000000000 immediately."),
        c(TRANSACTION, "JD-NIMBUS-S", "Service request 4455667 for address change has been registered and will be resolved within 7 working days."),
        c(TRANSACTION, "VM-NIMBCC-T", "Your Nimbus Credit Card statement for Aug is generated. Total due Rs 12,450, minimum due Rs 620, due date 15-Sep."),
        c(TRANSACTION, "AX-QBANKX-S", "Your Debit Card XX4411 has been blocked as requested. For a replacement card, visit net banking."),
        c(TRANSACTION, "VM-QBANKX-S", "New login to Quill Bank mobile banking from a new device on 12-Sep 21:04. If this was not you, call 18000000000."),
        c(TRANSACTION, "JD-LENDIX-S", "Your loan application LN2233 has been approved. Loan agreement has been sent to your registered email."),
        c(TRANSACTION, "VM-INSURX-S", "Premium of Rs 8,450 for policy no 55667788 is due on 20-Sep-26. Pay on time to keep your cover active."),
        c(TRANSACTION, "VM-QBANKX-T", "Beneficiary Ravi K has been added to your net banking. Transfers to this beneficiary are enabled after 30 minutes."),
    )

    val logistics: List<Case> = listOf(
        // Brand-new couriers and merchants: nothing in the bundle knows these names.
        c(TRANSACTION, "JD-ZYNTRA-S", "Your order with Zyntra Logistics AWB# 77053102755 was delivered to KARTI. Please rate our service on https://zyn.tr/r/lqZvGvJb"),
        c(TRANSACTION, "VM-KARGOX-S", "We have delivered your order:14385854046 on 2026-05-18 to Meera. For feedback pls click fb.kargo.in/ffb63 Kargo Express"),
        c(TRANSACTION, "AX-PARSLY-T", "ARRIVING: Parsly 500g (SURFACE) will deliver your shipment today. Track https://psl.in/s?r=ls6ZQJYLVf3R Regards, Parsly Logistics."),
        c(TRANSACTION, "VM-SWFTSH-S", "Your shipment 1234567890 is out for delivery today. Our delivery agent Ramesh (98XXXXX123) will reach you by 7 PM."),
        c(TRANSACTION, "VK-SWFTSH-S", "Consignment 88776655 is in transit and is expected to be delivered by Friday. Track: swft.sh/t/88776655"),
        c(TRANSACTION, "JD-KARGOX-T", "We could not deliver your shipment today as the address was locked. We will reattempt delivery tomorrow."),
        c(TRANSACTION, "VM-SHOPKT-S", "Your ShopKart order #OD1122334455 has been shipped. Expected delivery by 22-Sep. Rate your experience: https://shk.it/x"),
        c(TRANSACTION, "BZ-SHOPKT-S", "Packed! Your order 5566778 is packed and will be handed over to our courier partner soon."),
        c(TRANSACTION, "VM-FOODLY-T", "Your Foodly order from Pizza Hub has been delivered. Enjoyed it? Rate your meal: foodly.in/r/9981"),
        c(TRANSACTION, "AD-GROCIT-S", "Your Grocit order is on its way! Delivery partner Sunil will reach in 12 mins. Track live: grocit.co/t/abc12"),
        c(TRANSACTION, "VM-POSTAL-G", "Speed post article EK123456789IN has been delivered on 12-Sep. Thank you for using our services."),
        c(TRANSACTION, "VM-POSTAL-G", "Article RN998877665IN booked at Andheri PO on 10-Sep is out for delivery."),
        c(TRANSACTION, "JD-TRNDZY-S", "Your return request for order 77665544 has been approved. Pickup scheduled for tomorrow between 10 AM and 6 PM."),
        c(TRANSACTION, "VM-TRNDZY-S", "Refund of Rs 1,299 for your returned item is initiated and will reflect in 5-7 days. Order ID TZ-99887766."),
        c(TRANSACTION, "VM-KARGOX-S", "Your parcel with tracking number KX7788990011 has been picked up and is on its way to the destination hub."),
        c(TRANSACTION, "JD-ZYNTRA-S", "Delivery attempted for AWB 66554433: customer not available. Next attempt on 14-Sep. Reschedule: zyn.tr/rs/6655"),
        c(TRANSACTION, "VM-SHOPKT-T", "Your order 402-1234567 has been cancelled and a refund of Rs 799 will be processed to your original payment method."),
        c(TRANSACTION, "VM-MEDKRT-S", "Order MK55667 confirmed! Your medicines will be delivered by tomorrow evening."),
        c(TRANSACTION, "AX-KARGOX-S", "Aapka shipment AWB 77889900 aaj deliver hoga. Delivery agent ka number 98XXXXX456. Track karein kargo.in/t/7788"),
        c(TRANSACTION, "VM-SHOPKT-S", "आपका ऑर्डर 55443322 डिलीवर कर दिया गया है। हमें अपना फीडबैक दें: shk.it/f/55"),
        c(TRANSACTION, "VM-ZYNTRA-S", "Your COD shipment AWB 44556677 will be delivered today. Please keep Rs 649 ready or pay online at the door."),
        c(TRANSACTION, "JD-TRNDZY-T", "Hi Meera, your Trendzy order has been delivered. Tell us how we did - it takes 30 seconds: https://trz.io/fb/1"),
    )

    val invoicesAndBills: List<Case> = listOf(
        c(TRANSACTION, "JD-SHOPKT-S", "Your invoice for order 402-1234567 is ready: https://shk.it/i/abc"),
        c(TRANSACTION, "VM-TRNDZY-T", "Tax invoice TZ/24/5566 for Rs 1,299 is attached to your email. Thank you for shopping with us."),
        c(TRANSACTION, "VM-POWERD-S", "Your electricity bill of Rs 1,840 for Aug for CA No 1100223344 is generated. Due date 15-Sep. Pay at pwrd.in/pay. Ignore if paid."),
        c(TRANSACTION, "AX-GASCOX-S", "Dear consumer, bill no 55667788 of Rs 720 for your piped gas connection is due on 20-Sep-26."),
        c(TRANSACTION, "VM-FIBERX-T", "Your broadband bill of Rs 1,179 for account 99887766 has been generated. Due date 25-Sep. Pay: fbx.in/b"),
        c(TRANSACTION, "VM-CLINIQ-S", "Receipt no 3344 for Rs 500 towards consultation at Care Clinic. Thank you for your payment."),
        c(TRANSACTION, "JD-WATERB-G", "Water bill for connection 7788 of Rs 310 is generated for Jul-Aug. Pay before 30-Sep to avoid surcharge."),
        c(TRANSACTION, "VM-SCHOOL-S", "Fee receipt for term 2 of Rs 18,500 for student Aarav (Roll 21) is generated. Download: schl.in/r/21"),
    )

    val bookings: List<Case> = listOf(
        c(TRANSACTION, "VM-RAILBK-T", "PNR 4455667788, Train 12345, Dt 15-Sep, SL S4 32, Chart prepared. Happy journey."),
        c(TRANSACTION, "VK-RAILBK-S", "Your ticket booking is confirmed. PNR: 2233445566, Train 22222, 3A, B2 17. Rate your booking experience at rlb.in/fb"),
        c(TRANSACTION, "JD-SKYAIR-T", "Web check-in is open for your flight SA-2145 on 20-Sep, PNR ABCD12. Check in now at skyair.in/checkin"),
        c(TRANSACTION, "VM-SKYAIR-S", "Your flight SA-2145 from DEL to BOM on 20-Sep is delayed. New departure 11:30. We regret the inconvenience."),
        c(TRANSACTION, "AX-BUSGOO-S", "Bus ticket confirmed! Seat 12, BusGo, Pune to Goa, 21-Sep 22:00. Boarding: Swargate. Ticket no BG778899."),
        c(TRANSACTION, "VM-CINEMX-T", "Booking ID CX99887 confirmed: 2 tickets for Screen 3, 7:30 PM, 22-Sep. Show this SMS at the counter."),
        c(TRANSACTION, "JD-CLINIQ-S", "Your appointment with Dr. Mehta is confirmed for 23-Sep at 11:00 AM at Care Clinic, Baner. Reply C to cancel."),
        c(TRANSACTION, "VM-HOTELY-S", "Your stay at Hotel Palm is confirmed. Booking ref HP77889, check-in 25-Sep, check-out 27-Sep."),
        c(TRANSACTION, "VM-RIDEGO-T", "Your RideGo ride with Suresh (MH12AB1234) is arriving in 3 mins. Share your trip: rdg.o/t/xyz"),
        c(TRANSACTION, "AX-LABSXX-S", "Sample collection for booking LB5566 is scheduled for tomorrow 7-9 AM. Our phlebotomist will call before arriving."),
        c(TRANSACTION, "VM-RAILBK-T", "Your waitlisted ticket PNR 7788990011 is now confirmed. Coach B3 Berth 44."),
        c(TRANSACTION, "VM-VACCIN-G", "Your appointment for the vaccination dose is booked for 26-Sep at the district health centre. Ref VX4455."),
    )

    val telecom: List<Case> = listOf(
        c(TRANSACTION, "JM-TELCOX-S", "Recharge of Rs 299 is successful for 98XXXXX210. Pack: 1.5GB/day, unlimited calls, valid till 20-Oct-26."),
        c(TRANSACTION, "VK-TELCOX-S", "You have used 90% of your daily high speed data quota. Speed will reduce to 64 Kbps after 100%."),
        c(TRANSACTION, "AD-TELCOX-T", "Your plan will expire on 25-Sep-26. Recharge to continue enjoying uninterrupted services."),
        c(TRANSACTION, "JM-TELCOX-S", "Your postpaid bill of Rs 599 for Aug is generated. Due date 20-Sep. Pay: tlx.in/pay"),
        c(TRANSACTION, "121", "Your balance is Rs 12.50 and validity is till 30-Sep-26. Data balance: 1.2 GB."),
        c(TRANSACTION, "VK-TELCOX-S", "100% of your daily data has been consumed. Your data will be renewed at midnight."),
        c(PERSONAL, "JM-TELCOX-S", "Dear Customer, +919812300000 is now available to take calls."),
        c(PERSONAL, "AD-TELCOX", "Missed call alert: 9812300001 called you 2 times, last at 10:42."),
        c(PERSONAL, "121", "You have 3 missed calls from 9812300002. Last call at 18:12."),
        c(PERSONAL, "JM-TELCOX-S", "98XXXXX210 is now reachable. You can call now."),
        c(PROMOTION, "BP-TELCOX-P", "Recharge now with Rs 399 plan and get double data offer, valid till Sunday."),
        c(PROMOTION, "VK-TELCOX-P", "Enjoy 3 months of free OTT subscription on recharge of Rs 999. Recharge now: tlx.in/ott"),
        c(TRANSACTION, "JM-TELCOX-T", "Your SIM has been successfully upgraded to 5G. Restart your phone to enjoy 5G services."),
    )

    val government: List<Case> = listOf(
        c(TRANSACTION, "VM-UIDXXX-G", "Your Aadhaar has been successfully updated. The updated Aadhaar will be available for download in 7 days."),
        c(TRANSACTION, "JD-ITDEPT-G", "Your Income Tax Return for AY 2026-27 has been processed. Refund of Rs 4,210 is determined."),
        c(TRANSACTION, "VM-TRAFIC-G", "E-challan 1122334455 of Rs 500 issued to vehicle MH12AB1234 for over speeding. Pay at echallan portal."),
        c(TRANSACTION, "AD-ELECTN-G", "Your name is registered in the electoral roll. Your polling station is Govt School, Ward 12."),
        c(TRANSACTION, "VM-KISANX-G", "Rs 2,000 has been credited to your bank account under the farmer income support scheme instalment."),
        c(TRANSACTION, "VM-PASSPT-G", "Your passport application file no BO1234567890 has been granted. Passport will be dispatched soon."),
        c(TRANSACTION, "VM-UIDXXX-G", "Please update your Aadhaar documents if it was issued more than 10 years ago. Visit an enrolment centre or the official portal."),
        c(TRANSACTION, "JD-DISMGT-G", "Heavy rainfall alert for your district today. Avoid travel to low lying areas. Stay safe."),
    )

    val promotions: List<Case> = listOf(
        c(PROMOTION, "VK-SHOPKT-P", "Flat 50% off on all electronics this weekend only! Shop now: shk.it/sale"),
        c(PROMOTION, "BP-FOODLY-P", "Hungry? Use code FEAST50 to get 50% off up to Rs 100 on your next order. Order now!"),
        c(PROMOTION, "VK-TRNDZY-P", "Order now! 50% off on all shoes, today only."),
        c(PROMOTION, "BP-FOODLY-P", "Order no later than 9 PM and get flat 40% off, free delivery on your order!"),
        c(PROMOTION, "VK-GADGTX-P", "The new Phone 17 is now available at GadgetX, get 10% instant discount. Order now!"),
        c(PROMOTION, "VM-WALLTY-P", "Rs 500 cashback credited* to your Wallety! Use it on your next bill payment. *T&C apply"),
        c(PROMOTION, "AD-SHOPKT", "Congratulations! You have unlocked Rs 200 off on your next order. Use code SAVE200. Valid till 30-Sep. T&C apply"),
        c(PROMOTION, "VK-NIMBUS-P", "Get a pre-approved personal loan of up to Rs 5 lakh at attractive interest rates. Apply now: nmb.in/pl"),
        c(PROMOTION, "VK-NIMBUS-P", "Upgrade to the Nimbus Platinum Credit Card with zero joining fee. Limited period offer. Apply now!"),
        c(PROMOTION, "BZ-SKYAIR-P", "Book now and get flat Rs 1000 off on domestic flights. Offer ends soon!"),
        c(PROMOTION, "VK-GROCIT-P", "Mega sale is live! Up to 80% off on groceries. Download the app now."),
        c(PROMOTION, "VM-FITNES-P", "Join our gym this month and get 2 months free membership. Hurry, limited seats!"),
        c(PROMOTION, "VK-REALTY-P", "2 & 3 BHK luxury apartments starting Rs 75 lakh* near the metro. Site visits open. Call 9000000000"),
        c(PROMOTION, "VK-EDUPRO-P", "Last chance! Enroll in our data science course and get 30% scholarship. Register today: edp.in/ds"),
        c(PROMOTION, "BP-TRNDZY-P", "Diwali dhamaka sale! Kapdon par 70% tak ki chhoot. Abhi shop karein: trz.io/d"),
        c(PROMOTION, "VK-QBANKX-P", "Your Quill Bank credit card is eligible for a limit upgrade. Enjoy 5X reward points on dining this month!"),
        c(PROMOTION, "VK-MUTUAL-P", "Invest in our new fund offer from Rs 500 via SIP. Mutual fund investments are subject to market risks."),
        c(PROMOTION, "VK-SHOPKT-P", "Your cart is waiting! Items in your cart are selling fast. Complete your order now and get free delivery."),
        c(PROMOTION, "AD-CINEMX", "Buy 1 get 1 free on movie tickets this Friday. Use code BOGO at checkout."),
        c(PROMOTION, "VK-TELCOX-P", "Get Rs 50 cashback on your next recharge using WalletY. Offer valid till 30-Sep. T&C apply"),
    )

    val scams: List<Case> = listOf(
        // Fake bank credit / debit alerts from private numbers.
        c(SPAM, "+919812345678", "Rs 15,000 credited to your A/c XX4321 by IMPS. Avl bal Rs 15,212. If not yours, return to 9812345678."),
        c(SPAM, "9812345679", "Dear customer, INR 25,000 has been credited to your account XX9981 on 12-Sep. Ref 556677889900."),
        // Parcel customs / address scams.
        c(SPAM, "+919812345678", "Your parcel is held, pay customs fee at bit.ly/3xYzAb"),
        c(SPAM, "+919812345680", "Postal service: Your package could not be delivered due to incomplete address. Update your address within 12 hours: https://postal-in.top/track"),
        c(SPAM, "VM-ZYNTRA-S", "Zyntra: your shipment AWB 88776655 is on hold, pay redelivery fee of Rs 25 at https://zyn-pay.xyz"),
        c(SPAM, "+15551234567", "Your package is waiting for delivery. Please confirm your delivery address at https://redeliver-pkg.top"),
        c(SPAM, "9812345681", "Your courier is stuck at our warehouse due to unpaid handling charges of Rs 49. Pay now at pkg-release.click/pay"),
        // KYC / account block.
        c(SPAM, "9876501234", "URGENT: Your bank account will be suspended today. Verify your KYC now at http://secure-bank-kyc.tk"),
        c(SPAM, "+919876501235", "Dear customer your account will be blocked today. Please update your PAN card immediately click here kyc-update.info/pan"),
        c(SPAM, "9876501236", "Your account is locked, click here now https://netbank-help.xyz"),
        c(SPAM, "AB-ALERTZ", "Dear user your net banking will be deactivated within 24 hours. Complete e-KYC here: bit.ly/ekyc-now"),
        c(SPAM, "9876501237", "Aapka bank khata aaj band ho jayega. Turant KYC update karein: bit.ly/kyc9"),
        c(SPAM, "9876501238", "Your SIM card will be blocked within 24 hours as your e-KYC is incomplete. Call 9876500099 immediately."),
        // Electricity disconnection.
        c(SPAM, "9876501239", "Dear consumer your electricity power will be disconnected tonight at 9.30 pm because your previous month bill was not updated. Please immediately contact our electricity officer 9876500098."),
        c(SPAM, "+919876501240", "Bijli connection aaj raat kaat diya jayega. Bill update ke liye turant call karein 9876500097."),
        // Job / task.
        c(SPAM, "9876501241", "Part time job offer: earn Rs 3000-8000 daily by liking videos from home. WhatsApp HR: wa.me/919876500096"),
        c(SPAM, "+919876501242", "Hello, we are hiring for work from home. Daily salary Rs 5000. Simple tasks on your phone. Contact on Telegram @quickjobs"),
        c(SPAM, "AB-JOBSXY", "Ghar baithe roz Rs 2000-5000 kamaye. Sirf 2 ghante kaam. Abhi judein: t.me/earnfast"),
        // Lottery / prize.
        c(SPAM, "+919876501243", "Congratulations! You have won Rs 25,00,000 in the lucky draw. Click here to claim your prize now."),
        c(SPAM, "9876501244", "Your mobile number has been selected as the winner of a car in our anniversary lucky draw. To claim call 9876500095."),
        c(SPAM, "+447700900123", "You have won a GBP 500 gift card! Claim your reward now: prize-claim.xyz/uk"),
        // UPI collect bait.
        c(SPAM, "9876501245", "You have received a cashback of Rs 2,000. Enter your UPI PIN to receive the amount in your account."),
        c(SPAM, "9876501246", "Congrats! Rs 5,000 reward is waiting. Approve the payment request in your UPI app to get it credited."),
        // Loans from private numbers.
        c(SPAM, "9876501247", "Instant loan approved of Rs 5,00,000 without documents. Low interest. Apply now: bit.ly/loan-now"),
        // Refund / tax / electricity via link.
        c(SPAM, "9876501248", "Income tax refund of Rs 15,490 has been approved. Verify your bank account to receive it: itr-refund.info/verify"),
        c(SPAM, "9876501249", "Your credit card reward points worth Rs 7,500 expire today. Redeem now at rewardz-redeem.com/cc"),
        c(SPAM, "+919876501250", "Your traffic challan of Rs 500 is pending. Pay immediately to avoid legal action: echallan-pay.top"),
    )

    val personal: List<Case> = listOf(
        c(PERSONAL, "+919876500001", "Hey are you free this evening? Let's catch up for coffee near the office."),
        c(PERSONAL, "9876500002", "Bhai kal exam hai kya, mujhe yaad dila dena please"),
        c(PERSONAL, "+919876500003", "Mom said dinner is ready, come home soon"),
        c(PERSONAL, "9876500004", "I paid the electricity bill today, you pay the internet one ok?"),
        c(PERSONAL, "+919876500001", "Did you receive the parcel I sent? It should have been delivered yesterday."),
        c(PERSONAL, "9876500002", "Sent you Rs 500 for the movie tickets, check karo"),
        c(PERSONAL, "+919876500003", "Order pizza for everyone, I'll pay you back tonight"),
        c(PERSONAL, "9876500004", "Happy birthday yaar! Have an amazing day, let's meet this weekend"),
        c(PERSONAL, "+919876500005", "Hi, this is Priya from the society committee. Meeting tomorrow at 7 pm in the clubhouse."),
        c(PERSONAL, "9876500006", "Kal party mein aa raha hai na? Sabko bata diya hai time 8 baje"),
        c(PERSONAL, "+919876500007", "Can you pick up milk on your way home? We are out of it"),
        c(PERSONAL, "9876500008", "कल सुबह 9 बजे स्टेशन पर मिलना, देर मत करना"),
        c(PERSONAL, "+919876500001", "Check this out: youtube.com/watch?v=abc123 so funny"),
        c(PERSONAL, "9876500002", "Ghar aa jao jaldi, papa bula rahe hai"),
        c(PERSONAL, "+919876500003", "Congratulations on the new job! So proud of you."),
        c(PERSONAL, "9876500004", "Where are you? We have been waiting at the restaurant for 20 minutes"),
        c(PERSONAL, "+919876500009", "Sir I am the delivery boy, I am standing at your gate with your parcel. Please come down."),
        c(PERSONAL, "9876500010", "Thanks for helping me move yesterday, really appreciate it"),
    )

    /** Second round: more phrasings of every family, written after the first fixes (to catch over-fitting). */
    val moreTransactions: List<Case> = listOf(
        c(TRANSACTION, "VM-ORBITB-S", "A/c *7788 debited INR 349.00 on 14-09-26 for AUTOPAY to StreamFlix. Avl bal INR 8,020.55"),
        c(TRANSACTION, "JD-ORBITB-T", "INR 7,500 deposited in A/c XX0091 by cash at branch on 13-Sep. Available balance INR 19,340."),
        c(TRANSACTION, "AX-PAYLTR-S", "Your PayLater bill of Rs 2,340 is due on 05-Oct. Pay now to avoid late fees: pyl.in/p"),
        c(TRANSACTION, "VM-ORBITB-S", "Dear customer, auto debit mandate for Rs 999 per month towards GymFit has been registered on your A/c XX0091."),
        c(TRANSACTION, "BZ-CABGOO-T", "Thanks for riding with CabGo! Trip fare Rs 245 paid via wallet. Invoice: cbg.in/inv/8812"),
        c(TRANSACTION, "VM-FOODLY-S", "Your order has been placed! Estimated delivery in 32 mins. Track your order in the app."),
        c(TRANSACTION, "VM-KARGOX-S", "Shipment KX5566 delivered successfully. Delivered to: Security guard. Not received? Call 18000000002"),
        c(TRANSACTION, "JD-ZYNTRA-S", "Zyntra: Your parcel is out for delivery with our agent Manoj. OTP for delivery will be shared shortly."),
        c(TRANSACTION, "VM-SHOPKT-S", "Item(s) from your order OD55443322 have been dispatched and will be delivered by 24-Sep. Track: shk.it/t/55"),
        c(TRANSACTION, "AD-FRESHO-S", "Hi! Your Fresho order #FR-22113 has been delivered. How was your experience? Tell us: frsh.in/fb/22113 Rate 1-5."),
        c(TRANSACTION, "VM-RAILBK-S", "Ticket cancelled for PNR 3344556677. Refund of Rs 1,150 will be credited to your account in 3-5 working days."),
        c(TRANSACTION, "JD-SKYAIR-S", "Boarding pass for flight SA-778 DEL-BLR on 21-Sep is ready: skyair.in/bp/ABCD12. Gate closes 25 mins before departure."),
        c(TRANSACTION, "VM-CLINIQ-T", "Reminder: your appointment at Care Clinic is tomorrow at 10:30 AM. Reply Y to confirm."),
        c(TRANSACTION, "JM-TELCOX-S", "Your number 98XXXXX210 has been ported successfully. Welcome to TelcoX!"),
        c(TRANSACTION, "VK-TELCOX-T", "Dear customer, your daily data limit of 2GB has been exhausted. Recharge with a data add-on to continue browsing."),
        c(TRANSACTION, "AD-DTHXXX-S", "Your DTH balance is low: Rs 45. Your service will be deactivated on 18-Sep. Recharge to continue watching."),
        c(TRANSACTION, "VM-INSURX-S", "Claim no CL5566 for your health policy has been approved. Rs 42,000 will be paid to the hospital."),
        c(TRANSACTION, "JD-MUTUAL-T", "Redemption of 120.5 units in Growth Fund processed. Rs 6,210 will be credited to your bank a/c XX0091."),
        c(TRANSACTION, "VM-SOCIET-S", "Maintenance bill of Rs 3,200 for Oct is generated for Flat 302. Due date 10-Oct."),
        c(TRANSACTION, "VM-GASBKG-S", "Your LPG refill booking no 7788 is confirmed. Delivery expected within 2 days. Cash memo 55667."),
        c(TRANSACTION, "JD-EVCHRG-T", "Charging session ended at Station 12. Energy 18.2 kWh, amount Rs 364 deducted from wallet."),
        c(TRANSACTION, "VM-FASTAG-S", "Toll of Rs 115 paid via FASTag for vehicle MH12AB1234 at Plaza Khed on 12-Sep. Bal Rs 432."),
        c(TRANSACTION, "AX-QBANKX-S", "Your Quill Bank Debit Card will expire on 30-Sep-26. A new card has been sent to your registered address."),
        c(TRANSACTION, "VM-NIMBUS-S", "Your fixed deposit no 556677 of Rs 1,00,000 has matured and been renewed for 1 year at 7.1%."),
        c(TRANSACTION, "JD-NIMBUS-S", "Your request for a new cheque book (25 leaves) has been received and will be dispatched within 7 working days."),
        c(TRANSACTION, "VM-GOVPOR-G", "Your grievance no DOPAR/E/2026/0001 has been disposed of. View the reply at the public grievance portal."),
        c(TRANSACTION, "JD-EPFOXX-G", "Your PF contribution of Rs 3,600 for Aug-2026 has been received. Available balance Rs 1,42,300."),
        c(TRANSACTION, "VM-POSTAL-G", "Your speed post article EB556677889IN has been dispatched from Mumbai NSH and is in transit."),
        c(TRANSACTION, "VM-MUNCPL-G", "Property tax of Rs 4,210 for 2026-27 received for property ID 7788/12. Receipt no PT99887."),
    )

    val morePromotionsAndScams: List<Case> = listOf(
        c(PROMOTION, "VK-FOODLY-P", "Craving pizza? Your favourite Pizza Hub is now on Foodly with 60% off. Order now: foodly.in/ph"),
        c(PROMOTION, "BP-QBANKX-P", "Get a Quill Bank credit card with lifetime free membership and 10% cashback on groceries. Apply: qbk.in/cc"),
        c(PROMOTION, "VK-TELCOX-P", "Double data offer! Recharge with Rs 349 and get 3GB/day for 28 days. Valid till 30-Sep."),
        c(PROMOTION, "BP-GROCIT-P", "Free delivery on your first 3 orders! Plus Rs 100 off with code NEW100. Shop now."),
        c(PROMOTION, "VK-INSURX-P", "Secure your family with a term plan of Rs 1 crore at just Rs 490/month. Get a free quote: isx.in/term"),
        c(PROMOTION, "BZ-TRNDZY-P", "End of season sale: extra 20% off on top brands. Hurry, sale ends tonight!"),
        c(PROMOTION, "VK-SHOPKT-P", "Rs 250 has been credited to your ShopKart rewards! Use it before it expires on 30-Sep. Shop now."),
        c(PROMOTION, "VK-HOTELY-P", "Weekend getaway? Flat 30% off on resorts near you. Book now: htly.in/w"),
        c(PROMOTION, "AD-GYMFIT", "New batch of yoga classes starting Monday! Join today and get 1 month free. Call 9000000001"),
        c(PROMOTION, "VK-CARSXX-P", "Test drive the all new SUV this weekend and win assured gifts. Register now: crs.in/td"),
        // Numeric (6-digit) headers are promotional-only, whatever the text looks like.
        c(PROMOTION, "VM-612345", "Home loans at rates starting 8.4% p.a. Quick approval, minimal paperwork. Call 9000000002"),
        c(PROMOTION, "BZ-600111", "Rs 200 credited to your rewards wallet on your first purchase this week at Mega Mart."),
        c(SPAM, "9876501251", "Hi dear, I am Neha from an online trading group. Invest Rs 10,000 and get Rs 50,000 in 7 days. Join: t.me/fastprofit"),
        c(SPAM, "+919876501252", "Your electricity bill is not updated. Your power will be disconnected at 9:30 PM tonight. Contact 9876500094"),
        c(SPAM, "9876501253", "Dear SBI user, your YONO account will be blocked today. Update PAN now: sbi-yono-kyc.top"),
        c(SPAM, "9876501254", "Your Amazon account has been locked due to suspicious activity. Verify here: amaz0n-verify.xyz/login"),
        c(SPAM, "+919876501255", "You have a pending refund of Rs 4,999. Click the link and enter your card details to receive it: refund-now.click/r"),
        c(SPAM, "9876501256", "FedEx: Your international parcel is held at customs. Pay clearance charges of Rs 1,850 to release it."),
        c(SPAM, "AB-OFFERX", "Congratulations! Your number won Rs 10 lakh in the mobile lucky draw. Send your bank details to claim."),
        c(SPAM, "9876501257", "Aapka loan Rs 3,00,000 approve ho gaya hai. Processing fee Rs 999 bhejein aur paisa turant paayein."),
        c(SPAM, "+919876501258", "Mujhe galti se aapke number pe Rs 5,000 bhej diye, please wapas kar do. Ye mera number hai."),
    )

    val morePersonal: List<Case> = listOf(
        c(PERSONAL, "+919876500001", "Reached home safely. Thanks for dinner!"),
        c(PERSONAL, "9876500002", "Bhai 2000 transfer kar de, kal wapas kar dunga"),
        c(PERSONAL, "+919876500003", "Your cake order is ready, come pick it up after 6. - Aunty"),
        c(PERSONAL, "9876500004", "Meeting shifted to 4 pm. Please share the updated deck before that."),
        c(PERSONAL, "+919876500011", "Hello sir, I am your Kargo delivery partner. I will reach your address in 10 minutes, please be available."),
        c(PERSONAL, "9876500012", "Hi, is the flat on the 3rd floor still available for rent? I saw your ad."),
        c(PERSONAL, "+919876500001", "Did you book the tickets for Saturday? Mujhe bhi bata dena"),
        c(PERSONAL, "9876500002", "मैंने पैसे भेज दिए हैं, चेक कर लेना"),
        c(PERSONAL, "+919876500003", "lol that video was hilarious 😂"),
        c(PERSONAL, "9876500013", "Hi, this is Dr. Mehta's assistant. Doctor is running 20 mins late today, sorry for the wait."),
    )

    /**
     * Bodies whose SPAM verdict rests on India's "businesses never send from a private number" rule (a bank alert or a
     * return request from an unknown mobile); elsewhere banks and services do use long codes.
     */
    val INDIA_ONLY: Set<String> = setOf(
        scams[0].body, scams[1].body, morePromotionsAndScams.last().body,
    )

    val all: List<Case> =
        otp + bankTransactions + bankService + logistics + invoicesAndBills + bookings + telecom + government + promotions + scams +
            personal + moreTransactions + morePromotionsAndScams + morePersonal

    /** Group name per case, for the per-group report. */
    val groups: List<Pair<String, List<Case>>> = listOf(
        "otp" to otp, "bank-transactions" to bankTransactions, "bank-service" to bankService, "logistics" to logistics,
        "invoices-bills" to invoicesAndBills, "bookings" to bookings, "telecom" to telecom, "government" to government,
        "promotions" to promotions, "scams" to scams, "personal" to personal, "more-transactions" to moreTransactions,
        "more-promotions-scams" to morePromotionsAndScams, "more-personal" to morePersonal,
    )
}
