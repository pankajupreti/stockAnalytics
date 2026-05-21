const tokenKey = "access_token";
const refreshTokenKey = "refresh_token";
let currentPage = 0;
let refreshTimeoutId = null;

// Anchor date state
let currentAnchorDate = null;
let anchorPricesCache = {};  // Cache anchor prices for current session

// --- Login ---
document.addEventListener("DOMContentLoaded", function () {
    const loginBtn = document.getElementById("login-btn");
    if (loginBtn) {
        loginBtn.addEventListener("click", function () {
            window.location.href = "/oauth-service/oauth2/authorization/google";
        });
    }
});

// --- Logout with token revocation ---
document.getElementById("logout-btn")?.addEventListener("click", async () => {
    const refreshToken = localStorage.getItem(refreshTokenKey);

    // Cancel any scheduled refresh
    if (refreshTimeoutId) {
        clearTimeout(refreshTimeoutId);
        refreshTimeoutId = null;
    }

    // Revoke token on server
    if (refreshToken) {
        try {
            await fetch("/oauth-service/token/revoke", {
                method: "POST",
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify({ refreshToken: refreshToken })
            });
        } catch (e) {
            console.log("Token revoke failed (may already be revoked):", e);
        }
    }

    // Clear local storage
    localStorage.removeItem(tokenKey);
    localStorage.removeItem(refreshTokenKey);
    currentPage = 0;

    document.getElementById("dashboard-section").style.display = "none";
    document.getElementById("login-section").style.display = "block";
});

// NOTE: isTokenExpired, refreshAccessToken, scheduleTokenRefresh are now in token-utils.js
// Using the shared versions from TokenUtils to avoid duplication

// --- Autocomplete / Auto-search functionality ---
let searchDebounceTimer = null;
let selectedDropdownIndex = -1;

function initSearchAutocomplete() {
    const searchInput = document.getElementById("search");
    const dropdown = document.getElementById("search-dropdown");

    if (!searchInput || !dropdown) return;

    // Handle input changes with debounce
    searchInput.addEventListener("input", (e) => {
        const query = e.target.value.trim();

        // Clear previous timer
        if (searchDebounceTimer) {
            clearTimeout(searchDebounceTimer);
        }

        // Reset selection
        selectedDropdownIndex = -1;

        // Hide dropdown if query too short
        if (query.length < 2) {
            dropdown.classList.remove("show");
            dropdown.innerHTML = "";
            return;
        }

        // Show loading
        dropdown.innerHTML = '<div class="search-dropdown-loading">Searching...</div>';
        dropdown.classList.add("show");

        // Debounce the API call (300ms)
        searchDebounceTimer = setTimeout(() => {
            fetchSearchSuggestions(query);
        }, 300);
    });

    // Handle keyboard navigation
    searchInput.addEventListener("keydown", (e) => {
        const items = dropdown.querySelectorAll(".search-dropdown-item");

        if (e.key === "ArrowDown") {
            e.preventDefault();
            selectedDropdownIndex = Math.min(selectedDropdownIndex + 1, items.length - 1);
            updateDropdownSelection(items);
        } else if (e.key === "ArrowUp") {
            e.preventDefault();
            selectedDropdownIndex = Math.max(selectedDropdownIndex - 1, -1);
            updateDropdownSelection(items);
        } else if (e.key === "Enter") {
            if (selectedDropdownIndex >= 0 && items[selectedDropdownIndex]) {
                e.preventDefault();
                selectSearchItem(items[selectedDropdownIndex]);
            }
        } else if (e.key === "Escape") {
            dropdown.classList.remove("show");
            selectedDropdownIndex = -1;
        }
    });

    // Hide dropdown when clicking outside
    document.addEventListener("click", (e) => {
        if (!e.target.closest(".search-autocomplete-container")) {
            dropdown.classList.remove("show");
            selectedDropdownIndex = -1;
        }
    });

    // Show dropdown on focus if there's content
    searchInput.addEventListener("focus", () => {
        if (dropdown.innerHTML && searchInput.value.trim().length >= 2) {
            dropdown.classList.add("show");
        }
    });
}

async function fetchSearchSuggestions(query) {
    const dropdown = document.getElementById("search-dropdown");

    try {
        // Use fetchWithAuth from token-utils.js for automatic 401 handling
        const res = await fetchWithAuth(
            `/reporting-service/api/stocks/search?q=${encodeURIComponent(query)}`
        );

        if (!res.ok) {
            dropdown.innerHTML = '<div class="search-dropdown-empty">Search failed</div>';
            return;
        }

        const results = await res.json();

        if (results.length === 0) {
            dropdown.innerHTML = '<div class="search-dropdown-empty">No matches found</div>';
            dropdown.classList.add("show");
            return;
        }

        // Render dropdown items
        dropdown.innerHTML = results.map((item, index) => `
            <div class="search-dropdown-item" data-ticker="${item.ticker}" data-name="${item.name}">
                <span class="ticker">${item.ticker.replace("NSE:", "")}</span>
                <span class="name">${item.name || ""}</span>
            </div>
        `).join("");

        dropdown.classList.add("show");

        // Add click handlers to items
        dropdown.querySelectorAll(".search-dropdown-item").forEach(item => {
            item.addEventListener("click", () => selectSearchItem(item));
        });

    } catch (e) {
        console.error("Search failed:", e);
        dropdown.innerHTML = '<div class="search-dropdown-empty">Search error</div>';
    }
}

