import { accountApi, supabase } from "../auth.js";

const el = (id) => document.getElementById(id);
const php = new Intl.NumberFormat("en-PH", { style: "currency", currency: "PHP" });
const money = (cents) => php.format(cents / 100);
const orderDate = new Intl.DateTimeFormat("en-PH", { dateStyle: "medium" });
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
        node("p", `${quantity} × ${product.name} ${product.format === "kit" ? "DIY kit" : "assembled"}${phone_quantity ? ` + ${phone_quantity} rental phone(s)` : ""}`)));
    el("cart-total").textContent = cart.size
        ? `Subtotal ${money(Array.from(cart.values()).reduce((sum, item) =>
            sum + item.product.price * item.quantity + (item.product.phone_price ?? 0) * item.phone_quantity, 0))}`
        : "Choose a product to get started.";
    el("place-order").disabled = !cart.size || !session || submitting;
}

const productPhotos = supabase.storage.from("store-product-photos");
const photoExtensions = { "image/jpeg": "jpg", "image/png": "png", "image/webp": "webp" };
const photoLimit = 5 * 1024 * 1024;

function productPhoto(product, variant) {
    const frame = node("div", "", { class: `product-photo photo-${variant}`, "data-photo-sku": product.sku });
    showPhoto(frame, product);
    return frame;
}

function showPhoto(frame, product) {
    frame.replaceChildren();
    frame.hidden = !product.image_path;
    if (!product.image_path) return;
    const { data } = productPhotos.getPublicUrl(product.image_path);
    const img = node("img", "", {
        src: data.publicUrl, alt: `${product.name} · ${listingFormat(product)}`,
        loading: "lazy", decoding: "async"
    });
    img.addEventListener("error", () => { frame.hidden = true; });
    frame.append(img);
}

function listingPhotoForm(product) {
    const form = node("form", "", { class: "listing-photo-form" });
    const file = node("input", "", { type: "file", accept: Object.keys(photoExtensions).join(","), required: "" });
    const upload = node("button", product.image_path ? "Replace photo" : "Upload photo", { class: "btn", type: "submit" });
    const remove = node("button", "Remove photo", { class: "btn", type: "button" });
    const status = node("p", "", { role: "status" });
    const help = node("small", "JPG, PNG, or WebP · up to 5 MB", { id: `photo-help-${product.sku}` });
    file.setAttribute("aria-describedby", help.id);
    const controls = node("div", "", { class: "photo-buttons" });
    controls.append(upload, remove);
    remove.hidden = !product.image_path;
    form.append(field("Product photo", file), help, controls, status);

    async function changePhoto(nextFile) {
        const previous = product.image_path ?? null;
        let path = null;
        upload.disabled = remove.disabled = file.disabled = true;
        status.textContent = nextFile ? "Uploading photo…" : "Removing photo…";
        try {
            if (nextFile) {
                const ext = photoExtensions[nextFile.type];
                if (!ext || nextFile.size < 1 || nextFile.size > photoLimit) {
                    status.textContent = "Choose a JPG, PNG, or WebP file up to 5 MB.";
                    return;
                }
                path = `${product.sku}/${crypto.randomUUID()}.${ext}`;
                const uploaded = await productPhotos.upload(path, nextFile, { contentType: nextFile.type });
                if (uploaded.error) {
                    status.textContent = "Upload failed. Try again.";
                    return;
                }
            }

            const { error } = await supabase.rpc("store_seller_photo", {
                p_sku: product.sku, p_previous: previous, p_path: path
            });
            if (error) {
                if (path && error.code === "22023") await productPhotos.remove([path]);
                status.textContent = rpcError(error);
                return;
            }

            product.image_path = path;
            for (const frame of document.querySelectorAll("[data-photo-sku]")) {
                if (frame.dataset.photoSku === product.sku) showPhoto(frame, product);
            }
            file.value = "";
            upload.textContent = path ? "Replace photo" : "Upload photo";
            remove.hidden = !path;
            status.textContent = path ? "Product photo saved." : "Product photo removed.";
            if (previous) await productPhotos.remove([previous]);
        } catch {
            status.textContent = "Could not complete the photo change. Reload the listing to check its current photo.";
        } finally {
            upload.disabled = remove.disabled = file.disabled = false;
        }
    }

    form.addEventListener("submit", (event) => {
        event.preventDefault();
        if (file.files[0]) changePhoto(file.files[0]);
    });
    remove.addEventListener("click", () => changePhoto(null));
    return form;
}

