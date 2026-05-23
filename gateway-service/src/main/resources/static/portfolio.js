// Auth is handled by HttpOnly cookies + token-utils.js (fetchWithAuth, hasValidSession, etc.)
const API_BASE =
  window.location.hostname === "localhost"
    ? "http://localhost:8082/portfolio-service/api/portfolio" // local gateway
    : `${window.location.origin}/portfolio-service/api/portfolio`; // Render gateway
let sortState = { column: null, asc: true };
let positions = [];     // cache of positions from API
let editingId = null;   // null => create, otherwise update
let expandedTickers = new Set();  // tickers with sub-rows expanded
let cmpMap = {};        // CMP cache (if your backend provides, wire it here)
let dayPctMap = {};      // ticker -> daily change % (e.g. +1.23 or -0.75)
let announcementCounts = {};  // ticker -> announcement count
let announcementUnseenCounts = {};  // ticker -> unseen announcement count
let alertCounts = {};         // ticker -> alert count
let triggeredAlerts = [];     // list of triggered alerts for notification bell
let notifDropdownOpen = false;
let resultsData = {};         // ticker -> results summary (quarterLabel, patYoY, trend)
let buyTransactions = {};    // ticker -> [{id, quantity, price, transactionDate}, ...] from transactions table
let concallData = {};        // ticker -> { hasConcall, summaryAvailable, announcementId, date, subject }

// Announcement service base URL (authenticated endpoints via gateway)
const ANN_API_BASE =
  window.location.hostname === "localhost"
    ? "http://localhost:8082/announcement-service/api/announcements"  // via gateway
    : `${window.location.origin}/announcement-service/api/announcements`;  // via gateway

// Alert service base URL (authenticated endpoints via gateway)
const ALERT_API_BASE =
  window.location.hostname === "localhost"
    ? "http://localhost:8082/alert-service/api/alerts"  // via gateway
    : `${window.location.origin}/alert-service/api/alerts`;  // via gateway

// Results service base URL (authenticated endpoints via gateway)
const RESULTS_API_BASE =
  window.location.hostname === "localhost"
    ? "http://localhost:8082/results-service/api/results"  // via gateway
    : `${window.location.origin}/results-service/api/results`;  // via gateway

// ========= Autocomplete =========
const suggestBox = document.getElementById("ticker-suggestions");
const tickerInput = document.getElementById("m-ticker");

let lastQuery = "", suggestTimer = null;

tickerInput.addEventListener("input", () => {
  const q = tickerInput.value.trim();
  clearTimeout(suggestTimer);
  if (q.length < 2) { suggestBox.innerHTML = ""; return; }

  suggestTimer = setTimeout(async () => {
    try {
      const url = `/portfolio-service/api/portfolio/quotes/search?q=${encodeURIComponent(q)}`;
      const res = await fetchWithAuth(url);
      if (!res.ok) return;
      const list = await res.json(); // [{ticker,name},...]
      suggestBox.innerHTML = list
        .map(s => `<option value="${s.ticker}">${s.ticker} — ${s.name ?? ""}</option>`)
        .join("");
    } catch (_) { /* ignore */ }
  }, 200); // debounce
});


function sortBy(column) {
  // toggle direction if same column clicked again
  if (sortState.column === column) sortState.asc = !sortState.asc;
  else { sortState.column = column; sortState.asc = true; }

  // sort positions in place
  positions.sort((a, b) => compareRows(a, b, column, sortState.asc));

  // re-render table after sorting
  renderTable(byId("filter").value);
}

function compareRows(a, b, column, asc) {
  const dir = asc ? 1 : -1;

  switch (column) {
    case "ticker":   return dir * a.ticker.localeCompare(b.ticker);
    case "quantity": return dir * ((a.quantity ?? 0) - (b.quantity ?? 0));
    case "buyPrice": return dir * ((a.buyPrice ?? 0) - (b.buyPrice ?? 0));
    case "invested": return dir * ((a.invested ?? 0) - (b.invested ?? 0));
    case "cmp":      return dir * ((computeCMP(a.ticker) ?? 0) - (computeCMP(b.ticker) ?? 0));
    case "current":  return dir * ((computeCMP(a.ticker) ?? 0)*(a.quantity ?? 0)
                                  - (computeCMP(b.ticker) ?? 0)*(b.quantity ?? 0));
    case "pl":
      const plA = (computeCMP(a.ticker) ?? 0)*(a.quantity ?? 0) - (a.buyPrice ?? 0)*(a.quantity ?? 0);
      const plB = (computeCMP(b.ticker) ?? 0)*(b.quantity ?? 0) - (b.buyPrice ?? 0)*(b.quantity ?? 0);
      return dir * (plA - plB);
    case "plp":
      const investedA = (a.quantity ?? 0)*(a.buyPrice ?? 0);
      const investedB = (b.quantity ?? 0)*(b.buyPrice ?? 0);
      const plpA = investedA > 0 ? ((computeCMP(a.ticker) - a.buyPrice)*100 / a.buyPrice) : 0;
      const plpB = investedB > 0 ? ((computeCMP(b.ticker) - b.buyPrice)*100 / b.buyPrice) : 0;
      return dir * (plpA - plpB);
    case "buyDate":  return dir * ((a.buyDate ?? "").localeCompare(b.buyDate ?? ""));
    case "dayPct":   return dir * ((computeDayPct(a.ticker) ?? 0) - (computeDayPct(b.ticker) ?? 0));
    default: return 0;
  }
}


async function loadCMPForTickers(tickers){
  // naive loop; you can batch on backend later
  for (const t of tickers){
    try{
      const res = await fetchWithAuth(`/portfolio-service/api/portfolio/quotes/price?ticker=${encodeURIComponent(t)}`);
      if (res.ok){
        const p = await res.json(); // {ticker, price}
        cmpMap[p.ticker] = p.price;
      }
    } catch(_){}
  }
}




function fmtINR(n) {
  if (n === null || n === undefined || isNaN(n)) return "–";
  try {
    return Number(n).toLocaleString("en-IN", { maximumFractionDigits: 2 });
  } catch {
    return String(n);
  }
}
function byId(id){ return document.getElementById(id); }

async function loadPositions() {
  const res = await fetchWithAuth(`${API_BASE}/positions`);
  if (!res.ok) throw new Error(`Load positions failed (${res.status})`);
  positions = await res.json();
}

// NEW: load CMPs in batch
async function loadPrices() {
  cmpMap = {};
  dayPctMap = {};

  const tickers = [...new Set((positions || []).map(p => p.ticker).filter(Boolean))];
  console.log('tickers for batch:', tickers);
  if (!tickers.length) return;

  const url = new URL(`${API_BASE}/quotes/batch`);
  tickers.forEach(t => url.searchParams.append('tickers', t));
  console.log('quotes URL:', url.toString());

  try {
    const res = await fetchWithAuth(url);
    console.log('batch status', res.status);
    if (!res.ok) {
      const text = await res.text().catch(() => '');
      console.error('batch failed', res.status, text);
      return;
    }

    const payload = await res.json();           // { items: [...], degraded, source }
    console.log('quotes payload', payload);

    const list = Array.isArray(payload) ? payload : (payload?.items || []);

    for (const q of list) {
      if (!q) continue;
      const price = Number(q.cmp ?? q.price);
      const dayPct = (q.dailyChange !== undefined && q.dailyChange !== null)
        ? Number(q.dailyChange)
        : null;

      if (isFinite(price)) {
        const t = String(q.ticker || '').toUpperCase();
        cmpMap[t] = price;
        if (!t.includes(':')) cmpMap['NSE:' + t] = price;

        if (dayPct !== null && isFinite(dayPct)) {
          dayPctMap[t] = dayPct;
          if (!t.includes(':')) dayPctMap['NSE:' + t] = dayPct;
        }
      }
    }

  } catch (e) {
    console.error('batch fetch error', e);
  }
}





function computeCMP(ticker) {
  return cmpMap[String(ticker || '').toUpperCase()] ?? null;
}
function computeDayPct(ticker) {
  const v = dayPctMap[String(ticker || '').toUpperCase()];
  return (v === undefined ? null : v); // can be 0
}
function getAnnouncementCount(ticker) {
  return announcementCounts[String(ticker || '').toUpperCase()] ?? 0;
}