function updateDropdownSelection(items) {
    items.forEach((item, index) => {
        if (index === selectedDropdownIndex) {
            item.classList.add("selected");
            item.scrollIntoView({ block: "nearest" });
        } else {
            item.classList.remove("selected");
        }
    });
}

function selectSearchItem(item) {
    const searchInput = document.getElementById("search");
    const dropdown = document.getElementById("search-dropdown");
    const ticker = item.dataset.ticker;

    // Set the search input value to the ticker (without NSE: prefix)
    searchInput.value = ticker.replace("NSE:", "");

    // Hide dropdown
    dropdown.classList.remove("show");
    selectedDropdownIndex = -1;

    // Optionally auto-apply the filter
    const token = localStorage.getItem(tokenKey);
    if (token) {
        currentPage = 0;
        fetchDashboard();
    }
}

// Initialize autocomplete when DOM is ready
document.addEventListener("DOMContentLoaded", initSearchAutocomplete);

// --- Anchor Date Functionality ---
document.addEventListener("DOMContentLoaded", initAnchorDate);

let anchorPrefillPollTimer = null;

function initAnchorDate() {
    const anchorPreset = document.getElementById("anchorPreset");
    const anchorDateInput = document.getElementById("anchorDate");
    const clearAnchorBtn = document.getElementById("clearAnchor");
    const sortByEl = document.getElementById("sortBy");

    if (!anchorPreset) return;

    // Load saved custom events into dropdown
    loadSavedAnchorEvents();

    // Handle preset dropdown change
    anchorPreset.addEventListener("change", (e) => {
        const value = e.target.value;

        if (value === "custom") {
            // Show date picker + save controls for custom date
            anchorDateInput.style.display = "inline-block";
            anchorDateInput.focus();
            clearAnchorBtn.style.display = "inline-block";
            hideSaveAnchorControls();
        } else if (value === "manage_saved") {
            // Manage saved events
            showManageSavedEvents();
            anchorPreset.value = currentAnchorDate || "";
        } else if (value) {
            // Preset date selected
            anchorDateInput.value = value;
            anchorDateInput.style.display = "none";
            clearAnchorBtn.style.display = "inline-block";
            hideSaveAnchorControls();
            activateAnchorDate(value);
        } else {
            // "Select Event" chosen - clear anchor
            clearAnchorDate();
        }
    });

    // Handle custom date change
    anchorDateInput.addEventListener("change", (e) => {
        const value = e.target.value;
        if (value) {
            clearAnchorBtn.style.display = "inline-block";
            showSaveAnchorControls();
            activateAnchorDate(value);
        }
    });

    // Handle clear button
    clearAnchorBtn.addEventListener("click", () => {
        clearAnchorDate();
        currentPage = 0;
        fetchDashboard();
    });
}

/**
 * Activate an anchor date - check if data exists, trigger prefill if not.
 */
async function activateAnchorDate(dateStr) {
    const sortByEl = document.getElementById("sortBy");

    currentAnchorDate = dateStr;
    addAnchorSortOption();
    sortByEl.value = "anchorMove";
    document.getElementById("order").value = "desc";

    // Check if data exists for this date
    try {
        const res = await fetchWithAuth(`/reporting-service/api/anchor-prices/exists?date=${dateStr}`);
        if (!res.ok) throw new Error("Failed to check data");
        const data = await res.json();

        if (data.exists && data.count > 100) {
            // Data exists - show dashboard immediately
            hideAnchorBanner();
            currentPage = 0;
            fetchDashboard();
        } else {
            // No data - trigger background prefill
            showAnchorBanner("loading", `Loading price data for ${formatAnchorDateLabel(dateStr)}... fetching from Yahoo Finance`);
            triggerAnchorPrefill(dateStr);
        }
    } catch (err) {
        console.error("Error checking anchor data:", err);
        // Try fetching dashboard anyway
        currentPage = 0;
        fetchDashboard();
    }
}

/**
 * Trigger async prefill for a date and poll for completion.
 */
async function triggerAnchorPrefill(dateStr) {
    try {
        const res = await fetchWithAuth(`/sheet-import-service/api/eod-prices/prefill-async?date=${dateStr}`, {
            method: "POST"
        });
        if (!res.ok) throw new Error("Failed to start prefill");
        const data = await res.json();

        if (data.status === "already_cached") {
            // Data actually exists (race condition or partial)
            hideAnchorBanner();
            currentPage = 0;
            fetchDashboard();
            return;
        }

        if (data.status === "started" || data.status === "already_running") {
            // Poll for progress
            pollAnchorPrefillStatus(dateStr, data.total || 0);
        }
    } catch (err) {
        console.error("Error triggering prefill:", err);
        showAnchorBanner("error", `Failed to load price data for ${formatAnchorDateLabel(dateStr)}. Try again later.`);
    }
}

/**
 * Poll the prefill status and update banner.
 */
