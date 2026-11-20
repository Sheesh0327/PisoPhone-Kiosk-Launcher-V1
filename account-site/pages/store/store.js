import { accountApi, supabase } from "../auth.js";

const el = (id) => document.getElementById(id);
const php = new Intl.NumberFormat("en-PH", { style: "currency", currency: "PHP" });
const money = (cents) => php.format(cents / 100);
const checkout = el("checkout-form");
const cart = new Map();
const cartStorage = "pisophone-preview-cart";
const returnStorage = "pisophone-preview-store-return";
const statusNames = {
    needs_quote: "Waiting for shipping quote",
    awaiting_payment: "Awaiting test payment reference",
    payment_review: "Payment under review",
    paid: "Test payment verified"
};
const methods = { gcash: "GCash", maya: "Maya", bank: "Bank transfer" };
let session;
let seller = false;
let requestId = crypto.randomUUID();
let submitting = false;
let authRevision = 0;
let ordersRevision = 0;

function rememberCart() {
    const selection = [...cart.entries()].map(([sku, { quantity, phone_quantity }]) => ({ sku, quantity, phone_quantity }));
    sessionStorage.setItem(cartStorage, JSON.stringify(selection));
}

function restoreCart(products) {
    const saved = JSON.parse(sessionStorage.getItem(cartStorage) ?? "[]");
    for (const product of products) {
        const item = saved.find(({ sku }) => sku === product.sku);
        if (!item || !Number.isInteger(item.quantity) || item.quantity < 1 || item.quantity > 10) continue;
        const phones = product.phone_price && Number.isInteger(item.phone_quantity)
            ? Math.min(item.quantity, Math.max(0, item.phone_quantity)) : 0;
        cart.set(product.sku, { product, quantity: item.quantity, phone_quantity: phones });
    }
}

function node(tag, text, attrs = {}) {
    const result = document.createElement(tag);
    if (text) result.textContent = text;
    for (const [key, value] of Object.entries(attrs)) result.setAttribute(key, value);
    return result;
}

function field(label, input) {
    const wrapper = node("label", label, { class: "field" });
    wrapper.append(input);
    return wrapper;
}

function numberInput(name, min, max, value, step = 1) {
    return node("input", "", { type: "number", name, min, max, step, value, required: "" });
}

function renderCart() {
    el("cart").replaceChildren(...Array.from(cart.values(), ({ product, quantity, phone_quantity }) =>
        node("p", `${quantity} Ã— ${product.name} ${product.format === "kit" ? "DIY kit" : "assembled"}${phone_quantity ? ` + ${phone_quantity} rental phone(s)` : ""}`)));
    el("cart-total").textContent = cart.size
        ? `Subtotal ${money(Array.from(cart.values()).reduce((sum, item) =>
            sum + item.product.price * item.quantity + (item.product.phone_price ?? 0) * item.phone_quantity, 0))}`
        : "Choose a product to get started.";
    el("place-order").disabled = !cart.size || !session || submitting;
}

function productCard(product) {
    const card = node("article", "", { class: "card product" });
    card.append(node("p", product.format === "kit" ? "DIY kit" : "Assembled system", { class: "format" }),
        node("h3", product.name), node("p", product.contents), node("p", money(product.price), { class: "price" }),
        node("p", `Made to order Â· ${product.lead_days} day(s) to prepare`, { class: "metadata" }));
    if (product.phone_price) card.append(node("p", `Optional rental phone ${money(product.phone_price)}`, { class: "metadata" }));

    const controls = node("form", "", { class: "product-controls" });
    const initial = cart.get(product.sku);
    const qty = numberInput("quantity", 1, 10, initial?.quantity ?? 1);
    const phoneQty = numberInput("phones", 0, qty.value, initial?.phone_quantity ?? 0);
    const add = node("button", initial ? "Update order" : "Add to order", { type: "submit", class: "btn" });
    const remove = node("button", "Remove", { type: "button", class: "btn" });
    remove.hidden = !initial;
    qty.addEventListener("input", () => { phoneQty.max = qty.value; });
    controls.append(field("Quantity", qty));
    if (product.phone_price) controls.append(field("Rental phones", phoneQty));
    controls.append(add, remove);
    controls.addEventListener("submit", (event) => {
        event.preventDefault();
        cart.set(product.sku, { product, quantity: qty.valueAsNumber, phone_quantity: product.phone_price ? phoneQty.valueAsNumber : 0 });
        requestId = crypto.randomUUID();
        rememberCart();
        add.textContent = "Update order";
        remove.hidden = false;
        renderCart();
    });
    remove.addEventListener("click", () => {
        cart.delete(product.sku);
        requestId = crypto.randomUUID();
        rememberCart();
        add.textContent = "Add to order";
        remove.hidden = true;
        renderCart();
    });
    card.append(controls);
    return card;
}

