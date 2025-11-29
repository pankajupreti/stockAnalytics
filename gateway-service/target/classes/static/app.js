const tokenKey = "access_token";
let currentPage = 0;

// --- Login ---
document.getElementById("login-btn").addEventListener("click", () => {
  window.location.href = "http://localhost:8082/oauth-service/oauth2/authorization/google";
});

// --- Logout ---
document.getElementById("logout-btn").addEventListener("click", () => {
  localStorage.removeItem(tokenKey);
  currentPage = 0;
  document.getElementById("dashboard-section").style.display = "none";
  document.getElementById("login-section").style.display = "block";
});


async function loadUserInfo(token) {
  const res = await fetch("/oauth-service/api/userinfo", {
    headers: { Authorization: "Bearer " + token }
  });
  if (res.ok) {
    const info = await res.json();
    const name = info.name || info.email || info.subject || "User";
    document.getElementById("welcome-text").textContent = `Welcome, ${name}!`;
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

// --- Fetch dashboard data ---
async function fetchDashboard(token) {
  try {
    const params = new URLSearchParams({
      search: document.getElementById("search").value,
      minMarketCap: document.getElementById("minMarketCap").value,
      minDailyChange: document.getElementById("minDailyChange").value,
      sortBy: document.getElementById("sortBy").value,
      order: document.getElementById("order").value,
      page: currentPage,
      pageSize: document.getElementById("pageSize").value
    });

    const res = await fetch("http://localhost:8082/reporting-service/api/dashboard?" + params.toString(), {
      headers: { "Authorization": "Bearer " + token }
    });

    if (!res.ok) throw new Error("Unauthorized or token expired");
    const data = await res.json();
    renderDashboard(data.stocks || []);
  } catch (err) {
    console.error("Dashboard fetch failed:", err);
    localStorage.removeItem(tokenKey);
    document.getElementById("dashboard-section").style.display = "none";
    document.getElementById("login-section").style.display = "block";
  }
}

// --- Render table ---
function renderDashboard(stocks) {
  const container = document.getElementById("dashboard-content");
  if (!stocks.length) {
    container.innerHTML = "<p>No data available</p>";
    return;
  }

  let table = "<table><thead><tr>";
  table += "<th>#</th><th>Ticker</th><th>Name</th><th class='text-right'>CMP</th>";
  table += "<th class='text-right'>Daily %</th><th class='text-right'>Week %</th><th class='text-right'>Month %</th><th class='text-right'>Market Cap</th>";
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

    table += `<tr>
      <td>${idx + 1 + currentPage * pageSize}</td>
      <td>${s.ticker || "-"}</td>
      <td title="${s.name || "-"}">${s.name || "-"}</td>
      <td class="text-right">${fmtNum(s.cmp)}</td>
      <td class="text-right ${dailyClass}">${fmtNum(dayVal)}</td>
      <td class="text-right ${weekClass}">${fmtNum(weekVal)}</td>
      <td class="text-right ${monthClass}">${fmtNum(monthVal)}</td>
      <td class="text-right">${fmtNum(s.marketCap)}</td>
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
    if (n == null) return "-";
    const num = Number(n);
    if (!Number.isFinite(num)) return "-";
    // show up to 2 decimals for percentages & cmp
    return Math.abs(num) >= 1000 ? num.toLocaleString() : num.toFixed(2).replace(/\.00$/,'');
  }
}


// --- Show dashboard ---
function showDashboard(token) {
  document.getElementById("login-section").style.display = "none";
  document.getElementById("dashboard-section").style.display = "block";
  //loadUserInfo(token);
    // greet by name from JWT
    const claims = parseJwt(token);
    const name = claims.name || claims.email || claims.sub || "User";
    document.getElementById("welcome-text").textContent = `Welcome, ${name}!`;
  fetchDashboard(token);
}

// --- Apply filters ---
document.getElementById("apply-filters").addEventListener("click", () => {
  currentPage = 0;
  const token = localStorage.getItem(tokenKey);
  if (token) fetchDashboard(token);
});

// --- Pagination ---
document.getElementById("prev-page").addEventListener("click", () => {
  if (currentPage > 0) {
    currentPage--;
    const token = localStorage.getItem(tokenKey);
    if (token) fetchDashboard(token);
  }
});

document.getElementById("next-page").addEventListener("click", () => {
  currentPage++;
  const token = localStorage.getItem(tokenKey);
  if (token) fetchDashboard(token);
});

// --- Init ---
(function init() {
  try {
    if (window.location.hash.startsWith("#access_token=")) {
      const token = window.location.hash.split("=")[1];
      localStorage.setItem(tokenKey, token);
      window.location.hash = "";
      showDashboard(token);
      return;
    }

    const storedToken = localStorage.getItem(tokenKey);
    if (storedToken) showDashboard(storedToken);
  } catch (e) {
    console.error("Token handling failed", e);
  }
})();
