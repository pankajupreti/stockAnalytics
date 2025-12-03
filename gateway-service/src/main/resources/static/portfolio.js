// Reuse the same token handling you already have
const tokenKey = "access_token";
const API_BASE =
  window.location.hostname === "localhost"
    ? "http://localhost:8082/portfolio-service/api/portfolio" // local gateway
    : `${window.location.origin}/portfolio-service/api/portfolio`; // Render gateway
let sortState = { column: null, asc: true };
let positions = [];     // cache of positions from API
let editingId = null;   // null => create, otherwise update
let cmpMap = {};        // CMP cache (if your backend provides, wire it here)
let dayPctMap = {};      // ticker -> daily change % (e.g. +1.23 or -0.75)

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
      const res = await fetch(url, { headers: authHeader() });
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
    case "dayPct":   return dir * ((computeDayPct(a.ticker) ?? 0) - (computeDayPct(b.ticker) ?? 0));
    default: return 0;
  }
}


async function loadCMPForTickers(tickers){
  // naive loop; you can batch on backend later
  for (const t of tickers){
    try{
      const res = await fetch(`/portfolio-service/api/portfolio/quotes/price?ticker=${encodeURIComponent(t)}`,
        { headers: authHeader() });
      if (res.ok){
        const p = await res.json(); // {ticker, price}
        cmpMap[p.ticker] = p.price;
      }
    } catch(_){}
  }
}




function authHeader() {
  const t = localStorage.getItem(tokenKey);
  return { "Authorization": "Bearer " + t, "Content-Type": "application/json" };
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
  const res = await fetch(`${API_BASE}/positions`, { headers: authHeader() });
  if (!res.ok) throw new Error(`Load positions failed (${res.status})`);
  positions = await res.json();
}

function authHeaderGET() {
  const t = localStorage.getItem(tokenKey);
  return { "Authorization": "Bearer " + t, "Accept": "application/json" };
}
function authHeaderJSON() {
  const t = localStorage.getItem(tokenKey);
  return {
    "Authorization": "Bearer " + t,
    "Accept": "application/json",
    "Content-Type": "application/json"
  };
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
    const res = await fetch(url, { headers: authHeaderGET(), cache: 'no-store' });
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
    tbody.innerHTML = `<tr><td colspan="12" class="muted">No positions yet.</td></tr>`;
    updateKPIs([]);
    return;
  }

  let rowsHtml = "";
  const enriched = [];

  view.forEach((p, i) => {
    const qty = Number(p.quantity ?? 0);
    const buy = Number(p.buyPrice ?? 0);
    const invested = isFinite(qty * buy) ? qty * buy : 0;

    const cmp = computeCMP(p.ticker);
    const dayPct = computeDayPct(p.ticker);         // may be null
    const current = cmp != null ? qty * Number(cmp) : null;

    const pl  = current != null ? (current - invested) : null;
    const plp = (current != null && invested > 0) ? (pl * 100 / invested) : null;

    // per-row daily change value (based on current value)
    const rowDayDeltaVal = (current != null && dayPct != null) ? (current * dayPct / 100) : null;

    enriched.push({ invested, current, pl, plp, rowDayDeltaVal, currentForWeight: current, dayPct });

    rowsHtml += `
      <tr>
        <td>${i + 1}</td>
        <td>${p.ticker}</td>
        <td>${qty}</td>
        <td class="num">${fmtINR(buy)}</td>
        <td>${p.buyDate ?? ""}</td>
        <td class="num">${fmtINR(invested)}</td>
        <td class="num">${cmp != null ? fmtINR(cmp) : "–"}</td>
        <td class="num">${current != null ? fmtINR(current) : "–"}</td>
        <td class="num" style="color:${(pl ?? 0) >= 0 ? 'green':'red'}">${pl != null ? fmtINR(pl) : "–"}</td>
        <td class="num" style="color:${(plp ?? 0) >= 0 ? 'green':'red'}">${plp != null ? fmtINR(plp) + "%" : "–"}</td>
        <td class="num" style="color:${(dayPct ?? 0) >= 0 ? 'green':'red'}">${dayPct != null ? fmtINR(dayPct) + "%" : "–"}</td>
        <td>${p.notes ? p.notes.replace(/</g,"&lt;") : ""}</td>
        <td class="actions">
          <button class="btn btn-outline" onclick="openEdit(${p.id})">Edit</button>
          <button class="btn btn-danger" onclick="removePos(${p.id})">Delete</button>
        </td>
      </tr>
    `;
  });


  tbody.innerHTML = rowsHtml;
  updateKPIs(enriched);
}

function updateKPIs(enriched) {
  let totInvested = 0, totCurrent = 0, haveCurrent=false;
  let totDayDeltaVal = 0, totCurrentForWeight = 0;

  enriched.forEach(e => {
    totInvested += e.invested || 0;
    if (e.current !== null) { totCurrent += e.current; haveCurrent = true; }

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
  const res = await fetch(url, { headers: authHeader() });
  if (!res.ok) throw new Error("Unknown ticker");
  return res.json(); // {ticker,name}
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

  const res = await fetch(url, {
    method,
    headers: authHeader(),
    body: JSON.stringify(body)
  });

  if (!res.ok) {
    const t = await res.text().catch(()=> "");
    showErr(`Save failed (${res.status}) ${t}`);
    return;
  }
  closeModal();
  await refresh();
}


function authHeader() {
  const t = localStorage.getItem(tokenKey);
  return {
    "Authorization": "Bearer " + t,
    "Content-Type": "application/json",
    "Accept": "application/json"
  };
}


async function removePos(id) {
  if (!confirm("Delete this position?")) return;
  const res = await fetch(`${API_BASE}/positions/${id}`, { method: "DELETE", headers: authHeader() });
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
    await loadPrices(); // fills cmpMap / dayPctMap
    renderTable(byId("filter").value);

    // ⬇️ fetch the summary after data is ready
    if (!aiOnce) { aiOnce = true; loadAiSummary(); }   // no spamming
  } catch (e) {
    console.error(e);
    byId("rows").innerHTML =
      `<tr><td colspan="12" class="muted">Failed to load portfolio.</td></tr>`;
    byId('ai-summary-wrap').style.display = 'none';
  }
}

async function loadAiSummary() {
  try {
    const res = await fetch(`/portfolio-service/api/portfolio/ai-summary`, {
      headers: authHeaderGET(),
      cache: 'no-store'
    });
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
byId("logout-btn").addEventListener("click", () => {
  localStorage.removeItem(tokenKey);
  location.href = "index.html";
});

/* ---------- boot ---------- */
(function init(){
  const token = localStorage.getItem(tokenKey);
  if (!token) { location.href = "index.html"; return; }
  refresh();
})();