async function loadCatalog(revision = authRevision) {
    const { data, error } = await supabase.from("store_products").select("*").order("sku");
    if (revision !== authRevision) return;
    el("catalog-status").textContent = error ? "The catalog is temporarily unavailable. Reload to try again." : "";
    if (error) return;

    const published = data.filter((product) => product.published);
    cart.clear();
    try {
        restoreCart(published);
    } catch {
        sessionStorage.removeItem(cartStorage);
    }
    el("products").replaceChildren(...published.map(productCard));
    el("product-editor").replaceChildren(...(seller ? data.map(productEditor) : []));
    renderCart();
}

function rpcError(error) {
    return error?.code === "22023" ? error.message : "Could not save this change. Reload to try again.";
}

async function placeOrder(event) {
    event.preventDefault();
    if (!session || !cart.size || submitting) return;
    submitting = true;
    renderCart();
    const form = new FormData(checkout);
    const direct = seller && form.has("direct");
    const { data, error } = await supabase.rpc("store_place_order", {
        p_request_id: requestId,
        p_items: [...cart.entries()].map(([sku, { quantity, phone_quantity }]) => ({ sku, quantity, phone_quantity })),
        p_delivery: form.get("delivery"), p_name: form.get("name"), p_address: form.get("address"), p_direct: direct
    });
    submitting = false;
    if (error) el("checkout-status").textContent = rpcError(error);
    else {
        requestId = crypto.randomUUID();
        cart.clear();
        rememberCart();
        checkout.reset();
        updateDelivery();
        el("checkout-status").textContent = direct ? "Direct sale recorded." : "Test order placed.";
        await Promise.all([loadCatalog(), loadOrders()]);
        document.getElementById(`${direct ? "seller" : "buyer"}-${data}`)?.scrollIntoView({ block: "center" });
    }
    renderCart();
}

function updateDelivery() {
    const shipping = el("delivery").value === "shipping";
    el("address-field").hidden = !shipping;
    el("address").required = shipping;
}

function actionForm(buttonText, inputs, run, status, after) {
    const form = node("form");
    const submit = node("button", buttonText, { class: "btn", type: "submit" });
    form.append(...inputs, submit);
    form.addEventListener("submit", async (event) => {
        event.preventDefault();
        submit.disabled = true;
        status.textContent = "Savingâ€¦";
        try {
            const { error } = await run(new FormData(form));
            status.textContent = error ? rpcError(error) : "Saved.";
            if (!error) await after();
        } catch {
            status.textContent = "The service is unavailable. Try again.";
        } finally {
            submit.disabled = false;
        }
    });
    return form;
}