function getAnnouncementUnseenCount(ticker) {
  return announcementUnseenCounts[String(ticker || '').toUpperCase()] ?? 0;
}

function getAlertCount(ticker) {
  return alertCounts[String(ticker || '').toUpperCase()] ?? 0;
}

// Load alert counts for portfolio tickers
async function loadAlertCounts() {
  alertCounts = {};
  const tickers = [...new Set((positions || []).map(p => p.ticker).filter(Boolean))];
  if (!tickers.length) return;

  try {
    const res = await fetchWithAuth(`${ALERT_API_BASE}/active`);
    if (!res.ok) {
      console.log('Alert counts not available:', res.status);
      return;
    }

    const alerts = await res.json();  // List of AlertDTO
    console.log('Active alerts:', alerts);

    // Count alerts per ticker
    for (const alert of alerts) {
      const t = String(alert.ticker || '').toUpperCase();
      alertCounts[t] = (alertCounts[t] || 0) + 1;
    }
  } catch (e) {
    console.log('Could not load alert counts:', e.message);
  }
}

// ========= Load Results Data =========
async function loadResultsData() {
  resultsData = {};
  const tickers = [...new Set((positions || []).map(p => p.ticker).filter(Boolean))];
  if (!tickers.length) return;

  // Extract pure tickers (without NSE: prefix)
  const pureTickers = tickers.map(t => extractPureTicker(t));

  try {
    const url = new URL(`${RESULTS_API_BASE}/portfolio`);
    pureTickers.forEach(t => url.searchParams.append('tickers', t));

    const res = await fetchWithAuth(url);
    if (!res.ok) {
      console.log('Results data not available:', res.status);
      return;
    }

    const data = await res.json();  // { results: [...], count, tickersWithResults }
    console.log('Portfolio results:', data);

    // Store results by ticker
    if (data.results) {
      for (const r of data.results) {
        const t = String(r.ticker || '').toUpperCase();
        resultsData[t] = r;
        // Also store with NSE: prefix for lookups
        resultsData['NSE:' + t] = r;
      }
    }
  } catch (e) {
    console.log('Could not load results data:', e.message);
  }
}

function getResultsForTicker(ticker) {
  const t = String(ticker || '').toUpperCase();
  return resultsData[t] || resultsData[extractPureTicker(t).toUpperCase()] || null;
}

// ========= Notification Bell =========
async function loadTriggeredAlerts() {
  try {
    const res = await fetchWithAuth(`${ALERT_API_BASE}/triggered`);
    if (!res.ok) {
      console.log('Triggered alerts not available:', res.status);
      return;
    }
    triggeredAlerts = await res.json();
    console.log('Triggered alerts:', triggeredAlerts);
    updateNotifBadge();
    renderNotifList();
  } catch (e) {
    console.log('Could not load triggered alerts:', e.message);
  }
}

function updateNotifBadge() {
  const badge = byId('notif-badge');
  const bellWrap = byId('notif-bell-wrap');
  const unseenCount = triggeredAlerts.filter(a => !a.seen).length;
  const totalTriggered = triggeredAlerts.length;

  // Update badge count
  if (unseenCount > 0) {
    badge.textContent = unseenCount > 99 ? '99+' : unseenCount;
    badge.style.display = 'inline-block';
  } else {
    badge.style.display = 'none';
  }

  // Update bell icon visual state
  bellWrap.classList.remove('has-unseen', 'no-alerts');
  if (unseenCount > 0) {
    bellWrap.classList.add('has-unseen');  // Animated, glowing bell
  } else if (totalTriggered === 0) {
    bellWrap.classList.add('no-alerts');   // Muted/normal bell
  }
  // Otherwise: alerts exist but all are seen - normal bell (no extra class)
}

function renderNotifList() {
  const list = byId('notif-list');
  if (!triggeredAlerts || triggeredAlerts.length === 0) {
    list.innerHTML = '<div class="notif-empty">No triggered alerts</div>';
    return;
  }

  let html = '';
  for (const alert of triggeredAlerts) {
    const unseenClass = alert.seen ? '' : 'unseen';
    const typeLabel = alert.alertType === 'STOP_LOSS' ? 'Stop Loss' :
                      alert.alertType === 'PRICE_BELOW' ? 'Price Below' : 'Price Above';
    const triggeredTime = alert.triggeredAt ? formatTimeAgo(alert.triggeredAt) : '';

    html += `
      <div class="notif-item ${unseenClass}" onclick="viewTriggeredAlert(${alert.id})">
        <div>
          <span class="notif-ticker">${alert.ticker}</span>
          <span class="notif-type">${typeLabel}</span>
        </div>
        <div class="notif-price">
          Triggered at ₹${fmtINR(alert.triggeredPrice)} (target: ₹${fmtINR(alert.targetPrice)})
        </div>
        <div class="notif-time">${triggeredTime}</div>
      </div>
    `;
  }
  list.innerHTML = html;
}

function formatTimeAgo(dateStr) {
  const date = new Date(dateStr);
  const now = new Date();
  const diffMs = now - date;
  const diffMins = Math.floor(diffMs / 60000);
  const diffHours = Math.floor(diffMs / 3600000);
  const diffDays = Math.floor(diffMs / 86400000);

  if (diffMins < 1) return 'Just now';
  if (diffMins < 60) return `${diffMins} min ago`;
  if (diffHours < 24) return `${diffHours} hour${diffHours > 1 ? 's' : ''} ago`;
  if (diffDays < 7) return `${diffDays} day${diffDays > 1 ? 's' : ''} ago`;
  return date.toLocaleDateString();
}

function toggleNotifDropdown() {
  const dropdown = byId('notif-dropdown');
  notifDropdownOpen = !notifDropdownOpen;
  dropdown.style.display = notifDropdownOpen ? 'block' : 'none';

  // Refresh list when opening
  if (notifDropdownOpen) {
    loadTriggeredAlerts();
  }
}

async function viewTriggeredAlert(alertId) {
  // Mark as seen using authenticated endpoint
  try {
    await fetchWithAuth(`${ALERT_API_BASE}/seen`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ alertIds: [alertId] })
    });
  } catch (e) {
    console.log('Could not mark alert as seen:', e.message);
  }

  // Update local state
  const alert = triggeredAlerts.find(a => a.id === alertId);
  if (alert) {
    alert.seen = true;
    updateNotifBadge();
    renderNotifList();
  }

  // Could open a detail modal here if needed
}

async function markAllSeen() {
  const alertIds = triggeredAlerts.filter(a => !a.seen).map(a => a.id);
  if (alertIds.length === 0) return;

  try {
    await fetchWithAuth(`${ALERT_API_BASE}/seen`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ alertIds })
    });

    // Update local state
    triggeredAlerts.forEach(a => a.seen = true);
    updateNotifBadge();
    renderNotifList();
  } catch (e) {
    console.log('Could not mark alerts as seen:', e.message);
  }
}

// Close dropdown when clicking outside
document.addEventListener('click', (e) => {
  const dropdown = byId('notif-dropdown');
  const bellWrap = byId('notif-bell-wrap');
  if (notifDropdownOpen && !dropdown.contains(e.target) && !bellWrap.contains(e.target)) {
    notifDropdownOpen = false;
    dropdown.style.display = 'none';
  }
});

// ========= Alert Modal =========
let alertModalTicker = null;
let alertModalBuyPrice = null;
let alertModalPositionId = null;
let alertModalCMP = null;

function openAlertModal(ticker, buyPrice, positionId) {
  alertModalTicker = ticker;
  alertModalBuyPrice = buyPrice;
  alertModalPositionId = positionId;
  alertModalCMP = computeCMP(ticker);

  const pureTicker = extractPureTicker(ticker);

  byId("alert-modal-title").textContent = "Create Alert for " + pureTicker;
  byId("a-ticker").value = pureTicker;
  byId("a-buyprice").value = buyPrice ? fmtINR(buyPrice) : "–";
  byId("a-cmp").value = alertModalCMP ? fmtINR(alertModalCMP) : "–";
  byId("a-type").value = "STOP_LOSS";
  byId("a-email").value = localStorage.getItem("alert_email") || "";
  byId("a-telegram").value = localStorage.getItem("telegram_chat_id") || "";

  // Default stop-loss target = buyPrice * 0.95 (5% below)
  const defaultTarget = buyPrice ? (buyPrice * 0.95).toFixed(2) : "";
  byId("a-target").value = defaultTarget;
  updateAlertTypeUI();

  byId("alert-modal-backdrop").style.display = "flex";
}