function pollAnchorPrefillStatus(dateStr, total) {
    // Clear any existing poll
    if (anchorPrefillPollTimer) clearInterval(anchorPrefillPollTimer);

    anchorPrefillPollTimer = setInterval(async () => {
        try {
            const res = await fetchWithAuth("/sheet-import-service/api/eod-prices/status");
            if (!res.ok) return;
            const status = await res.json();

            if (status.isRunning) {
                const pct = total > 0 ? Math.round((status.progress / total) * 100) : 0;
                showAnchorBanner("loading",
                    `Loading prices for ${formatAnchorDateLabel(dateStr)}... ${status.progress}/${total} stocks (${pct}%)`);
            } else {
                // Done!
                clearInterval(anchorPrefillPollTimer);
                anchorPrefillPollTimer = null;
                showAnchorBanner("success", `Price data loaded for ${formatAnchorDateLabel(dateStr)}! Refreshing...`);

                // Clear cache so fresh data is fetched
                delete anchorPricesCache[dateStr];

                // Refresh dashboard
                currentPage = 0;
                await fetchDashboard();

                // Hide banner after 3 seconds
                setTimeout(hideAnchorBanner, 3000);
            }
        } catch (err) {
            console.error("Error polling prefill status:", err);
        }
    }, 3000); // Poll every 3 seconds
}

/**
 * Show/hide the anchor loading banner.
 */
function showAnchorBanner(type, message) {
    let banner = document.getElementById("anchor-banner");
    if (!banner) {
        banner = document.createElement("div");
        banner.id = "anchor-banner";
        const filtersCard = document.querySelector(".filters-card");
        if (filtersCard) {
            filtersCard.parentNode.insertBefore(banner, filtersCard.nextSibling);
        }
    }

    const colors = {
        loading: { bg: "#eff6ff", border: "#3b82f6", text: "#1e40af", icon: "⏳" },
        success: { bg: "#f0fdf4", border: "#22c55e", text: "#166534", icon: "✅" },
        error:   { bg: "#fef2f2", border: "#ef4444", text: "#991b1b", icon: "❌" }
    };
    const c = colors[type] || colors.loading;

    banner.style.cssText = `margin:12px 24px;padding:12px 16px;background:${c.bg};border:1px solid ${c.border};border-radius:8px;font-size:13px;color:${c.text};font-weight:500;display:flex;align-items:center;gap:8px;`;
    banner.innerHTML = `<span>${c.icon}</span><span>${message}</span>`;
}

function hideAnchorBanner() {
    const banner = document.getElementById("anchor-banner");
    if (banner) banner.remove();
}

// --- Save Custom Anchor Date ---

function showSaveAnchorControls() {
    let container = document.getElementById("save-anchor-controls");
    if (!container) {
        container = document.createElement("span");
        container.id = "save-anchor-controls";
        container.style.cssText = "display:inline-flex;align-items:center;gap:4px;";
        container.innerHTML = `
            <input type="text" id="anchorSaveName" placeholder="Event name" style="width:120px;padding:5px 8px;border:1px solid #f59e0b;border-radius:6px;font-size:12px;background:white;">
            <button id="anchorSaveBtn" style="background:#059669;color:white;border:none;padding:5px 10px;border-radius:6px;cursor:pointer;font-size:12px;font-weight:600;">Save</button>
        `;
        const anchorGroup = document.getElementById("anchor-date-group");
        if (anchorGroup) anchorGroup.appendChild(container);

        document.getElementById("anchorSaveBtn").addEventListener("click", saveCustomAnchorDate);
        document.getElementById("anchorSaveName").addEventListener("keydown", (e) => {
            if (e.key === "Enter") saveCustomAnchorDate();
        });
    }
    container.style.display = "inline-flex";
    document.getElementById("anchorSaveName").value = "";
}

function hideSaveAnchorControls() {
    const container = document.getElementById("save-anchor-controls");
    if (container) container.style.display = "none";
}

function saveCustomAnchorDate() {
    const nameInput = document.getElementById("anchorSaveName");
    const dateInput = document.getElementById("anchorDate");
    const name = nameInput?.value?.trim();
    const date = dateInput?.value;

    if (!name || !date) {
        nameInput?.focus();
        return;
    }

    // Load existing saved events
    const saved = getSavedAnchorEvents();

    // Don't duplicate
    if (saved.some(e => e.date === date && e.name === name)) return;

    saved.push({ date, name });
    localStorage.setItem("savedAnchorEvents", JSON.stringify(saved));

    // Refresh dropdown
    loadSavedAnchorEvents();

    // Select the newly saved event in dropdown
    const anchorPreset = document.getElementById("anchorPreset");
    if (anchorPreset) anchorPreset.value = date;

    // Hide save controls and date picker
    hideSaveAnchorControls();
    dateInput.style.display = "none";

    showAnchorBanner("success", `Saved "${name}" (${formatAnchorDateLabel(date)}) as anchor event`);
    setTimeout(hideAnchorBanner, 3000);
}

function getSavedAnchorEvents() {
    try {
        return JSON.parse(localStorage.getItem("savedAnchorEvents") || "[]");
    } catch { return []; }
}

