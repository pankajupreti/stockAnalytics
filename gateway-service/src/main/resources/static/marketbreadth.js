(function () {
  const tokenKey = "access_token";
  const token = localStorage.getItem(tokenKey);
  const apiUrl = "/reporting-service/api/market-breadth";

  const goLogin = () => (window.location.href = "/oauth-service/oauth2/authorization/google");
  if (!token) { goLogin(); return; }

  // logout aligns with dashboard behavior
  const logoutBtn = document.getElementById("logout-btn");
  if (logoutBtn) {
    logoutBtn.addEventListener("click", () => {
      localStorage.removeItem(tokenKey);
      window.location.href = "index.html";
    });
  }

  const $ = (id) => document.getElementById(id);
  $("apply").addEventListener("click", fetchBreadth);

  // initial load
  fetchBreadth();

  async function fetchBreadth() {
    try {
      const params = new URLSearchParams();
      if ($("minMarketCap").value) params.set("minMarketCap", $("minMarketCap").value);
      if ($("t1").value) params.set("t1", $("t1").value);
      if ($("t2").value) params.set("t2", $("t2").value);
      if ($("t3").value) params.set("t3", $("t3").value);

      const res = await fetch(`${apiUrl}?${params.toString()}`, {
        headers: { Authorization: "Bearer " + token }
      });

      if (res.status === 401) { localStorage.removeItem(tokenKey); goLogin(); return; }
      if (!res.ok) { renderError(`Error ${res.status}`); return; }

      const b = await res.json();
      renderTable(b);
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