function closeAlertModal() {
  byId("alert-modal-backdrop").style.display = "none";
}

function updateAlertTypeUI() {
  const type = byId("a-type").value;
  const cmp = alertModalCMP;
  const buy = alertModalBuyPrice || 0;
  const label = byId("a-target-label");
  const input = byId("a-target");

  // Update label and default value based on type
  if (type === "STOP_LOSS") {
    label.textContent = "Target Price ₹";
    input.value = buy ? (buy * 0.95).toFixed(2) : "";
    input.step = "0.01";
  } else if (type === "PRICE_BELOW") {
    label.textContent = "Target Price ₹";
    input.value = cmp ? (cmp * 0.95).toFixed(2) : "";
    input.step = "0.01";
  } else if (type === "PRICE_ABOVE") {
    label.textContent = "Target Price ₹";
    input.value = cmp ? (cmp * 1.05).toFixed(2) : "";
    input.step = "0.01";
  } else if (type === "PCT_BELOW") {
    label.textContent = "% Below Current";
    input.value = "5";
    input.step = "0.1";
  } else if (type === "PCT_ABOVE") {
    label.textContent = "% Above Current";
    input.value = "5";
    input.step = "0.1";
  }

  updateAlertHint();
}

function updateAlertHint() {
  const type = byId("a-type").value;
  const target = parseFloat(byId("a-target").value) || 0;
  const cmp = alertModalCMP;
  const buy = alertModalBuyPrice || 0;

  let hint = "";
  let targetPrice = 0;

  if (type === "STOP_LOSS" && buy > 0 && target > 0) {
    const pct = ((buy - target) / buy * 100).toFixed(1);
    hint = `Alert when price falls to ₹${fmtINR(target)} (${pct}% below buy price ₹${fmtINR(buy)})`;
  } else if (type === "PRICE_BELOW" && target > 0) {
    if (cmp) {
      const pct = ((cmp - target) / cmp * 100).toFixed(1);
      hint = `Alert when price falls to ₹${fmtINR(target)} (${pct}% below current)`;
    } else {
      hint = `Alert when price falls to ₹${fmtINR(target)}`;
    }
  } else if (type === "PRICE_ABOVE" && target > 0) {
    if (cmp) {
      const pct = ((target - cmp) / cmp * 100).toFixed(1);
      hint = `Alert when price rises to ₹${fmtINR(target)} (${pct}% above current)`;
    } else {
      hint = `Alert when price rises to ₹${fmtINR(target)}`;
    }
  } else if (type === "PCT_BELOW" && cmp && target > 0) {
    targetPrice = cmp * (1 - target / 100);
    hint = `Alert when price falls to ₹${fmtINR(targetPrice)} (${target}% below ₹${fmtINR(cmp)})`;
  } else if (type === "PCT_ABOVE" && cmp && target > 0) {
    targetPrice = cmp * (1 + target / 100);
    hint = `Alert when price rises to ₹${fmtINR(targetPrice)} (${target}% above ₹${fmtINR(cmp)})`;
  }

  byId("a-hint").textContent = hint;
}

async function saveAlert() {
  const type = byId("a-type").value;
  const inputVal = parseFloat(byId("a-target").value);
  const email = byId("a-email").value.trim();
  const telegramChatId = byId("a-telegram").value.trim();
  const cmp = alertModalCMP;

  if (!inputVal || inputVal <= 0) {
    showErr("Please enter a valid value");
    return;
  }

  // Calculate actual target price for percentage types
  let targetPrice = inputVal;
  let apiAlertType = type;

  if (type === "PCT_BELOW") {
    if (!cmp) {
      showErr("Current price not available for percentage calculation");
      return;
    }
    targetPrice = cmp * (1 - inputVal / 100);
    apiAlertType = "PRICE_BELOW";  // Backend stores as PRICE_BELOW
  } else if (type === "PCT_ABOVE") {
    if (!cmp) {
      showErr("Current price not available for percentage calculation");
      return;
    }
    targetPrice = cmp * (1 + inputVal / 100);
    apiAlertType = "PRICE_ABOVE";  // Backend stores as PRICE_ABOVE
  }

  // Save preferences for next time
  if (email) localStorage.setItem("alert_email", email);
  if (telegramChatId) localStorage.setItem("telegram_chat_id", telegramChatId);

  // Build notification channels string
  let channels = [];
  if (email) channels.push('EMAIL');
  if (telegramChatId) channels.push('TELEGRAM');
  const notificationChannels = channels.length > 0 ? channels.join(',') : 'EMAIL';

  try {
    let res;
    if (type === "STOP_LOSS") {
      // For stop-loss, we can override the default 5% by passing target directly
      res = await fetchWithAuth(`${ALERT_API_BASE}`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          ticker: extractPureTicker(alertModalTicker),
          alertType: 'STOP_LOSS',
          targetPrice: targetPrice,
          buyPrice: alertModalBuyPrice,
          positionId: alertModalPositionId,
          userEmail: email || null,
          telegramChatId: telegramChatId || null,
          notificationChannels: notificationChannels
        })
      });
    } else {
      res = await fetchWithAuth(`${ALERT_API_BASE}`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          ticker: extractPureTicker(alertModalTicker),
          alertType: apiAlertType,
          targetPrice: targetPrice,
          userEmail: email || null,
          telegramChatId: telegramChatId || null,
          notificationChannels: notificationChannels
        })
      });
    }

    if (res.ok) {
      const alertData = await res.json();
      console.log('Created alert:', alertData);
      closeAlertModal();
      await loadAlertCounts();
      renderTable(byId("filter").value);
      showErr(""); // Clear any error
    } else {
      const errText = await res.text().catch(() => "");
      showErr(`Failed to create alert: ${res.status} ${errText}`);
    }
  } catch (e) {
    console.error('Could not create alert:', e);
    showErr(`Error: ${e.message}`);
  }
}

// Show alerts for a ticker (fetch and display in modal)
async function showAlerts(ticker) {
  const pureTicker = extractPureTicker(ticker);
  try {
    const res = await fetchWithAuth(`${ALERT_API_BASE}/ticker/${pureTicker}`);
    if (!res.ok) {
      alert(`Could not load alerts for ${pureTicker}`);
      return;
    }
    const alerts = await res.json();
    if (alerts.length === 0) {
      alert(`No active alerts for ${pureTicker}`);
      return;
    }

    // Format alerts for display
    let msg = `Alerts for ${pureTicker}:\n\n`;
    alerts.forEach((a, i) => {
      const status = a.status || 'ACTIVE';
      const type = a.alertType || 'ALERT';
      const target = a.targetPrice ? `₹${a.targetPrice}` : '–';
      msg += `${i+1}. ${type} @ ${target} [${status}]\n`;
    });
    msg += `\nTotal: ${alerts.length} alert(s)`;
    alert(msg);
  } catch (e) {
    console.error('Error loading alerts:', e);
    alert(`Error loading alerts: ${e.message}`);
  }
}

function extractPureTicker(ticker) {
  if (!ticker) return '';
  ticker = String(ticker).trim().toUpperCase();
  if (ticker.startsWith('NSE:')) return ticker.substring(4);
  if (ticker.startsWith('BSE:')) return ticker.substring(4);
  return ticker;
}