function loadSavedAnchorEvents() {
    const anchorPreset = document.getElementById("anchorPreset");
    if (!anchorPreset) return;

    // Remove old saved options and manage option
    anchorPreset.querySelectorAll("option[data-saved]").forEach(o => o.remove());
    anchorPreset.querySelectorAll("option[data-manage]").forEach(o => o.remove());

    const saved = getSavedAnchorEvents();
    if (saved.length === 0) return;

    // Find the "Custom Date..." option and insert saved events before it
    const customOpt = anchorPreset.querySelector('option[value="custom"]');

    // Add separator
    const sep = document.createElement("option");
    sep.disabled = true;
    sep.textContent = "── Saved Events ──";
    sep.setAttribute("data-saved", "true");
    anchorPreset.insertBefore(sep, customOpt);

    // Add saved events
    for (const event of saved) {
        const opt = document.createElement("option");
        opt.value = event.date;
        const label = new Date(event.date + "T00:00:00").toLocaleDateString('en-IN', { month: 'short', day: 'numeric' });
        opt.textContent = `${event.name} (${label})`;
        opt.setAttribute("data-saved", "true");
        anchorPreset.insertBefore(opt, customOpt);
    }

    // Add "Manage Saved..." option after Custom
    const manageOpt = document.createElement("option");
    manageOpt.value = "manage_saved";
    manageOpt.textContent = "Manage Saved...";
    manageOpt.setAttribute("data-manage", "true");
    anchorPreset.appendChild(manageOpt);
}

function showManageSavedEvents() {
    const saved = getSavedAnchorEvents();
    if (saved.length === 0) {
        alert("No saved events to manage.");
        return;
    }

    let msg = "Saved Anchor Events:\n\n";
    saved.forEach((e, i) => {
        msg += `${i + 1}. ${e.name} (${e.date})\n`;
    });
    msg += "\nEnter number to delete (or 0 to cancel):";

    const choice = prompt(msg);
    if (!choice || choice === "0") return;

    const idx = parseInt(choice) - 1;
    if (idx >= 0 && idx < saved.length) {
        const removed = saved.splice(idx, 1)[0];
        localStorage.setItem("savedAnchorEvents", JSON.stringify(saved));
        loadSavedAnchorEvents();
        showAnchorBanner("success", `Removed "${removed.name}" from saved events`);
        setTimeout(hideAnchorBanner, 3000);
    }
}

function clearAnchorDate() {
    const anchorPreset = document.getElementById("anchorPreset");
    const anchorDateInput = document.getElementById("anchorDate");
    const clearAnchorBtn = document.getElementById("clearAnchor");

    currentAnchorDate = null;
    anchorPricesCache = {};

    if (anchorPreset) anchorPreset.value = "";
    if (anchorDateInput) {
        anchorDateInput.value = "";
        anchorDateInput.style.display = "none";
    }
    if (clearAnchorBtn) clearAnchorBtn.style.display = "none";

    hideSaveAnchorControls();
    hideAnchorBanner();

    // Stop any ongoing poll
    if (anchorPrefillPollTimer) {
        clearInterval(anchorPrefillPollTimer);
        anchorPrefillPollTimer = null;
    }

    // Remove anchor sort option and reset to default
    removeAnchorSortOption();
    const sortByEl = document.getElementById("sortBy");
    if (sortByEl && sortByEl.value === "anchorMove") {
        sortByEl.value = "marketCap";
    }
}

function addAnchorSortOption() {
    const sortByEl = document.getElementById("sortBy");
    if (!sortByEl) return;

    // Check if option already exists
    if (!sortByEl.querySelector('option[value="anchorMove"]')) {
        const opt = document.createElement("option");
        opt.value = "anchorMove";
        opt.textContent = "Move Since Anchor";
        sortByEl.appendChild(opt);
    }
}

function removeAnchorSortOption() {
    const sortByEl = document.getElementById("sortBy");
    if (!sortByEl) return;

    const anchorOpt = sortByEl.querySelector('option[value="anchorMove"]');
    if (anchorOpt) {
        sortByEl.removeChild(anchorOpt);
    }
}

function formatAnchorDateLabel(dateStr) {
    if (!dateStr) return "";
    const d = new Date(dateStr);
    return d.toLocaleDateString('en-IN', { month: 'short', day: 'numeric' });
}

async function loadUserInfo() {
  try {
    // Use fetchWithAuth from token-utils.js for automatic 401 handling
    const res = await fetchWithAuth("/oauth-service/api/userinfo");
    if (res.ok) {
      const info = await res.json();
      const name = info.name || info.email || info.subject || "User";
      document.getElementById("welcome-text").textContent = `Welcome, ${name}!`;
    }
  } catch (e) {
    console.log("Failed to load user info:", e);
  }
}


// --- tiny jwt decoder ---
function parseJwt (token) {
  try {
    const base64Url = token.split('.')[1];
    const base64 = base64Url.replace(/-/g, '+').replace(/_/g, '/');
    return JSON.parse(atob(base64));
  } catch {
    return {};
  }
}

