// The last part of portal-core.js (after the part of the product, so the polls start in the order they always did).
pollTimers.push(setInterval(fetchDeviceStatus, 3000));
document.addEventListener("DOMContentLoaded", fetchDeviceStatus);