// Load announcement counts for portfolio tickers (total and unseen)
async function loadAnnouncementCounts() {
  announcementCounts = {};
  announcementUnseenCounts = {};
  const tickers = [...new Set((positions || []).map(p => p.ticker).filter(Boolean))];
  if (!tickers.length) return;

  try {
    // Load total counts
    const url = new URL(`${ANN_API_BASE}/counts`);
    tickers.forEach(t => url.searchParams.append('tickers', t));
    url.searchParams.append('days', '7');  // Last 7 days

    const res = await fetchWithAuth(url);
    if (!res.ok) {
      console.log('Announcement counts not available:', res.status);
      return;
    }

    const counts = await res.json();  // { "RELIANCE": 2, "TCS": 0, ... }
    console.log('Announcement counts:', counts);

    for (const [ticker, count] of Object.entries(counts)) {
      announcementCounts[ticker.toUpperCase()] = count;
    }

    // Load unseen counts
    const unseenUrl = new URL(`${ANN_API_BASE}/unseen-counts`);
    tickers.forEach(t => unseenUrl.searchParams.append('tickers', t));
    unseenUrl.searchParams.append('days', '7');

    const unseenRes = await fetchWithAuth(unseenUrl);
    if (unseenRes.ok) {
      const unseenCounts = await unseenRes.json();  // { "RELIANCE": 1, "TCS": 0, ... }
      console.log('Unseen announcement counts:', unseenCounts);

      for (const [ticker, count] of Object.entries(unseenCounts)) {
        announcementUnseenCounts[ticker.toUpperCase()] = count;
      }
    }
  } catch (e) {
    console.log('Could not load announcement counts:', e.message);
  }
}

// Load buy transactions for active positions (for expand/split view)
async function loadBuyTransactions() {
  buyTransactions = {};
  try {
    const res = await fetchWithAuth(`${API_BASE}/transactions/buys/active`);
    if (!res.ok) {
      console.log('Buy transactions not available:', res.status);
      return;
    }
    buyTransactions = await res.json(); // { "NSE:QPOWER": [{...}, {...}], ... }
    console.log('Buy transactions loaded:', Object.keys(buyTransactions).length, 'tickers');
  } catch (e) {
    console.log('Could not load buy transactions:', e.message);
  }
}

function getBuyTxForTicker(ticker) {
  const t = String(ticker || '').toUpperCase();
  return buyTransactions[t] || [];
}

// ========= Load Concall Data =========
async function loadConcallData() {
  concallData = {};
  const tickers = [...new Set((positions || []).map(p => p.ticker).filter(Boolean))];
  if (!tickers.length) return;

  const pureTickers = tickers.map(t => extractPureTicker(t));

  try {
    const url = new URL(`${ANN_API_BASE}/concall/status`);
    pureTickers.forEach(t => url.searchParams.append('tickers', t));
    url.searchParams.append('days', '90');

    const res = await fetchWithAuth(url);
    if (!res.ok) {
      console.log('Concall status not available:', res.status);
      return;
    }

    const data = await res.json();
    console.log('Concall status:', data);

    for (const [ticker, info] of Object.entries(data)) {
      concallData[ticker.toUpperCase()] = info;
      concallData['NSE:' + ticker.toUpperCase()] = info;
    }
  } catch (e) {
    console.log('Could not load concall data:', e.message);
  }
}

function getConcallInfo(ticker) {
  const t = String(ticker || '').toUpperCase();
  return concallData[t] || concallData[extractPureTicker(t).toUpperCase()] || null;
}

function toggleExpand(ticker) {
  if (expandedTickers.has(ticker)) expandedTickers.delete(ticker);
  else expandedTickers.add(ticker);
  renderTable(byId("filter").value);
}

function buildBadges(ticker, avgBuy, firstId) {
  const annCount = getAnnouncementCount(ticker);
  const annUnseenCount = getAnnouncementUnseenCount(ticker);
  const hasUnseen = annUnseenCount > 0;
  const annBadgeClass = hasUnseen ? 'has-ann has-unseen' : (annCount > 0 ? 'has-ann' : 'no-ann');
  let annBadgeHtml;
  if (hasUnseen) {
    annBadgeHtml = `<span class="ann-badge ${annBadgeClass}" onclick="showAnnouncements('${ticker}')" title="${annUnseenCount} new announcement(s)"><span class="ann-icon">📢</span>${annUnseenCount}</span>`;
  } else if (annCount > 0) {
    annBadgeHtml = `<span class="ann-badge has-ann seen" onclick="showAnnouncements('${ticker}')" title="All seen"><span class="ann-icon">📢</span></span>`;
  } else {
    annBadgeHtml = `<span class="ann-badge no-ann">–</span>`;
  }

  const alertCount = getAlertCount(extractPureTicker(ticker));
  const alertBadgeHtml = alertCount > 0
    ? `<span class="alert-badge has-alert" onclick="showAlerts('${ticker}')" title="View alerts"><span class="alert-icon">🔔</span>${alertCount}</span>`
    : `<span class="alert-badge no-alert" onclick="openAlertModal('${ticker}', ${avgBuy}, ${firstId})" title="Create alert"><span class="alert-icon">+</span></span>`;

  const resultsInfo = getResultsForTicker(ticker);
  let resultsBadgeHtml;
  if (resultsInfo) {
    const trend = resultsInfo.trend || '';
    const trendClass = trend === 'UP' ? 'trend-up' : (trend === 'DOWN' ? 'trend-down' : '');
    const patYoY = resultsInfo.patYoY;
    const patYoYText = patYoY != null ? `PAT: ${patYoY >= 0 ? '+' : ''}${patYoY.toFixed(1)}% YoY` : '';
    const quarterLabel = resultsInfo.quarterLabel || '';
    const pureTicker = extractPureTicker(ticker);
    resultsBadgeHtml = `<a href="results-analysis.html?ticker=${encodeURIComponent(pureTicker)}" class="results-badge has-results ${trendClass}" title="${quarterLabel}: ${patYoYText}"><span class="results-icon">📊</span>${quarterLabel}</a>`;
  } else {
    resultsBadgeHtml = `<span class="results-badge no-results" title="No results available">-</span>`;
  }

  const concallInfo = getConcallInfo(ticker);
  let concallBadgeHtml;
  if (concallInfo && concallInfo.hasConcall) {
    if (concallInfo.summaryAvailable) {
      concallBadgeHtml = `<span class="concall-badge has-summary" onclick="showConcallSummary(${concallInfo.announcementId}, '${extractPureTicker(ticker)}')" title="View concall summary (cached)"><span class="concall-icon">📞</span></span>`;
    } else {
      concallBadgeHtml = `<span class="concall-badge needs-gen" onclick="showConcallSummary(${concallInfo.announcementId}, '${extractPureTicker(ticker)}')" title="Generate concall summary"><span class="concall-icon">📞</span></span>`;
    }
  } else {
    concallBadgeHtml = `<span class="concall-badge no-concall">-</span>`;
  }

  return { annBadgeHtml, alertBadgeHtml, resultsBadgeHtml, concallBadgeHtml };
}