function productEditor(product) {
    const card = node("article", "", { class: "card order-card" });
    const status = node("p", "", { role: "status" });
    const description = node("textarea", product.contents, { name: "contents", maxlength: 500, rows: 2, required: "" });
    const listed = node("input", "", { type: "checkbox", name: "published" });
    listed.checked = product.published;
    const inputs = [
        field("Sample price in PHP", numberInput("price", 0.01, 100000, product.price / 100, 0.01)),
        field("Preparation days", numberInput("days", 1, 365, product.lead_days)),
        field("Box contents", description),
        field("Published in test catalog", listed)
    ];
    const eligible = product.format === "assembled" && product.edition !== "wifi";
    if (eligible) inputs.push(field("Phone add-on in PHP (0 to remove)",
        numberInput("phone", 0, 100000, (product.phone_price ?? 0) / 100, 0.01)));
    card.append(node("h3", `${product.name} Â· ${product.format}`), status,
        actionForm("Save sample listing", inputs, (form) => supabase.rpc("store_seller_product", {
            p_sku: product.sku, p_price: Math.round(Number(form.get("price")) * 100),
            p_phone_price: eligible && Number(form.get("phone")) > 0 ? Math.round(Number(form.get("phone")) * 100) : null,
            p_lead_days: Number(form.get("days")), p_contents: form.get("contents"), p_published: form.has("published")
        }), status, () => loadCatalog()));
    return card;
}

function paymentForm(order, status) {
    const select = node("select", "", { name: "method" });
    for (const [value, name] of Object.entries(methods)) select.append(node("option", name, { value }));
    return actionForm("Submit test reference", [
        field("Payment method", select),
        field("Test reference", node("input", "", { name: "reference", minlength: 4, maxlength: 80, required: "" }))
    ], (form) => supabase.rpc("store_submit_payment", {
        p_order_id: order.id, p_method: form.get("method"), p_reference: form.get("reference")
    }), status, loadOrders);
}

async function sendNotice(orderId) {
    const { data } = await supabase.auth.getSession();
    if (!data.session) return false;
    try {
        const response = await fetch(`${accountApi}/api/store/quote-email/${orderId}`, {
            method: "POST", headers: { Authorization: `Bearer ${data.session.access_token}` }
        });
        return response.ok;
    } catch {
        return false;
    }
}

function sellerControls(card, order, notice, status) {
    if (order.status === "needs_quote") {
        card.append(actionForm("Save shipping quote", [
            field("Sample shipping charge in PHP", numberInput("shipping", 0, 100000, "", 0.01))
        ], (form) => supabase.rpc("store_seller_quote", {
            p_order_id: order.id, p_shipping: Math.round(Number(form.get("shipping")) * 100)
        }), status, async () => {
            if (order.source === "web") await sendNotice(order.id);
            await loadOrders();
        }));
    }
    if (notice) {
        card.append(node("small", `Quote email: ${notice.status}`));
        if (notice.status !== "sent") card.append(actionForm("Send or retry quote email", [], async () => ({
            error: await sendNotice(order.id) ? null : { code: "22023", message: "Email is unavailable or still sending. The quote is visible to the buyer." }
        }), status, loadOrders));
    }
    if (order.status === "payment_review") {
        const confirmed = node("input", "", { type: "checkbox", required: "" });
        const confirmation = node("label", "I checked the test reference and destination.", { class: "check-line" });
        confirmation.prepend(confirmed);
        card.append(actionForm("Verify test payment", [
            field("Verified amount in PHP", numberInput("amount", 0, Number(order.total) / 100, "", 0.01)),
            confirmation
        ], (form) => supabase.rpc("store_seller_verify", {
            p_order_id: order.id, p_confirmed_amount: Math.round(Number(form.get("amount")) * 100)
        }), status, loadOrders));
    }
}