function productCard(product) {
    const card = node("article", "", { class: "card product" });
    card.append(productPhoto(product, "catalog"), node("p", listingFormat(product), { class: "format" }),
        node("h3", product.name), node("p", product.contents), node("p", money(product.price), { class: "price" }),
        node("p", `Made to order · ${product.lead_days} day(s) to prepare`, { class: "metadata" }));
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
        if (direct) {
            selectedOrderId = data;
            el("order-search").value = "";
            el("order-filter").value = "";
        }
        selectView(direct ? "seller" : "orders");
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
        status.textContent = "Saving…";
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

function listingFormat(product) {
    return product.format === "kit" ? "DIY kit" : "Assembled system";
}

function productEditor(product) {
    const card = node("article", "", { class: "card listing-card" });
    const header = node("div", "", { class: "card-heading" });
    const title = node("div");
    title.append(node("p", listingFormat(product), { class: "card-meta" }), node("h2", product.name));
    header.append(title, productPhoto(product, "thumbnail"), node("span", product.published ? "Listed" : "Hidden", {
        class: `order-status${product.published ? " paid" : ""}`
    }));

    const facts = node("dl", "", { class: "listing-facts" });
    facts.append(orderFact("Price", money(product.price)), orderFact("Lead time", `${product.lead_days} days`));
    if (product.phone_price) facts.append(orderFact("Rental phone", money(product.phone_price)));

    const details = node("details", "", { class: "listing-edit" });
    const status = node("p", "", { role: "status" });
    const description = node("textarea", product.contents, { name: "contents", maxlength: 500, rows: 2, required: "" });
    const listed = node("input", "", { type: "checkbox", name: "published" });
    listed.checked = product.published;
    const publishedLabel = node("label", "Show in catalog", { class: "check-line editor-wide" });
    publishedLabel.prepend(listed);

    const inputs = [
        field("Price (PHP)", numberInput("price", 0.01, 100000, product.price / 100, 0.01)),
        field("Lead time (days)", numberInput("days", 1, 365, product.lead_days)),
        field("Box contents", description)
    ];
    inputs[2].classList.add("editor-wide");
    const eligible = product.format === "assembled" && product.edition !== "wifi";
    if (eligible) {
        const phonePrice = field("Phone add-on (PHP)", numberInput("phone", 0, 100000, (product.phone_price ?? 0) / 100, 0.01));
        phonePrice.append(node("small", "Set to zero to remove the add-on."));
        phonePrice.classList.add("editor-wide");
        inputs.push(phonePrice);
    }
    inputs.push(publishedLabel);

    const form = actionForm("Save listing", inputs, (values) => supabase.rpc("store_seller_product", {
        p_sku: product.sku, p_price: Math.round(Number(values.get("price")) * 100),
        p_phone_price: eligible && Number(values.get("phone")) > 0 ? Math.round(Number(values.get("phone")) * 100) : null,
        p_lead_days: Number(values.get("days")), p_contents: values.get("contents"), p_published: values.has("published")
    }), status, async () => {
        await loadCatalog();
        el("listing-status").textContent = `${product.name} · ${listingFormat(product)} saved.`;
        el(`edit-${product.sku}`)?.focus();
    });
    const summary = node("summary", "Edit listing", { id: `edit-${product.sku}` });
    details.append(summary, listingPhotoForm(product), form, status);
    card.append(header, facts, details);
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
        card.append(node("small", `Quote email: ${notice.status}`, { class: "email-status" }));
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

function orderFact(label, value) {
    const entry = node("div");
    entry.append(node("dt", label), node("dd", value));
    return entry;
}

function orderCard(order, staff, notice) {
    const prefix = staff ? "seller" : "buyer";
    const status = node("p", "", { role: "status" });
    const card = node("article", "", { class: "card order-card", id: `${prefix}-${order.id}` });
    const header = node("div", "", { class: "card-heading order-heading" });
    const title = node("div");
    const date = orderDate.format(new Date(order.created_at));
    title.append(node("h3", `Order #${order.id.slice(0, 8)}`),
        node("p", `${order.delivery === "shipping" ? "Shipping" : "Pickup"} · ${date}${order.source === "direct" ? " · Direct sale" : ""}`, { class: "card-meta" }));
    header.append(title, node("span", statusNames[order.status], { class: `order-status ${order.status}` }));

    const lines = node("ul", "", { class: "order-items", "aria-label": "Items" });
    for (const line of order.store_order_lines) {
        const item = node("li");
        const description = node("div");
        description.append(node("strong", line.name), node("span",
            `${line.quantity} × ${line.sku.endsWith("-kit") ? "DIY kit" : "Assembled system"}`, { class: "card-meta" }));
        if (line.phone_quantity) description.append(node("span", `${line.phone_quantity} × rental phone`, { class: "card-meta" }));
        const amount = line.quantity * line.price + line.phone_quantity * line.phone_price;
        item.append(description, node("span", money(amount), { class: "item-cost" }));
        lines.append(item);
    }
    const totals = node("dl", "", { class: "order-totals" });
    if (order.shipping !== null && order.delivery === "shipping") {
        totals.append(orderFact("Subtotal", money(order.subtotal)), orderFact("Shipping", money(order.shipping)));
    }
    totals.append(orderFact(order.total === null ? "Subtotal" : "Total", money(order.total ?? order.subtotal)));

    const metadata = node("details", "", { class: "order-metadata" });
    const facts = node("dl", "", { class: "order-facts" });
    facts.append(orderFact("Full order reference", order.id));
    if (order.address) facts.append(orderFact("Test delivery address", order.address));
    if (order.payment_reference) facts.append(orderFact(methods[order.payment_method], order.payment_reference));
    metadata.append(node("summary", "Order details"), facts);
    if (staff) metadata.open = true;
    card.append(header, lines, totals, metadata);

    const actions = node("div", "", { class: "order-actions" });
    if (order.status === "awaiting_payment" && (!staff || order.source === "direct")) {
        if (order.delivery === "pickup") actions.append(node("small", "The seller will provide pickup details."));
        actions.append(paymentForm(order, status));
    }
    if (staff) sellerControls(actions, order, notice, status);
    if (actions.childElementCount) {
        actions.append(status);
        card.append(actions);
    }
    return card;
}

const filters = {
    needs_quote: "Needs shipping quote",
    awaiting_payment: "Awaiting reference",
    payment_review: "Payment review",
    paid: "Verified"
};
let sellerOrders = [];
let noticesByOrder = new Map();
let selectedOrderId;

function activeView() {
    const hash = location.hash.slice(1);
    if (hash === "catalog" || hash === "checkout") return "catalog";
    if (hash === "orders" || (!hash && new URLSearchParams(location.search).has("order"))) return session ? "orders" : "catalog";
    if (hash === "listings" && seller) return "listings";
    if (hash === "seller" || !hash) return seller ? "seller" : "catalog";
    return "catalog";
}

function renderView(scroll = false) {
    const view = activeView();
    el("shop-view").hidden = view !== "catalog";
    el("orders").hidden = view !== "orders";
    el("seller").hidden = view !== "seller";
    el("listings").hidden = view !== "listings";
    el("order-search-field").hidden = view !== "seller";
    for (const link of document.querySelectorAll(".store-nav [data-view]")) {
        if (link.dataset.view === view) link.setAttribute("aria-current", "page");
        else link.removeAttribute("aria-current");
    }
    if (scroll && location.hash === "#checkout") el("checkout").scrollIntoView({ block: "start" });
    else if (scroll) window.scrollTo(0, 0);
}

function selectView(view) {
    if (location.hash === `#${view}`) renderView();
    else location.hash = view;
}

function renderSellerSummary() {
    const counts = Object.fromEntries(Object.keys(filters).map((status) => [status, 0]));
    let paidTotal = 0;
    for (const order of sellerOrders) {
        counts[order.status] += 1;
        if (order.status === "paid") paidTotal += Number(order.total);
    }
    const metrics = [
        ["Test orders", sellerOrders.length],
        ["Needs action", counts.needs_quote + counts.payment_review],
        ["Awaiting reference", counts.awaiting_payment],
        ["Verified test payments", money(paidTotal)]
    ];
    el("seller-metrics").replaceChildren(...metrics.map(([caption, value]) => {
        const card = node("dl", "", { class: "card metric" });
        card.append(node("dt", caption), node("dd", String(value)));
        return card;
    }));
    el("seller-pipeline").replaceChildren(...Object.entries(filters).map(([status, label]) => {
        const row = node("div", "", { class: "pipeline-row" });
        row.append(node("span", label), node("meter", "", {
            min: 0, max: Math.max(sellerOrders.length, 1), value: counts[status], "aria-label": label
        }), node("strong", String(counts[status])));
        return row;
    }));
    const attention = ["needs_quote", "payment_review"].filter((status) => counts[status] > 0);
    el("seller-queue").replaceChildren(...(attention.length ? attention.map((status) => {
        const button = node("button", filters[status], { type: "button" });
        button.append(node("strong", String(counts[status])));
        button.addEventListener("click", () => {
            el("order-filter").value = status;
            el("order-search").value = "";
            renderSellerTable();
            el("order-filter").focus();
        });
        return button;
    }) : [node("p", "All caught up.")]));
}

function renderSellerTable() {
    const filter = el("order-filter").value;
    const query = el("order-search").value.trim().toLowerCase();
    const matching = sellerOrders.filter((order) => (!filter || order.status === filter)
        && (!query || [order.id, order.buyer_name, order.payment_reference].some((value) => value?.toLowerCase().includes(query))));
    const table = el("seller-orders");
    table.replaceChildren();
    if (!matching.length) {
        const row = node("tr");
        row.append(node("td", sellerOrders.length ? "No orders match these filters." : "No orders yet.",
            { colspan: "6", class: "table-empty" }));
        table.append(row);
    }

    for (const order of matching) {
        const expanded = selectedOrderId === order.id;
        const row = node("tr");
        const reference = node("td");
        reference.append(node("strong", `#${order.id.slice(0, 8)}`, { class: "order-ref", title: order.id }),
            node("time", orderDate.format(new Date(order.created_at)), { datetime: order.created_at }));
        const badge = node("td");
        badge.append(node("span", filters[order.status], { class: `order-status ${order.status}` }));
        const action = node("td");
        const button = node("button", expanded ? "Close" : "View", {
            type: "button", class: "btn", id: `open-${order.id}`,
            "aria-label": `${expanded ? "Close" : "View"} order ${order.id.slice(0, 8)}`,
            "aria-expanded": String(expanded)
        });
        button.addEventListener("click", () => {
            selectedOrderId = expanded ? undefined : order.id;
            renderSellerTable();
            el(button.id)?.focus();
        });
        action.append(button);
        row.append(reference, node("td", order.buyer_name), node("td", order.delivery === "shipping" ? "Shipping" : "Pickup"),
            badge, node("td", order.total === null ? "Pending quote" : money(order.total)), action);
        table.append(row);
        if (expanded) {
            const detail = node("tr", "", { class: "order-detail" });
            const content = node("td", "", { colspan: "6" });
            content.append(orderCard(order, true, noticesByOrder.get(order.id)));
            detail.append(content);
            table.append(detail);
        }
    }
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
        sellerOrders = data;
        noticesByOrder = new Map((notices.data ?? []).map((notice) => [notice.order_id, notice]));
        el("seller-status").textContent = orders.error ? "Orders could not be loaded." :
            notices.error ? "Email statuses could not be loaded." : "";
        renderSellerSummary();
        renderSellerTable();
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
    el("orders-link").hidden = !session;
    el("direct-field").hidden = !seller;
    el("seller-nav").hidden = !seller;
    el("account-initial").textContent = session?.user.email?.slice(0, 2).toUpperCase() ?? "TP";
    renderView();
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
window.addEventListener("hashchange", () => renderView(true));
el("order-filter").addEventListener("change", renderSellerTable);
el("order-search").addEventListener("input", renderSellerTable);
checkout.addEventListener("submit", placeOrder);
el("delivery").addEventListener("change", updateDelivery);
supabase.auth.onAuthStateChange((_event, next) => {
    if (next && session?.user.id === next.user.id) {
        session = next;
        return;
    }
    el("orders").hidden = true;
    el("seller").hidden = true;
    el("listings").hidden = true;
    el("seller-nav").hidden = true;
    el("order-search-field").hidden = true;
    sellerOrders = [];
    noticesByOrder.clear();
    selectedOrderId = undefined;
    el("order-search").value = "";
    el("checkout-form").hidden = true;
    const revision = ++authRevision;
    ++ordersRevision;
    setTimeout(() => showSession(next, revision), 0);
});