function renderTable(filterText = "") {
  const tbody = byId("rows");
  tbody.innerHTML = "";

  const normalized = filterText.trim().toLowerCase();
  let view = positions;
  if (normalized) {
    view = positions.filter(p =>
      p.ticker.toLowerCase().includes(normalized) ||
      (p.notes || "").toLowerCase().includes(normalized)
    );
  }

  if (view.length === 0) {
    tbody.innerHTML = `<tr><td colspan="17" class="muted">No positions yet.</td></tr>`;
    updateKPIs([]);
    return;
  }

  // Group positions by ticker
  const grouped = new Map();
  view.forEach(p => {
    const key = p.ticker.toUpperCase();
    if (!grouped.has(key)) grouped.set(key, []);
    grouped.get(key).push(p);
  });

  let rowsHtml = "";
  const enriched = [];
  let rowNum = 0;

  for (const [ticker, group] of grouped) {
    rowNum++;
    const cmp = computeCMP(ticker);
    const dayPct = computeDayPct(ticker);

    // Get buy transactions for this ticker (from transactions table)
    const buyTxs = getBuyTxForTicker(ticker);
    const hasMultipleBuys = buyTxs.length > 1;
    const isExpanded = expandedTickers.has(ticker);

    // Compute aggregated values from positions
    let totalQty = 0, totalInvested = 0;
    group.forEach(p => {
      const q = Number(p.quantity ?? 0);
      const b = Number(p.buyPrice ?? 0);
      totalQty += q;
      totalInvested += q * b;
    });
    const avgBuy = totalQty > 0 ? totalInvested / totalQty : 0;
    const current = cmp != null ? totalQty * Number(cmp) : null;
    const pl = current != null ? (current - totalInvested) : null;
    const plp = (current != null && totalInvested > 0) ? (pl * 100 / totalInvested) : null;
    const rowDayDeltaVal = (current != null && dayPct != null) ? (current * dayPct / 100) : null;

    enriched.push({ invested: totalInvested, current, pl, plp, rowDayDeltaVal, currentForWeight: current, dayPct });

    // Badges (only on main row)
    const badges = buildBadges(ticker, avgBuy, group[0].id);

    // Combine notes from all positions
    const allNotes = group.map(p => p.notes).filter(Boolean).join("; ");

    // Expand button if multiple buy transactions exist
    const expandBtn = hasMultipleBuys
      ? `<button class="expand-btn" onclick="toggleExpand('${ticker}')" title="${isExpanded ? 'Collapse' : 'Expand'} ${buyTxs.length} buys">${isExpanded ? '−' : '+'}</button> `
      : '';

    rowsHtml += `
      <tr>
        <td>${rowNum}</td>
        <td>${expandBtn}${ticker}${hasMultipleBuys ? ` <span style="color:#6b7280;font-size:11px">(${buyTxs.length})</span>` : ''}</td>
        <td>${totalQty}</td>
        <td class="num">${fmtINR(avgBuy)}</td>
        <td>${group[0].buyDate ?? ""}</td>
        <td class="num">${fmtINR(totalInvested)}</td>
        <td class="num">${cmp != null ? fmtINR(cmp) : "–"}</td>
        <td class="num">${current != null ? fmtINR(current) : "–"}</td>
        <td class="num" style="color:${(pl ?? 0) >= 0 ? 'green':'red'}">${pl != null ? fmtINR(pl) : "–"}</td>
        <td class="num" style="color:${(plp ?? 0) >= 0 ? 'green':'red'}">${plp != null ? fmtINR(plp) + "%" : "–"}</td>
        <td class="num" style="color:${(dayPct ?? 0) >= 0 ? 'green':'red'}">${dayPct != null ? fmtINR(dayPct) + "%" : "–"}</td>
        <td>${badges.annBadgeHtml}</td>
        <td>${badges.alertBadgeHtml}</td>
        <td>${badges.resultsBadgeHtml}</td>
        <td>${badges.concallBadgeHtml}</td>
        <td>${allNotes ? allNotes.replace(/</g,"&lt;") : ""}</td>
        <td class="actions">
          <button class="btn btn-outline" onclick="openAddShares('${ticker}', ${totalQty}, ${avgBuy})" title="Add more shares">+Add</button>
          <button class="btn" onclick="openSellModal('${ticker}', ${totalQty}, ${avgBuy}, ${group[0].id})" style="background:#059669;color:#fff;" title="Sell shares">Sell</button>
          <button class="btn btn-outline" onclick="openEdit(${group[0].id})" style="padding:6px 8px;" title="Edit position">✏️</button>
        </td>
      </tr>
    `;

    // Sub-rows from buy transactions (when expanded)
    if (hasMultipleBuys && isExpanded) {
      buyTxs.forEach((tx, si) => {
        const qty = Number(tx.quantity ?? 0);
        const buy = Number(tx.price ?? 0);
        const inv = qty * buy;
        const subCurrent = cmp != null ? qty * Number(cmp) : null;
        const subPl = subCurrent != null ? (subCurrent - inv) : null;
        const subPlp = (subCurrent != null && inv > 0) ? (subPl * 100 / inv) : null;
        const txDate = tx.transactionDate ?? "";

        rowsHtml += `
          <tr class="sub-row">
            <td></td>
            <td style="padding-left:28px;color:#6b7280;">↳ Buy ${si + 1}</td>
            <td>${qty}</td>
            <td class="num">${fmtINR(buy)}</td>
            <td>${txDate}</td>
            <td class="num">${fmtINR(inv)}</td>
            <td class="num">${cmp != null ? fmtINR(cmp) : "–"}</td>
            <td class="num">${subCurrent != null ? fmtINR(subCurrent) : "–"}</td>
            <td class="num" style="color:${(subPl ?? 0) >= 0 ? 'green':'red'}">${subPl != null ? fmtINR(subPl) : "–"}</td>
            <td class="num" style="color:${(subPlp ?? 0) >= 0 ? 'green':'red'}">${subPlp != null ? fmtINR(subPlp) + "%" : "–"}</td>
            <td class="num" style="color:${(dayPct ?? 0) >= 0 ? 'green':'red'}">${dayPct != null ? fmtINR(dayPct) + "%" : "–"}</td>
            <td></td>
            <td></td>
            <td></td>
            <td></td>
            <td>${tx.notes ? tx.notes.replace(/</g,"&lt;") : ""}</td>
            <td></td>
          </tr>
        `;
      });
    }
  }

  tbody.innerHTML = rowsHtml;
  updateKPIs(enriched);
}

function updateKPIs(enriched) {
  let totInvested = 0, totCurrent = 0, haveCurrent=false;
  let totDayDeltaVal = 0, totCurrentForWeight = 0;

  enriched.forEach(e => {
    // Only include in totals if we have a valid current value (CMP exists)
    // This ensures P&L calculation is consistent with analytics page
    if (e.current !== null) {
      totInvested += e.invested || 0;
      totCurrent += e.current;
      haveCurrent = true;
    }

    // accumulate daily delta and weight
    if (e.rowDayDeltaVal !== null) totDayDeltaVal += e.rowDayDeltaVal;
    if (e.currentForWeight !== null && e.dayPct !== null) totCurrentForWeight += e.currentForWeight;
  });

  const pl  = haveCurrent ? (totCurrent - totInvested) : null;
  const plp = (haveCurrent && totInvested > 0) ? (pl * 100 / totInvested) : null;

  // Portfolio daily change value & weighted % (weighted by current value)
  const dayVal = (totCurrentForWeight > 0) ? totDayDeltaVal : null;
  const dayPct = (totCurrentForWeight > 0) ? (totDayDeltaVal * 100 / totCurrentForWeight) : null;

  byId("kpi-invested").textContent = "₹ " + fmtINR(totInvested);
  byId("kpi-current").textContent  = haveCurrent ? ("₹ " + fmtINR(totCurrent)) : "–";

  const kpiPl = byId("kpi-pl");
  kpiPl.textContent = (pl !== null) ? ("₹ " + fmtINR(pl)) : "–";
  kpiPl.style.color = (pl ?? 0) >= 0 ? "green" : "red";      // color P/L ₹

  const kpiPlp = byId("kpi-plp");
  kpiPlp.textContent = (plp !== null) ? (fmtINR(plp) + "%") : "–";
  kpiPlp.style.color = (plp ?? 0) >= 0 ? "green" : "red";    // color P/L %

  // NEW: daily change KPI (₹ and %)
  const kpiDay = byId("kpi-day");
  const kpiDayp = byId("kpi-dayp");
  if (kpiDay && kpiDayp) {
    kpiDay.textContent  = (dayVal !== null) ? ("₹ " + fmtINR(dayVal)) : "–";
    kpiDayp.textContent = (dayPct !== null) ? (fmtINR(dayPct) + "%") : "–";
    const col = (dayVal ?? 0) >= 0 ? "green" : "red";
    kpiDay.style.color = col;
    kpiDayp.style.color = col;
  }
}


/* ---------- Modal handling ---------- */
function openCreate() {
  editingId = null;
  byId("modal-title").textContent = "Add Position";
  byId("m-ticker").value = "";
  byId("m-qty").value = "";
  byId("m-price").value = "";
  byId("m-date").value = "";
  byId("m-notes").value = "";
  byId("modal-backdrop").style.display = "flex";
}
function openEdit(id) {
  const p = positions.find(x => x.id === id);
  if (!p) return;

  editingId = id;
  byId("modal-title").textContent = "Edit Position";
  byId("m-ticker").value = p.ticker || "";
  byId("m-qty").value = p.quantity ?? "";
  byId("m-price").value = p.buyPrice ?? "";
  byId("m-date").value = p.buyDate ?? "";
  byId("m-notes").value = p.notes ?? "";
  byId("modal-backdrop").style.display = "flex";
}
function closeModal() { byId("modal-backdrop").style.display = "none"; }


async function resolveTicker(query){
  const url = `/portfolio-service/api/portfolio/quotes/resolve?query=${encodeURIComponent(query)}`;
  const res = await fetchWithAuth(url);
  if (!res.ok) throw new Error("Unknown ticker");
  return res.json(); // {ticker,name}
}