function orderCard(order, staff, notice) {
    const prefix = staff ? "seller" : "buyer";
    const status = node("p", "", { role: "status" });
    const card = node("article", "", { class: "card order-card", id: `${prefix}-${order.id}` });
    const products = node("ul");
    for (const line of order.store_order_lines) products.append(node("li",
        `${line.quantity} Ã— ${line.name} (${line.sku.endsWith("-kit") ? "DIY kit" : "assembled"}) at ${money(line.price)}${line.phone_quantity ? ` Â· ${line.phone_quantity} rental phone(s) at ${money(line.phone_price)}` : ""}`));
    card.append(node("p", `${order.source === "direct" ? "Direct sale" : "Web order"} Â· ${order.delivery}`, { class: "order-label" }),
        node("h3", statusNames[order.status]), node("small", `Order reference ${order.id}`), products,
        node("p", `Subtotal ${money(order.subtotal)}`));
    if (staff) card.append(node("p", `Buyer ${order.buyer_name}`));
    if (order.address) card.append(node("p", `Test address ${order.address}`));
    if (order.shipping !== null) card.append(node("p", `Shipping ${money(order.shipping)} Â· Total ${money(order.total)}`));
    if (order.payment_reference) card.append(node("p", `${methods[order.payment_method]} Â· ${order.payment_reference}`));
    card.append(status);

    if (order.status === "awaiting_payment" && (!staff || order.source === "direct")) {
        if (order.delivery === "pickup") card.append(node("p", "Pickup details will be provided by the seller."));
        card.append(node("p", "Use a sample GCash, Maya, or bank reference. This preview has no payment destination."),
            paymentForm(order, status));
    }
    if (staff) sellerControls(card, order, notice, status);
    return card;
}

async function loadOrders() {
    const revision = ++ordersRevision;
    if (!session) return;
    const [orders, notices] = await Promise.all([
        supabase.from("store_orders").select("*,store_order_lines(*)").order("created_at", { ascending: false }),
        seller ? supabase.from("store_quote_notices").select("order_id,status") : Promise.resolve({ data: [] })
    ]);
    if (revision !== ordersRevision) return;
    const data = orders.data ?? [];
    const mine = data.filter(({ buyer_id }) => buyer_id === session.user.id);
    el("orders-status").textContent = orders.error ? "Orders could not be loaded." : mine.length ? "" : "No orders yet.";
    el("order-list").replaceChildren(...mine.map((order) => orderCard(order, false)));
    if (seller) {
        const byOrder = new Map((notices.data ?? []).map((notice) => [notice.order_id, notice]));
        el("seller-status").textContent = orders.error ? "Orders could not be loaded." :
            notices.error ? "Email statuses could not be loaded." : data.length ? "" : "No orders yet.";
        el("seller-orders").replaceChildren(...data.map((order) => orderCard(order, true, byOrder.get(order.id))));
    }
}

async function showSession(next, revision) {
    if (revision !== authRevision) return;
    session = next;
    seller = false;
    if (session) {
        const { data, error } = await supabase.rpc("store_is_seller");
        if (revision !== authRevision) return;
        seller = !error && data === true;
    }
    el("checkout-form").hidden = !session;
    el("sign-in").hidden = Boolean(session);
    el("orders").hidden = !session;
    el("orders-link").hidden = !session;
    el("direct-field").hidden = !seller;
    el("seller").hidden = !seller;
    renderCart();
    await Promise.all([loadCatalog(revision), loadOrders()]);
    if (revision !== authRevision) return;
    const target = new URLSearchParams(location.search).get("order");
    if (target) document.getElementById(`buyer-${target}`)?.scrollIntoView({ block: "center" });
}

el("sign-in").addEventListener("click", () => {
    const orderId = new URLSearchParams(location.search).get("order");
    sessionStorage.setItem(returnStorage, orderId && /^[0-9a-f-]{36}$/i.test(orderId) ? orderId : "store");
});
checkout.addEventListener("submit", placeOrder);
el("delivery").addEventListener("change", updateDelivery);
supabase.auth.onAuthStateChange((_event, next) => {
    if (next && session?.user.id === next.user.id) {
        session = next;
        return;
    }
    el("orders").hidden = true;
    el("seller").hidden = true;
    el("checkout-form").hidden = true;
    const revision = ++authRevision;
    ++ordersRevision;
    setTimeout(() => showSession(next, revision), 0);
});