// --- Fetch dashboard data with automatic token refresh on 401 ---
async function fetchDashboard() {
  try {
    const anchorDate = document.getElementById("anchorDate")?.value || currentAnchorDate;
    const sortBy = document.getElementById("sortBy").value;

    const params = new URLSearchParams({
      search: document.getElementById("search").value,
      minMarketCap: document.getElementById("minMarketCap").value,
      minDailyChange: document.getElementById("minDailyChange").value,
      minRank1Week: document.getElementById("minRank1Week")?.value || "",
      minRank1Month: document.getElementById("minRank1Month")?.value || "",
      maxPctFrom52WHigh: document.getElementById("maxPctFrom52WHigh")?.value || "",
      maxPctFrom52WLow: document.getElementById("maxPctFrom52WLow")?.value || "",
      sortBy: sortBy,
      order: document.getElementById("order").value,
      page: currentPage,
      pageSize: document.getElementById("pageSize").value
    });

    // Pass anchor date to backend for server-side sorting
    if (anchorDate) {
      params.set("anchorDate", anchorDate);
    }

    // Remove empty params
    for (const [key, value] of [...params.entries()]) {
      if (value === "" || value === null || value === undefined) {
        params.delete(key);
      }
    }

    // Use fetchWithAuth from token-utils.js for automatic 401 handling
    const res = await fetchWithAuth("/reporting-service/api/dashboard?" + params.toString());

    if (!res.ok) throw new Error("Failed to load dashboard");
    const data = await res.json();
    let stocks = data.stocks || [];

    // If backend handled anchor sorting (sortBy=anchorMove), stocks already have anchorMove
    // Otherwise, blend anchor prices client-side for display only (non-anchor sort)
    const backendHandledAnchor = sortBy === "anchorMove" && anchorDate;

    if (anchorDate && stocks.length > 0 && !backendHandledAnchor) {
      // Fetch anchor prices and blend with stocks (for display, no re-sorting)
      stocks = await blendAnchorPrices(stocks, anchorDate);
    }

    renderDashboard(stocks, !!anchorDate);
    updateActiveFiltersDisplay();

    // Update "last refreshed" timestamp
    const tsEl = document.getElementById("last-refresh-time");
    if (tsEl) {
      const now = new Date();
      tsEl.textContent = "Updated " + now.toLocaleTimeString("en-IN", { hour: "2-digit", minute: "2-digit" });
    }
  } catch (err) {
    console.error("Dashboard fetch failed:", err);
    // fetchWithAuth handles session expiry and redirect to login
  }
}

// Auto-refresh dashboard every 5 minutes (backend updates prices via Google Sheets + Yahoo Finance)
setInterval(() => {
  const token = localStorage.getItem(tokenKey);
  if (token) fetchDashboard();
}, 5 * 60 * 1000);

/**
 * Fetch anchor prices and blend with stock data.
 * Returns stocks array with anchorPrice and anchorMove added.
 *
 * The API returns ALL stock prices for the date (pre-populated by EOD job).
 * We cache the full response and filter locally.
 */
async function blendAnchorPrices(stocks, anchorDate) {
  try {
    if (stocks.length === 0) return stocks;

    // Check session cache first
    const cacheKey = anchorDate;
    let anchorPrices = anchorPricesCache[cacheKey];

    // Fetch from API if not in cache
    if (!anchorPrices) {
      console.log(`Fetching anchor prices for ${anchorDate}...`);

      const res = await fetchWithAuth(
        `/reporting-service/api/anchor-prices?date=${anchorDate}`
      );

      if (res.ok) {
        anchorPrices = await res.json();
        anchorPricesCache[cacheKey] = anchorPrices;
        console.log(`Fetched ${Object.keys(anchorPrices).length} anchor prices for ${anchorDate}`);
      } else {
        console.warn("Failed to fetch anchor prices:", res.status);
        return stocks;
      }
    }

    // Check if we have any prices
    if (!anchorPrices || Object.keys(anchorPrices).length === 0) {
      console.warn(`No anchor prices available for ${anchorDate}. Run EOD job or backfill first.`);
      return stocks;
    }

    // Blend anchor data into stocks
    return stocks.map(stock => {
      const ticker = (stock.ticker || "").replace("NSE:", "").trim().toUpperCase();
      const anchorPrice = anchorPrices[ticker];

      if (anchorPrice && stock.cmp && anchorPrice > 0) {
        const anchorMove = ((stock.cmp - anchorPrice) / anchorPrice) * 100;
        return {
          ...stock,
          anchorPrice: anchorPrice,
          anchorMove: Math.round(anchorMove * 100) / 100  // Round to 2 decimals
        };
      }
      return stock;
    });

  } catch (err) {
    console.error("Error blending anchor prices:", err);
    return stocks;
  }
}