// Auto-create stop-loss alert at 5% below buy price
async function autoCreateStopLoss(ticker, buyPrice, positionId) {
  const pureTicker = extractPureTicker(ticker);
  const targetPrice = (buyPrice * 0.95).toFixed(2);
  const email = localStorage.getItem("alert_email") || "";
  const telegramChatId = localStorage.getItem("telegram_chat_id") || "";

  let channels = [];
  if (email) channels.push('EMAIL');
  if (telegramChatId) channels.push('TELEGRAM');
  const notificationChannels = channels.length > 0 ? channels.join(',') : 'EMAIL';

  try {
    const res = await fetchWithAuth(`${ALERT_API_BASE}`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        ticker: pureTicker,
        alertType: 'STOP_LOSS',
        targetPrice: parseFloat(targetPrice),
        buyPrice: buyPrice,
        stopLossPercent: 5.0,
        positionId: positionId || null,
        userEmail: email || null,
        telegramChatId: telegramChatId || null,
        notificationChannels: notificationChannels,
        notes: 'Auto-created 5% stop-loss'
      })
    });
    if (res.ok) {
      console.log(`Auto stop-loss created for ${pureTicker} at ₹${targetPrice}`);
    } else {
      console.log(`Auto stop-loss failed for ${pureTicker}:`, res.status);
    }
  } catch (e) {
    console.log(`Auto stop-loss error for ${pureTicker}:`, e.message);
  }
}

/* ---------- CRUD ---------- */
async function savePosition() {
  const raw = (byId("m-ticker").value || "").trim();
  const qty = Number(byId("m-qty").value);
  const price = Number(byId("m-price").value);
  const date = byId("m-date").value || null;
  const notes = byId("m-notes").value || null;

  if (!raw || qty <= 0 || price <= 0) {
    return showErr("Please fill ticker, qty and buy price correctly.");
  }

  let symbol;
  try {
    symbol = await resolveTicker(raw);      // validate + normalize
  } catch (e) {
    showErr("Ticker not found. Please pick from suggestions.");
    return;
  }

  const body = {
    ticker: symbol.ticker,     // canonical value from server
    quantity: qty,
    buyPrice: price,
    buyDate: date,
    notes
  };

  const method = editingId ? "PUT" : "POST";
  const url = editingId
    ? `${API_BASE}/positions/${editingId}`
    : `${API_BASE}/positions`;

  const res = await fetchWithAuth(url, {
    method,
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body)
  });

  if (!res.ok) {
    const t = await res.text().catch(()=> "");
    showErr(`Save failed (${res.status}) ${t}`);
    return;
  }

  // Auto-create 5% stop-loss for new positions
  if (!editingId) {
    const created = await res.json().catch(() => null);
    autoCreateStopLoss(symbol.ticker, price, created?.id);
  }

  closeModal();
  await refresh();
}


async function removePos(id) {
  if (!confirm("Delete this position?")) return;
  const res = await fetchWithAuth(`${API_BASE}/positions/${id}`, { method: "DELETE" });
  if (!res.ok) {
    showErr(`Delete failed (${res.status})`);
    return;
  }
  await refresh();
}

function showErr(msg){ byId("err").textContent = msg || ""; }

/* ---------- Init / refresh ---------- */
let aiOnce = false;
async function refresh() {
  try {
    await loadPositions();
    await Promise.all([
      loadPrices(),           // fills cmpMap / dayPctMap
      loadAnnouncementCounts(), // fills announcementCounts
      loadAlertCounts(),       // fills alertCounts
      loadTriggeredAlerts(),   // fills triggeredAlerts for notification bell
      loadResultsData(),       // fills resultsData for results column
      loadBuyTransactions(),   // fills buyTransactions for expand/split view
      loadConcallData()        // fills concallData for concall column
    ]);
    renderTable(byId("filter").value);

    // ⬇️ fetch the summary after data is ready
    if (!aiOnce) { aiOnce = true; loadAiSummary(); }   // no spamming
  } catch (e) {
    console.error(e);
    byId("rows").innerHTML =
      `<tr><td colspan="17" class="muted">Failed to load portfolio.</td></tr>`;
    byId('ai-summary-wrap').style.display = 'none';
  }
}

// Show announcements for a ticker (opens announcements page)
function showAnnouncements(ticker) {
  window.location.href = `announcements.html?ticker=${encodeURIComponent(ticker)}`;
}

async function loadAiSummary() {
  try {
    const res = await fetchWithAuth(`/portfolio-service/api/portfolio/ai-summary`);
    if (!res.ok) {
      // hide if endpoint not available / 401 during dev etc.
      byId('ai-summary-wrap').style.display = 'none';
      return;
    }
    const s = await res.json(); // { text, aiGenerated, dayPercent, dayValue, leaders, laggards }

    const wrap = byId('ai-summary-wrap');
    const textEl = byId('ai-summary');
    const badge = byId('ai-summary-badge');

    if (s && s.text) {
      textEl.textContent = s.text;

      // Color by day's sign
      const col = (s.dayValue ?? 0) >= 0 ? 'green' : 'red';
      textEl.style.color = col;

      // Badge style (AI vs rule-based)
      if (s.aiGenerated) {
        badge.textContent = 'AI insight';
        badge.style.background = '#eef';
        badge.style.color = '#334';
      } else {
        badge.textContent = 'Summary';
        badge.style.background = '#eee';
        badge.style.color = '#555';
      }

      wrap.style.display = 'block';
    } else {
      wrap.style.display = 'none';
    }
  } catch (e) {
    byId('ai-summary-wrap').style.display = 'none';
  }
}

/* ---------- Wire events ---------- */
byId("btn-add").addEventListener("click", openCreate);
byId("m-cancel").addEventListener("click", closeModal);
byId("m-save").addEventListener("click", () => savePosition().catch(err => showErr(err.message)));
byId("apply-filter").addEventListener("click", () => renderTable(byId("filter").value));
byId("logout-btn").addEventListener("click", async () => {
  try {
    await fetch("/oauth-service/token/revoke", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      credentials: "include",
      body: JSON.stringify({})
    });
  } catch (e) { console.log("Token revoke failed:", e); }
  location.href = "index.html";
});

// Alert modal events
byId("a-cancel").addEventListener("click", closeAlertModal);
byId("a-save").addEventListener("click", () => saveAlert().catch(err => showErr(err.message)));
byId("a-type").addEventListener("change", updateAlertTypeUI);  // updates label, default value, and hint
byId("a-target").addEventListener("input", updateAlertHint);

// ========= Sell Modal =========
let sellModalTicker = null;
let sellModalQty = 0;
let sellModalBuyPrice = 0;
let sellModalPositionId = null;

function openSellModal(ticker, availableQty, buyPrice, positionId) {
  sellModalTicker = ticker;
  sellModalQty = availableQty;
  sellModalBuyPrice = buyPrice;
  sellModalPositionId = positionId;

  const pureTicker = extractPureTicker(ticker);
  const cmp = computeCMP(ticker);

  byId("sell-modal-title").textContent = "Sell " + pureTicker;
  byId("s-ticker").value = pureTicker;
  byId("s-available-qty").value = availableQty;
  byId("s-buy-price").value = fmtINR(buyPrice);
  byId("s-cmp").value = cmp ? fmtINR(cmp) : "–";

  // Calculate unrealized P&L
  if (cmp) {
    const unrealizedPnl = (cmp - buyPrice) * availableQty;
    const unrealizedPct = buyPrice > 0 ? ((cmp - buyPrice) / buyPrice * 100) : 0;
    byId("s-unrealized-pnl").value = `₹${fmtINR(unrealizedPnl)} (${unrealizedPct >= 0 ? '+' : ''}${fmtINR(unrealizedPct)}%)`;
    byId("s-unrealized-pnl").style.color = unrealizedPnl >= 0 ? 'green' : 'red';
  } else {
    byId("s-unrealized-pnl").value = "–";
  }

  // Default values
  byId("s-qty").value = "";
  byId("s-sell-price").value = cmp ? cmp.toFixed(2) : "";
  byId("s-date").value = new Date().toISOString().split('T')[0];
  byId("s-notes").value = "";
  byId("s-realized-pnl").value = "";

  byId("sell-modal-backdrop").style.display = "flex";
}

