document.addEventListener('DOMContentLoaded', () => {
    fetchStatus();
    setupForm();
});

async function fetchStatus() {
    try {
        const response = await fetch('/api/status');
        if (!response.ok) throw new Error('Network status failed');
        const data = await response.json();
        
        // Update global indicator
        document.getElementById('system_status').textContent = data.system_armed ? "Armed" : "Disarmed";
        document.getElementById('system_status').style.color = data.system_armed ? "var(--success)" : "var(--text-secondary)";

        // Populate fields in settings form
        document.getElementById('price').value = data.coin_price;
        document.getElementById('minutes').value = data.min_per_coin;
        document.getElementById('port').value = data.target_port;

        // Render dynamic slots grid (mocking structure if endpoints are not populated yet)
        renderSlotsGrid();
    } catch (err) {
        console.error('Error fetching system configs:', err);
    }
}

function renderSlotsGrid() {
    const container = document.getElementById('slots_container');
    container.innerHTML = ''; // clear loading state

    // 5 dynamic slots matching licenseSlots definition
    for (let i = 1; i <= 5; i++) {
        const div = document.createElement('div');
        div.className = 'slot-card';
        div.innerHTML = `
            <h3>Slot ${i}</h3>
            <p class="slot-ip">IP: Unassigned</p>
            <p class="slot-status">Inactive</p>
        `;
        container.appendChild(div);
    }
}

function setupForm() {
    const form = document.getElementById('config_form');
    const msg = document.getElementById('form_message');

    form.addEventListener('submit', async (e) => {
        e.preventDefault();
        msg.classList.add('hidden');

        const formData = new FormData();
        formData.append('price', document.getElementById('price').value);
        formData.append('min_per_coin', document.getElementById('minutes').value);
        formData.append('port', document.getElementById('port').value);
        formData.append('password', document.getElementById('password').value);

        try {
            const resp = await fetch('/api/config', {
                method: 'POST',
                body: formData
            });

            const resData = await resp.json();
            if (resp.status === 200) {
                msg.textContent = "✓ Configuration saved successfully!";
                msg.className = "alert success";
                msg.classList.remove('hidden');
                document.getElementById('password').value = ''; // clear pwd field
            } else {
                throw new Error(resData.error || 'Server error saving configuration');
            }
        } catch (err) {
            msg.textContent = `❌ Error: ${err.message}`;
            msg.className = "alert error";
            msg.classList.remove('hidden');
        }
    });
}