// --- Render table ---
function renderDashboard(stocks, showAnchorColumn = false) {
  const container = document.getElementById("dashboard-content");
  container.classList.remove("loading");
  if (!stocks.length) {
    container.innerHTML = '<div class="no-data">No stocks found matching your criteria</div>';
    return;
  }

  const anchorDate = document.getElementById("anchorDate")?.value || currentAnchorDate;
  const anchorLabel = anchorDate ? formatAnchorDateLabel(anchorDate) : "";

  let table = "<table><thead><tr>";
  table += "<th style='width:35px;text-align:center'>#</th>";
  table += "<th style='width:75px'>Ticker</th>";
  table += "<th style='width:130px;max-width:130px'>Name</th>";

  // Show anchor column prominently if anchor date is set
  if (showAnchorColumn && anchorDate) {
    table += `<th class='text-right anchor-col' style='width:100px'>Since ${anchorLabel}</th>`;
  }

  table += "<th class='text-right' style='width:75px'>CMP</th>";
  table += "<th class='text-right' style='width:65px'>Daily</th>";
  table += "<th class='text-right' style='width:65px'>Week</th>";
  table += "<th class='text-right' style='width:65px'>Month</th>";
  table += "<th class='text-right' style='width:80px'>52W High</th>";
  table += "<th class='text-right' style='width:65px'>% High</th>";
  table += "<th class='text-right' style='width:85px'>MCap</th>";
  table += "<th class='text-center' style='width:50px'>RS</th>";
  table += "</tr></thead><tbody>";

  const pageSize = parseInt(document.getElementById("pageSize").value, 10) || 50;

  stocks.forEach((s, idx) => {
    // pick whichever field exists
    const weekVal  = coalesceNum(s.weekChange, s.rank1Week, s.weeklyChange);
    const monthVal = coalesceNum(s.monthChange, s.rank1Month, s.monthlyChange);
    const dayVal   = coalesceNum(s.dailyChange);

    const dailyClass = (dayVal ?? 0) >= 0 ? "positive" : "negative";
    const weekClass  = (weekVal ?? 0) >= 0 ? "positive" : "negative";
    const monthClass = (monthVal ?? 0) >= 0 ? "positive" : "negative";

    // Calculate % from 52W high
    let pctFromHigh = null;
    let pctFromHighClass = "";
    if (s.high52Week && s.high52Week > 0 && s.cmp) {
      pctFromHigh = ((s.high52Week - s.cmp) / s.high52Week) * 100;
      pctFromHighClass = pctFromHigh <= 5 ? "positive" : (pctFromHigh >= 20 ? "negative" : "");
    }

    // RS Rating badge
    let rsRatingHtml = "<span style='color:#94a3b8'>-</span>";
    if (s.rsRating != null) {
      let rsBadgeClass = "medium";
      if (s.rsRating >= 70) rsBadgeClass = "high";
      else if (s.rsRating < 40) rsBadgeClass = "low";
      rsRatingHtml = `<span class="rs-badge ${rsBadgeClass}">${Math.round(s.rsRating)}</span>`;
    }

    // Anchor move column
    let anchorMoveHtml = "";
    if (showAnchorColumn && anchorDate) {
      if (s.anchorMove != null && s.anchorPrice != null) {
        const moveClass = s.anchorMove >= 0 ? "positive" : "negative";
        const sign = s.anchorMove >= 0 ? "+" : "";
        anchorMoveHtml = `<td class="text-right anchor-col anchor-move ${moveClass}">
          ${sign}${s.anchorMove.toFixed(1)}%
        </td>`;
      } else {
        anchorMoveHtml = `<td class="text-right anchor-col"><span style='color:#94a3b8'>—</span></td>`;
      }
    }

    // Format ticker (remove NSE: prefix)
    const ticker = (s.ticker || "-").replace("NSE:", "");

    // Format name (truncate if too long)
    const name = s.name || "-";

    table += `<tr>
      <td style="text-align:center;color:#64748b">${idx + 1 + currentPage * pageSize}</td>
      <td>${ticker}</td>
      <td class="name-col" title="${name}">${name}</td>
      ${anchorMoveHtml}
      <td class="text-right">${fmtNum(s.cmp)}</td>
      <td class="text-right ${dailyClass}">${fmtPct(dayVal)}</td>
      <td class="text-right ${weekClass}">${fmtPct(weekVal)}</td>
      <td class="text-right ${monthClass}">${fmtPct(monthVal)}</td>
      <td class="text-right">${fmtNum(s.high52Week)}</td>
      <td class="text-right ${pctFromHighClass}">${pctFromHigh != null ? fmtNum(pctFromHigh) + "%" : "—"}</td>
      <td class="text-right">${fmtMCap(s.marketCap)}</td>
      <td class="text-center">${rsRatingHtml}</td>
    </tr>`;
  });

  table += "</tbody></table>";
  container.innerHTML = table;

  document.getElementById("page-info").textContent = "Page " + (currentPage + 1);

  function coalesceNum(...vals) {
    for (const v of vals) {
      if (v === 0 || (v != null && !Number.isNaN(Number(v)))) return Number(v);
    }
    return null;
  }
  function fmtNum(n) {
    if (n == null) return "—";
    const num = Number(n);
    if (!Number.isFinite(num)) return "—";
    return num.toLocaleString('en-IN', { maximumFractionDigits: 2 });
  }
  function fmtPct(n) {
    if (n == null) return "—";
    const num = Number(n);
    if (!Number.isFinite(num)) return "—";
    const sign = num >= 0 ? "+" : "";
    return sign + num.toFixed(1) + "%";
  }
  function fmtMCap(n) {
    if (n == null) return "—";
    const num = Number(n);
    if (!Number.isFinite(num)) return "—";
    if (num >= 100000) return (num / 100000).toFixed(1) + "L";
    if (num >= 1000) return (num / 1000).toFixed(1) + "K";
    return num.toLocaleString('en-IN');
  }
}

function showDashboardView() {
    const landing = document.getElementById("landing-section");
    const dash = document.getElementById("dashboard-section");

    if (landing) landing.style.display = "none";
    if (dash) dash.style.display = "block";

    const anchor = document.getElementById("dashboard-section");
    if (anchor) {
        anchor.scrollIntoView({ behavior: "instant", block: "start" });
    }
}