function closeSellModal() {
  byId("sell-modal-backdrop").style.display = "none";
}

function updateSellPnlEstimate() {
  const qty = parseInt(byId("s-qty").value) || 0;
  const sellPrice = parseFloat(byId("s-sell-price").value) || 0;

  if (qty > 0 && sellPrice > 0 && sellModalBuyPrice > 0) {
    const realizedPnl = (sellPrice - sellModalBuyPrice) * qty;
    const pct = (sellPrice - sellModalBuyPrice) / sellModalBuyPrice * 100;
    byId("s-realized-pnl").value = `₹${fmtINR(realizedPnl)} (${pct >= 0 ? '+' : ''}${fmtINR(pct)}%)`;
    byId("s-realized-pnl").style.color = realizedPnl >= 0 ? 'green' : 'red';
  } else {
    byId("s-realized-pnl").value = "";
  }
}

function sellAll() {
  byId("s-qty").value = sellModalQty;
  updateSellPnlEstimate();
}

async function confirmSell() {
  const qty = parseInt(byId("s-qty").value);
  const sellPrice = parseFloat(byId("s-sell-price").value);
  const sellDate = byId("s-date").value || null;
  const notes = byId("s-notes").value || null;

  if (!qty || qty <= 0) {
    showErr("Please enter quantity to sell");
    return;
  }
  if (qty > sellModalQty) {
    showErr(`Cannot sell ${qty} shares. Only ${sellModalQty} available.`);
    return;
  }
  if (!sellPrice || sellPrice <= 0) {
    showErr("Please enter sell price");
    return;
  }

  const body = {
    ticker: extractPureTicker(sellModalTicker),
    quantity: qty,
    sellPrice: sellPrice,
    sellDate: sellDate,
    notes: notes
  };

  try {
    const res = await fetchWithAuth(`${API_BASE}/transactions/sell`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body)
    });

    if (res.ok) {
      const tx = await res.json();
      console.log('Sell transaction:', tx);
      closeSellModal();
      showErr(""); // Clear any error
      await refresh();

      // Show success message
      const pnl = tx.realizedPnl || 0;
      alert(`Sold ${qty} shares of ${extractPureTicker(sellModalTicker)}.\nRealized P&L: ₹${fmtINR(pnl)}`);
    } else {
      const errText = await res.text().catch(() => "");
      showErr(`Sell failed: ${res.status} ${errText}`);
    }
  } catch (e) {
    console.error('Sell error:', e);
    showErr(`Error: ${e.message}`);
  }
}

// ========= Add Shares Modal =========
let addSharesTicker = null;
let addSharesCurrentQty = 0;
let addSharesAvgPrice = 0;

function openAddShares(ticker, currentQty, avgPrice) {
  addSharesTicker = ticker;
  addSharesCurrentQty = currentQty;
  addSharesAvgPrice = avgPrice;

  const pureTicker = extractPureTicker(ticker);
  const cmp = computeCMP(ticker);

  byId("add-shares-modal-title").textContent = "Add Shares - " + pureTicker;
  byId("as-ticker").value = pureTicker;
  byId("as-current-qty").value = currentQty;
  byId("as-avg-price").value = fmtINR(avgPrice);
  byId("as-cmp").value = cmp ? fmtINR(cmp) : "–";
  byId("as-new-avg").value = "";

  // Default values
  byId("as-qty").value = "";
  byId("as-price").value = cmp ? cmp.toFixed(2) : "";
  byId("as-date").value = new Date().toISOString().split('T')[0];
  byId("as-notes").value = "";

  byId("add-shares-modal-backdrop").style.display = "flex";
}

function closeAddSharesModal() {
  byId("add-shares-modal-backdrop").style.display = "none";
}

function updateNewAvgPrice() {
  const addQty = parseInt(byId("as-qty").value) || 0;
  const addPrice = parseFloat(byId("as-price").value) || 0;

  if (addQty > 0 && addPrice > 0) {
    const oldValue = addSharesAvgPrice * addSharesCurrentQty;
    const newValue = addPrice * addQty;
    const totalQty = addSharesCurrentQty + addQty;
    const newAvg = (oldValue + newValue) / totalQty;
    byId("as-new-avg").value = `₹${fmtINR(newAvg)} (${totalQty} shares)`;
  } else {
    byId("as-new-avg").value = "";
  }
}

async function confirmAddShares() {
  const qty = parseInt(byId("as-qty").value);
  const buyPrice = parseFloat(byId("as-price").value);
  const buyDate = byId("as-date").value || null;
  const notes = byId("as-notes").value || null;

  if (!qty || qty <= 0) {
    showErr("Please enter quantity to add");
    return;
  }
  if (!buyPrice || buyPrice <= 0) {
    showErr("Please enter buy price");
    return;
  }

  const body = {
    ticker: extractPureTicker(addSharesTicker),
    quantity: qty,
    buyPrice: buyPrice,
    buyDate: buyDate,
    notes: notes
  };

  try {
    const res = await fetchWithAuth(`${API_BASE}/transactions/add`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body)
    });

    if (res.ok) {
      const tx = await res.json();
      console.log('Add shares transaction:', tx);

      // Auto-create 5% stop-loss for the new buy price
      autoCreateStopLoss(addSharesTicker, buyPrice, tx.positionId);

      closeAddSharesModal();
      showErr(""); // Clear any error
      await refresh();
    } else {
      const errText = await res.text().catch(() => "");
      showErr(`Add failed: ${res.status} ${errText}`);
    }
  } catch (e) {
    console.error('Add shares error:', e);
    showErr(`Error: ${e.message}`);
  }
}

// Wire sell modal events
byId("s-cancel").addEventListener("click", closeSellModal);
byId("s-save").addEventListener("click", () => confirmSell().catch(err => showErr(err.message)));
byId("s-sell-all").addEventListener("click", sellAll);
byId("s-qty").addEventListener("input", updateSellPnlEstimate);
byId("s-sell-price").addEventListener("input", updateSellPnlEstimate);

// Wire add shares modal events
byId("as-cancel").addEventListener("click", closeAddSharesModal);
byId("as-save").addEventListener("click", () => confirmAddShares().catch(err => showErr(err.message)));
byId("as-qty").addEventListener("input", updateNewAvgPrice);
byId("as-price").addEventListener("input", updateNewAvgPrice);

// ========= Zerodha Import =========
let parsedImportData = [];

function openImportModal() {
  byId("import-data").value = "";
  byId("import-file").value = "";
  byId("import-preview").style.display = "none";
  byId("import-error").textContent = "";
  byId("import-save").disabled = true;
  parsedImportData = [];
  byId("import-modal-backdrop").style.display = "flex";
}

function closeImportModal() {
  byId("import-modal-backdrop").style.display = "none";
}

// Parse Zerodha data (CSV or tab-separated)
function parseZerodhaData() {
  const textarea = byId("import-data").value.trim();
  const fileInput = byId("import-file");

  if (fileInput.files.length > 0) {
    // Read file
    const file = fileInput.files[0];
    const reader = new FileReader();
    reader.onload = function(e) {
      const content = e.target.result;
      processImportData(content);
    };
    reader.readAsText(file);
  } else if (textarea) {
    processImportData(textarea);
  } else {
    byId("import-error").textContent = "Please paste data or upload a file.";
  }
}

