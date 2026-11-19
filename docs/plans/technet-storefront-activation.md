# TechNet storefront, kit activation, and recovery

## Store and orders

Keep pisophone.pages.dev on Cloudflare Pages. Anyone can browse the catalog. Use Supabase Auth for Google sign-in and email one-time codes, and Supabase Postgres for orders, kit ownership, entitlements, and hardware assignments.

Sell six products: DIY kits and assembled systems for Full TechNet Hub, PisoWiFi only, and PisoPhone only. DIY kits contain prepared, paired parts for customer assembly. Complete systems arrive assembled and tested. Include a router in all editions. Offer a rental phone as an optional paid add-on for assembled Full Hub and PisoPhone systems. Show contents, PHP prices, add-on subtotal, and preparation lead time before ordering. Accept made-to-order purchases. The seller will provide product photos and catalog details.

Require sign-in to order. Offer shipping and local pickup. For shipping, collect the delivery address and let the seller enter a quote before payment. Email the buyer a link to the signed-in order page with the final amount, order reference, and GCash, Maya, and bank transfer instructions. Collect the payment transaction reference and require seller verification before fulfillment. Give the seller a private area to manage products, quote delivery, verify payments, record direct sales, and handle ownership and hardware changes.

## Workshop and first activation

Extend the technician setup program to configure the ordered edition and defaults, pair the router and ESP32, and test the coin acceptor and a paid session for each included product. Clear test data before shipment. Register one kit record per unit for both website and direct sales. Match the edition, router, ESP32, and printed claim card at handover. Each kit includes one entitlement for one installation. An additional working installation requires another kit or complete system.

The operator connects power and the router WAN, opens the website on their phone, signs in, and enters the one-time code packed with the kit. This claims the kit and activates its purchased edition. The claiming operator can differ from the buyer. Keep order and payment details visible only to the buyer and authorized staff. Handle concurrent code redemption atomically. A repeat visit by the owner resumes setup.

The kit reports activation and health through outbound check-in from the supplied router. Issue a signed permanent entitlement bound to the kit, assigned hardware, and edition. Verify and persist it locally before allowing paid operation. Show whether the site accepted the code, is waiting for hardware, is waiting for first phone enrollment, or has confirmed readiness. Use the existing local QR enrollment flow for Full Hub and PisoPhone, and confirm the first rental phone is paired and online. For PisoWiFi and Full Hub, verify paid Wi-Fi and the coin box. Provide a private phone network without paid public Wi-Fi on PisoPhone-only routers. Keep daily administration and subsequent enrollment in the local dashboard.

## Repair, ownership, and license enforcement

- Gate the official browser flasher and new-kit installation images behind authenticated kit ownership and current hardware assignment. On a supported laptop browser, identify the connected ESP32 before the first write. Refuse unrelated boards. Verify device-side license enforcement independently of the website check.
- Link replacement boards sold or supplied under warranty to the original kit before shipping. Allow the operator to reflash the original or install the assigned replacement from their account, including when the previous board is dead.
- Supply replacement routers with OpenWrt and a TechNet setup service already installed. Assign the router to the kit before shipping. The operator connects it and completes software installation and pairing from a phone browser.
- Restore the edition and purchased phone slots on replacement. Record paid slots against the kit at issuance so recovery works with dead hardware. Keep two included slots for phone editions and the existing limit of twelve. Operators configure shop settings and pair phones again after complete loss.
- Let an activated kit run indefinitely during licensing outages. Disable a replaced device's paid service when it next reaches licensing. A replaced device can continue operating while offline.
- Route missing or disputed claim codes through seller review. Require seller approval to transfer a kit, its paid slots, and repair access to a resale buyer. Retire a fully refunded kit's entitlement and repair access after the returned hardware is received. Staff handle edition upgrades.
- Preserve legacy installation and update paths for existing shops. Keep new-kit installation images private. Verify that legacy recovery images cannot bypass new-kit licensing on the shipping hardware.

## Pilot and acceptance

Qualify one physical router and ESP32 combination for all three editions. Record exact board IDs, flash layout, and firmware versions. First prove the browser can recognize the original and assigned blank replacement before writing and rejects an unrelated board of the same model. Verify shipping firmware signing, device-side licensing, and behavior on legacy recovery.

Run each edition from order through the first real coin-funded session. Cover a multi-kit order, direct-sale gift claim, concurrent claims, interrupted flashing and pairing, offline restart, warranty replacement, phone-only router recovery, paid-slot recovery, seller-assisted transfer, and return after activation. Verify buyer, operator, and staff access separately. Verify that an existing shop can update without losing paid time, vouchers, phone pairings, or slots. Test restoration of ownership and slot records from backup.

## Open decisions and launch inputs

- Decide whether operators purchase additional phone slots through the signed-in kit page or buy directly and receive a credit from the seller.
- Set rules for expiring shipping quotes, short payments, duplicate transaction references, and made-to-order cancellations. Payment approval should verify the amount and destination.
- Supply prices, box contents, lead times, pickup instructions, payment account details, photos, and the exact pilot hardware. Confirm the actual pilot's ability to enforce device identity and signed firmware before accepting orders for new kits.
