(function () {
  const tokenKey = "access_token";
  const apiUrl = "/reporting-service/api/market-breadth";

  // Check auth on load - wait for token-utils.js to potentially refresh token
  function getToken() {
    return localStorage.getItem(tokenKey);
  }

  // Don't check token immediately - let token-utils.js handle initialization
  // The fetchWithAuth will handle 401 and refresh

  // logout aligns with dashboard behavior - also remove refresh_token
  const logoutBtn = document.getElementById("logout-btn");
  if (logoutBtn) {
    logoutBtn.addEventListener("click", async () => {
      const refreshToken = localStorage.getItem("refresh_token");
      if (refreshToken) {
        try {
          await fetch("/oauth-service/token/revoke", {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify({ refreshToken: refreshToken })
          });
        } catch (e) { console.log("Token revoke failed:", e); }
      }
      localStorage.removeItem(tokenKey);
      localStorage.removeItem("refresh_token");
      window.location.href = "index.html";
    });
  }

  const $ = (id) => document.getElementById(id);
  $("apply").addEventListener("click", fetchBreadth);

  // Expose functions globally for onclick handlers
  window.openSectorDrilldown = openSectorDrilldown;
  window.closeSectorModal = closeSectorModal;

  // initial load
  fetchBreadth();

  async function fetchBreadth() {
    try {
      const params = new URLSearchParams();
      if ($("minMarketCap").value) params.set("minMarketCap", $("minMarketCap").value);
      if ($("t1").value) params.set("t1", $("t1").value);
      if ($("t2").value) params.set("t2", $("t2").value);
      if ($("t3").value) params.set("t3", $("t3").value);

      // Use fetchWithAuth for automatic token refresh on 401
      const res = await fetchWithAuth(`${apiUrl}?${params.toString()}`);

      if (!res.ok) { renderError(`Error ${res.status}`); return; }

      const b = await res.json();
      renderTable(b);
      renderSectorLeaders(b.sectorLeaders || []);
    } catch (e) {
      console.error(e);
      renderError("Failed to load breadth.");
    }
  }

  function renderError(msg) {
    $("breadth-table").innerHTML =
      `<tr><th>Market Breadth</th><td style="color:#b91c1c;">${msg}</td></tr>`;
  }

  function renderTable(b) {
    const gr = b.red === 0 ? b.green : (b.green / b.red);
    const rows = [
      ["Total Stocks", fmt(b.total)],
      ["Green", `<span class="text-success">${fmt(b.green)}</span>`],
      ["Red", `<span class="text-danger">${fmt(b.red)}</span>`],
      ["Green %", `${fmtPct(b.greenPct)}%`],
      ["Green:Red", isFinite(gr) ? gr.toFixed(2) : "∞"],
      [`≥${num($("t1").value,3)}%`, fmt(b.above3)],
      [`≥${num($("t2").value,5)}%`, fmt(b.above5)],
      [`≥${num($("t3").value,8)}%`, fmt(b.above8)],
      [`≤ -${num($("t1").value,3)}%`, fmt(b.below3)],
      [`≤ -${num($("t2").value,5)}%`, fmt(b.below5)],
      [`≤ -${num($("t3").value,8)}%`, fmt(b.below8)],
    ];

    $("breadth-table").innerHTML = rows
      .map(([k, v]) => `<tr><th>${k}</th><td>${v}</td></tr>`)
      .join("");
  }

  function renderSectorLeaders(sectors) {
    const tbody = $("sector-tbody");
    if (!sectors || sectors.length === 0) {
      tbody.innerHTML = '<tr><td colspan="5" style="text-align:center; color:var(--muted);">No sector data available</td></tr>';
      return;
    }

    tbody.innerHTML = sectors.map(s => {
      const avgClass = s.avgDailyChange >= 0 ? 'text-success' : 'text-danger';
      const avgSign = s.avgDailyChange >= 0 ? '+' : '';

      // Format top gainers as clickable chips
      const topGainersHtml = (s.topGainers || []).slice(0, 3).map(g => {
        const ticker = g.ticker ? g.ticker.replace('NSE:', '') : '';
        const changeClass = g.dailyChange >= 0 ? 'text-success' : 'text-danger';
        const sign = g.dailyChange >= 0 ? '+' : '';
        return `<span style="background:#f0fdf4; padding:2px 8px; border-radius:4px; margin-right:6px; font-size:12px;">
          ${ticker} <span class="${changeClass}">${sign}${g.dailyChange.toFixed(1)}%</span>
        </span>`;
      }).join('');

      return `
        <tr style="cursor:pointer;" onclick="openSectorDrilldown('${s.sector.replace(/'/g, "\\'")}')">
          <td style="font-weight:500;">${s.sector}</td>
          <td style="text-align:right;">${s.stockCount}</td>
          <td style="text-align:right;" class="${avgClass}">${avgSign}${s.avgDailyChange.toFixed(2)}%</td>
          <td style="text-align:right;">${s.greenPct.toFixed(0)}%</td>
          <td>${topGainersHtml || '-'}</td>
        </tr>
      `;
    }).join('');
  }

  async function openSectorDrilldown(sectorName) {
    $("sector-modal-title").textContent = sectorName + " - Stocks";
    $("sector-modal-backdrop").style.display = "flex";
    $("sector-stocks-tbody").innerHTML = '<tr><td colspan="5" style="text-align:center;">Loading...</td></tr>';

    try {
      const params = new URLSearchParams();
      params.set("name", sectorName);
      if ($("minMarketCap").value) params.set("minMarketCap", $("minMarketCap").value);
      params.set("sortBy", "dailyChange");
      params.set("order", "desc");

      // Use fetchWithAuth for automatic token refresh on 401
      const res = await fetchWithAuth(`${apiUrl}/sector?${params.toString()}`);

      if (!res.ok) {
        $("sector-stocks-tbody").innerHTML = '<tr><td colspan="5" style="color:red;">Failed to load</td></tr>';
        return;
      }

      const data = await res.json();
      renderSectorStocks(data.stocks || []);
    } catch (e) {
      console.error(e);
      $("sector-stocks-tbody").innerHTML = '<tr><td colspan="5" style="color:red;">Error loading stocks</td></tr>';
    }
  }

  function renderSectorStocks(stocks) {
    const tbody = $("sector-stocks-tbody");
    if (!stocks || stocks.length === 0) {
      tbody.innerHTML = '<tr><td colspan="5" style="text-align:center; color:var(--muted);">No stocks found</td></tr>';
      return;
    }

    tbody.innerHTML = stocks.map(s => {
      const ticker = s.ticker ? s.ticker.replace('NSE:', '') : '';
      const changeClass = (s.dailyChange || 0) >= 0 ? 'text-success' : 'text-danger';
      const sign = (s.dailyChange || 0) >= 0 ? '+' : '';
      const mcap = s.marketCap ? (s.marketCap / 1).toFixed(0) : '-';

      return `
        <tr>
          <td style="font-weight:500;">${ticker}</td>
          <td>${s.name || '-'}</td>
          <td style="text-align:right;">${s.cmp ? s.cmp.toFixed(2) : '-'}</td>
          <td style="text-align:right;" class="${changeClass}">${sign}${(s.dailyChange || 0).toFixed(2)}%</td>
          <td style="text-align:right;">${mcap}</td>
        </tr>
      `;
    }).join('');
  }

  function closeSectorModal() {
    $("sector-modal-backdrop").style.display = "none";
  }

  // Close modal on backdrop click
  $("sector-modal-backdrop").addEventListener("click", (e) => {
    if (e.target.id === "sector-modal-backdrop") closeSectorModal();
  });

  function fmt(n) {
    if (n == null) return "-";
    if (typeof n === "number" && n % 1 !== 0) return n.toFixed(2);
    return String(n);
  }
  function fmtPct(n) {
    if (n == null) return "-";
    return Number(n).toFixed(2);
  }
  function num(v, dflt) {
    const n = Number(v);
    return Number.isFinite(n) ? n : dflt;
  }
})();