function processImportData(content) {
  byId("import-error").textContent = "";
  parsedImportData = [];

  // Split by lines
  const lines = content.split(/\r?\n/).filter(line => line.trim());
  if (lines.length < 2) {
    byId("import-error").textContent = "No data found. Need header + at least 1 row.";
    return;
  }

  // Detect separator (tab or comma)
  const firstLine = lines[0];
  const separator = firstLine.includes('\t') ? '\t' : ',';

  // Parse header
  const headers = lines[0].split(separator).map(h => h.trim().toLowerCase());
  console.log('Headers:', headers);

  // Find column indices
  const instrumentIdx = headers.findIndex(h => h.includes('instrument') || h.includes('ticker') || h.includes('symbol'));
  const qtyIdx = headers.findIndex(h => h.includes('qty') || h.includes('quantity'));
  const avgCostIdx = headers.findIndex(h => h.includes('avg') && h.includes('cost') || h === 'avg. cost' || h === 'avg cost' || h === 'buy price');

  if (instrumentIdx === -1) {
    byId("import-error").textContent = "Could not find 'Instrument' column in headers.";
    return;
  }
  if (qtyIdx === -1) {
    byId("import-error").textContent = "Could not find 'Qty' column in headers.";
    return;
  }
  if (avgCostIdx === -1) {
    byId("import-error").textContent = "Could not find 'Avg. cost' column in headers.";
    return;
  }

  // Parse data rows
  for (let i = 1; i < lines.length; i++) {
    const cols = lines[i].split(separator).map(c => c.trim());
    if (cols.length <= Math.max(instrumentIdx, qtyIdx, avgCostIdx)) continue;

    let instrument = cols[instrumentIdx];
    const qty = parseFloat(cols[qtyIdx].replace(/,/g, '')) || 0;
    const avgCost = parseFloat(cols[avgCostIdx].replace(/,/g, '')) || 0;

    if (!instrument || qty <= 0 || avgCost <= 0) continue;

    // Clean up ticker thoroughly
    let ticker = instrument.trim().toUpperCase();
    // Remove all surrounding quotes (handles multiple layers like ""TICKER"")
    ticker = ticker.replace(/^["']+|["']+$/g, '');
    // Remove any remaining whitespace after quote removal
    ticker = ticker.trim();
    // Remove exchange suffixes like -EQ, -BE, -BL, -N1, etc.
    ticker = ticker.replace(/-(EQ|BE|BL|N1|SM|ST)$/i, '');
    // Add NSE: prefix for Indian stocks if not present
    if (!ticker.includes(':')) {
      ticker = 'NSE:' + ticker;
    }

    parsedImportData.push({
      ticker: ticker,
      quantity: qty,
      buyPrice: avgCost
    });
  }

  if (parsedImportData.length === 0) {
    byId("import-error").textContent = "No valid positions found in the data.";
    return;
  }

  // Show preview
  byId("import-count").textContent = parsedImportData.length;
  const previewRows = byId("import-preview-rows");
  previewRows.innerHTML = parsedImportData.map(p => `
    <tr>
      <td>${p.ticker}</td>
      <td>${p.quantity}</td>
      <td>₹${fmtINR(p.buyPrice)}</td>
    </tr>
  `).join('');

  byId("import-preview").style.display = "block";
  byId("import-save").disabled = false;
}

async function importPositions() {
  if (parsedImportData.length === 0) {
    byId("import-error").textContent = "No data to import.";
    return;
  }

  byId("import-save").disabled = true;
  byId("import-save").textContent = "Importing...";
  byId("import-error").textContent = "";

  try {
    const res = await fetchWithAuth(`${API_BASE}/positions/bulk`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ positions: parsedImportData })
    });

    if (res.ok) {
      const result = await res.json();
      console.log('Import result:', result);
      closeImportModal();
      await refresh();
      alert(`Successfully imported ${result.imported || parsedImportData.length} positions!`);
    } else {
      const errText = await res.text().catch(() => "");
      byId("import-error").textContent = `Import failed: ${res.status} ${errText}`;
      byId("import-save").disabled = false;
    }
  } catch (e) {
    console.error('Import error:', e);
    byId("import-error").textContent = `Error: ${e.message}`;
    byId("import-save").disabled = false;
  }

  byId("import-save").textContent = "Import All";
}

// Wire import modal events
byId("btn-import").addEventListener("click", openImportModal);
byId("import-cancel").addEventListener("click", closeImportModal);
byId("import-parse").addEventListener("click", parseZerodhaData);
byId("import-save").addEventListener("click", importPositions);
byId("import-file").addEventListener("change", parseZerodhaData);

// Refresh prices button
byId("btn-refresh").addEventListener("click", refreshPrices);

async function refreshPrices() {
  const btn = byId("btn-refresh");
  const originalText = btn.textContent;
  btn.textContent = "⏳ Loading...";
  btn.disabled = true;

  try {
    await loadPrices();
    renderTable(byId("filter").value);
    showErr("");
    // Brief success indication
    btn.textContent = "✅ Updated!";
    setTimeout(() => { btn.textContent = originalText; }, 1500);
  } catch (e) {
    console.error("Refresh prices error:", e);
    showErr("Failed to refresh prices");
    btn.textContent = originalText;
  } finally {
    btn.disabled = false;
  }
}


// ========= Concall Summary Modal =========
async function showConcallSummary(announcementId, ticker) {
  const modal = byId("concall-modal-backdrop");
  const title = byId("concall-modal-title");
  const body = byId("concall-modal-body");

  title.textContent = `Concall Summary - ${ticker}`;
  body.innerHTML = `
    <div class="concall-loading">
      <div class="spinner"></div>
      <div>Generating summary from earnings call transcript...</div>
      <div style="font-size:12px;margin-top:8px;">This may take 15-30 seconds on first load</div>
    </div>
  `;
  modal.style.display = "flex";

  try {
    const res = await fetchWithAuth(`${ANN_API_BASE}/concall/summary/${announcementId}`);
    if (!res.ok) {
      body.innerHTML = `<div class="concall-error">Failed to load summary (${res.status})</div>`;
      return;
    }

    const data = await res.json();
    console.log('Concall summary:', data);

    if (data.error) {
      body.innerHTML = `<div class="concall-error">${data.error}</div>`;
      return;
    }

    if (data.status === 'SUCCESS') {
      const quarterLabel = data.quarter ? ` (${data.quarter})` : '';
      body.innerHTML = `
        ${formatConcallSummary(data.summaryText)}
        <div class="concall-meta">
          ${data.pdfPageCount ? `PDF: ${data.pdfPageCount} pages` : ''}
          ${data.textLength ? ` | Extracted: ${Math.round(data.textLength / 1000)}K chars` : ''}
          ${data.generatedAt ? ` | Generated: ${new Date(data.generatedAt).toLocaleDateString()}` : ''}
          ${quarterLabel}
        </div>
      `;

      // Update badge to purple (cached) after successful generation
      const info = getConcallInfo(ticker);
      if (info) info.summaryAvailable = true;
    } else if (data.status === 'AI_DISABLED') {
      body.innerHTML = `<div class="concall-error">${data.summaryText || 'AI summarization is not configured.'}</div>`;
    } else if (data.status === 'PDF_ERROR') {
      body.innerHTML = `<div class="concall-error">${data.summaryText || 'Could not extract text from PDF.'}</div>`;
    } else {
      body.innerHTML = `<div class="concall-error">${data.summaryText || 'Summary generation failed.'}</div>`;
    }
  } catch (e) {
    console.error('Concall summary error:', e);
    body.innerHTML = `<div class="concall-error">Error: ${e.message}</div>`;
  }
}

function formatConcallSummary(text) {
  if (!text) return '<div class="concall-error">No summary available</div>';

  // Convert markdown-like formatting to HTML
  let html = text
    // Headers: ## Header
    .replace(/^## (.+)$/gm, '<h2>$1</h2>')
    .replace(/^### (.+)$/gm, '<h3>$1</h3>')
    // Bold: **text**
    .replace(/\*\*(.+?)\*\*/g, '<strong>$1</strong>')
    // Bullet points: - item
    .replace(/^- (.+)$/gm, '<li>$1</li>')
    // Wrap consecutive <li> in <ul>
    .replace(/((<li>.*<\/li>\n?)+)/g, '<ul>$1</ul>')
    // Paragraphs: double newline
    .replace(/\n\n/g, '</p><p>')
    // Single newlines within paragraphs
    .replace(/\n/g, '<br>');

  return `<div>${html}</div>`;
}

function closeConcallModal() {
  byId("concall-modal-backdrop").style.display = "none";
}

// Close concall modal on backdrop click
byId("concall-modal-backdrop").addEventListener("click", function(e) {
  if (e.target === this) closeConcallModal();
});

/* ---------- boot ---------- */
// Wait for token-utils.js to handle token validation/refresh, then load portfolio
document.addEventListener("DOMContentLoaded", function() {
  // Give token-utils.js a moment to initialize and potentially refresh token
  setTimeout(function() {
    if (!hasValidSession()) {
      console.log("Portfolio: No valid session after init, redirecting to login");
      location.href = "index.html";
      return;
    }
    refresh();
  }, 100);  // Small delay to let token-utils init complete
});