function scrollToLogin() {
    const el = document.getElementById("login");
    if (el) {
        el.scrollIntoView({ behavior: "smooth", block: "center" });
    }
}
// --- Show dashboard ---
function showDashboard(token) {
  document.getElementById("dashboard-section").style.display = "block";

  const claims = parseJwt(token);
  const name = claims.name || claims.email || claims.sub || "User";
  document.getElementById("welcome-text").textContent = `Welcome, ${name}!`;

  fetchDashboard();
}

// --- Apply filters ---
document.getElementById("apply-filters").addEventListener("click", () => {
  currentPage = 0;

  // Auto-select sort based on active filters
  autoSelectSort();

  const token = localStorage.getItem(tokenKey);
  if (token) fetchDashboard();
});

// Auto-select appropriate sort when filters are applied
function autoSelectSort() {
  const sortByEl = document.getElementById("sortBy");
  const orderEl = document.getElementById("order");

  const pctFrom52WHighVal = document.getElementById("maxPctFrom52WHigh")?.value;
  const pctFrom52WLowVal = document.getElementById("maxPctFrom52WLow")?.value;
  const minRank1WeekVal = document.getElementById("minRank1Week")?.value;
  const minRank1MonthVal = document.getElementById("minRank1Month")?.value;
  const minDailyChangeVal = document.getElementById("minDailyChange")?.value;

  // Priority: 52W High > 52W Low > Week% > Month% > Daily%
  if (pctFrom52WHighVal && pctFrom52WHighVal !== "") {
    sortByEl.value = "pctFrom52WHigh";
    orderEl.value = "asc"; // Lower % = closer to 52W high = show first
  } else if (pctFrom52WLowVal && pctFrom52WLowVal !== "") {
    sortByEl.value = "pctFrom52WLow";
    orderEl.value = "asc"; // Lower % = closer to 52W low = show first
  } else if (minRank1WeekVal && minRank1WeekVal !== "") {
    sortByEl.value = "rank1Week";
    orderEl.value = "desc"; // Higher week% = better performers first
  } else if (minRank1MonthVal && minRank1MonthVal !== "") {
    sortByEl.value = "rank1Month";
    orderEl.value = "desc"; // Higher month% = better performers first
  } else if (minDailyChangeVal && minDailyChangeVal !== "") {
    sortByEl.value = "dailyChange";
    orderEl.value = "desc"; // Higher daily% = better performers first
  }
  // If no special filter, keep current selection
}

// Show active filters summary
function updateActiveFiltersDisplay() {
  const filters = [];

  const search = document.getElementById("search")?.value;
  const minMarketCap = document.getElementById("minMarketCap")?.value;
  const minDailyChange = document.getElementById("minDailyChange")?.value;
  const minRank1Week = document.getElementById("minRank1Week")?.value;
  const minRank1Month = document.getElementById("minRank1Month")?.value;
  const maxPctFrom52WHigh = document.getElementById("maxPctFrom52WHigh")?.value;
  const maxPctFrom52WLow = document.getElementById("maxPctFrom52WLow")?.value;
  const anchorDate = document.getElementById("anchorDate")?.value || currentAnchorDate;
  const sortBy = document.getElementById("sortBy")?.value;
  const order = document.getElementById("order")?.value;

  if (search) filters.push(`Search: "${search}"`);
  if (minMarketCap) filters.push(`MCap >= ${minMarketCap}`);
  if (minDailyChange) filters.push(`Daily >= ${minDailyChange}%`);
  if (minRank1Week) filters.push(`Week >= ${minRank1Week}%`);
  if (minRank1Month) filters.push(`Month >= ${minRank1Month}%`);
  if (maxPctFrom52WHigh) filters.push(`Within ${maxPctFrom52WHigh}% of 52W High`);
  if (maxPctFrom52WLow) filters.push(`Within ${maxPctFrom52WLow}% of 52W Low`);
  if (anchorDate) filters.push(`Anchor: ${formatAnchorDateLabel(anchorDate)}`);

  const sortLabels = {
    cmp: "CMP", marketCap: "Market Cap", dailyChange: "Daily %",
    rank1Week: "Week %", rank1Month: "Month %", rank1Year: "Year %",
    rsRating: "RS Rating", pctFrom52WHigh: "% from 52W High", pctFrom52WLow: "% from 52W Low",
    anchorMove: "Anchor Move %",
    name: "Name", ticker: "Ticker"
  };
  filters.push(`Sort: ${sortLabels[sortBy] || sortBy} (${order})`);

  const activeFiltersEl = document.getElementById("active-filters");
  const activeFiltersListEl = document.getElementById("active-filters-list");

  if (filters.length > 1) {
    activeFiltersEl.style.display = "block";
    activeFiltersListEl.textContent = filters.join(" | ");
  } else {
    activeFiltersEl.style.display = "none";
  }
}

// Clear all filters
function clearFilters() {
  document.getElementById("search").value = "";
  document.getElementById("minMarketCap").value = "";
  document.getElementById("minDailyChange").value = "";
  document.getElementById("minRank1Week").value = "";
  document.getElementById("minRank1Month").value = "";
  document.getElementById("maxPctFrom52WHigh").value = "";
  document.getElementById("maxPctFrom52WLow").value = "";
  document.getElementById("sortBy").value = "cmp";
  document.getElementById("order").value = "desc";
  document.getElementById("pageSize").value = "50";
  currentPage = 0;

  // Clear anchor date
  clearAnchorDate();

  document.getElementById("active-filters").style.display = "none";

  const token = localStorage.getItem(tokenKey);
  if (token) fetchDashboard();
}

// Clear filters button handler
document.getElementById("clear-filters")?.addEventListener("click", clearFilters);

// --- Pagination ---
document.getElementById("prev-page").addEventListener("click", () => {
  if (currentPage > 0) {
    currentPage--;
    const token = localStorage.getItem(tokenKey);
    if (token) fetchDashboard();
  }
});

document.getElementById("next-page").addEventListener("click", () => {
  currentPage++;
  const token = localStorage.getItem(tokenKey);
  if (token) fetchDashboard();
});

// --- Init ---
(function init() {
  try {
    const isDashboard = window.location.pathname.includes("dashboard");

    // Check for tokens in URL hash (after OAuth redirect)
    if (window.location.hash && window.location.hash.includes("access_token=")) {
      const hashParams = new URLSearchParams(window.location.hash.substring(1));
      const accessToken = hashParams.get("access_token");
      const refreshToken = hashParams.get("refresh_token");

      if (accessToken) {
        localStorage.setItem(tokenKey, accessToken);
        console.log("Access token saved");
      }
      if (refreshToken) {
        localStorage.setItem(refreshTokenKey, refreshToken);
        console.log("Refresh token saved");
      }

      // Check if "stay logged in" was selected before login
      const stayLoggedInPending = localStorage.getItem("stay_logged_in_pending");
      if (stayLoggedInPending === "true" && accessToken) {
        // Send preference to backend
        fetch("/oauth-service/user/stay-logged-in", {
          method: "POST",
          headers: {
            "Content-Type": "application/json",
            "Authorization": "Bearer " + accessToken
          },
          body: JSON.stringify({ stayLoggedIn: true })
        }).then(() => {
          console.log("Stay logged in preference saved");
        }).catch(e => {
          console.log("Failed to save stay logged in preference:", e);
        });
      }
      localStorage.removeItem("stay_logged_in_pending");

      // Clear hash and redirect to dashboard
      window.location.hash = "";
      window.location.href = "dashboard.html";
      return;
    }

    const token = localStorage.getItem(tokenKey);
    const refreshToken = localStorage.getItem(refreshTokenKey);

    // If on index.html with a valid token, redirect to dashboard
    if (token && !isDashboard) {
      if (!isTokenExpired(token)) {
        window.location.href = "dashboard.html";
        return;
      }
      // Token expired - try refresh
      if (refreshToken) {
        refreshAccessToken().then(success => {
          if (success) {
            window.location.href = "dashboard.html";
          }
        });
        return;
      }
      // Expired with no refresh token - clear and stay on login
      localStorage.removeItem(tokenKey);
    }

    if (!token && isDashboard) {
      // No access token but have refresh token - try to refresh
      if (refreshToken) {
        console.log("No access token, attempting refresh...");
        refreshAccessToken().then(success => {
          if (success) {
            const newToken = localStorage.getItem(tokenKey);
            // Schedule next refresh BEFORE showing dashboard
            scheduleTokenRefresh(newToken);
            showDashboard(newToken);
          } else {
            window.location.href = "index.html";
          }
        });
        return;
      }
      // No tokens at all - redirect to landing
      window.location.href = "index.html";
      return;
    }

    if (token && isDashboard) {
      // Check if token is expired
      if (isTokenExpired(token)) {
        console.log("Token expired, attempting refresh...");
        if (refreshToken) {
          refreshAccessToken().then(success => {
            if (success) {
              const newToken = localStorage.getItem(tokenKey);
              // Schedule next refresh BEFORE showing dashboard
              scheduleTokenRefresh(newToken);
              showDashboard(newToken);
            } else {
              localStorage.removeItem(tokenKey);
              localStorage.removeItem(refreshTokenKey);
              window.location.href = "index.html";
            }
          });
          return;
        } else {
          localStorage.removeItem(tokenKey);
          localStorage.removeItem(refreshTokenKey);
          window.location.href = "index.html";
          return;
        }
      }

      // Token is valid - show dashboard and schedule refresh
      scheduleTokenRefresh(token);
      showDashboard(token);
    }
  } catch (e) {
    console.error("Init failed", e);
  }
})();

window.addEventListener("load", () => {
  const token = localStorage.getItem("access_token");
  if (token) return; // already logged in, no need to warm

  // Warm GATEWAY + OAUTH
  fetch("/oauth-service/oauth2/authorization/google", {
    method: "GET",
    mode: "no-cors",
  }).catch(() => {});

  // Warm REPORTING-SERVICE via gateway
  fetch("/reporting-service/actuator/health", {
    method: "GET",
    mode: "no-cors",
  }).catch(() => {});

  // Warm PORTFOLIO-SERVICE via gateway
  fetch("/portfolio-service/actuator/health", {
    method: "GET",
    mode: "no-cors",
  }).catch(() => {});
});



