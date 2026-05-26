/**
 * Shared Token Utility (HttpOnly Cookie Edition)
 *
 * Tokens are stored in HttpOnly cookies (not readable by JS).
 * A non-HttpOnly "token_meta" cookie carries expiry/email/name for UI use.
 * The gateway CookieToAuthFilter converts the cookie to an Authorization header
 * so downstream services work without changes.
 *
 * Usage: Add <script src="token-utils.js"></script> to your HTML before other scripts.
 */

let tokenRefreshTimeoutId = null;

// Enable verbose logging for debugging
const DEBUG_AUTH = true;

function getLocalTimestamp() {
    const now = new Date();
    return now.toLocaleTimeString('en-IN', {
        hour12: false,
        hour: '2-digit',
        minute: '2-digit',
        second: '2-digit',
        fractionalSecondDigits: 3
    });
}

function formatLocalDateTime(date) {
    return date.toLocaleString('en-IN', {
        year: 'numeric',
        month: '2-digit',
        day: '2-digit',
        hour: '2-digit',
        minute: '2-digit',
        second: '2-digit',
        hour12: false,
        timeZoneName: 'short'
    });
}

function authLog(...args) {
    if (DEBUG_AUTH) {
        console.log(`[AUTH ${getLocalTimestamp()}]`, ...args);
    }
}

function authWarn(...args) {
    console.warn(`[AUTH ${getLocalTimestamp()}]`, ...args);
}

function authError(...args) {
    console.error(`[AUTH ${getLocalTimestamp()}]`, ...args);
}

/**
 * Parse the token_meta cookie (non-HttpOnly, readable by JS).
 * Format: "exp=<epoch>&email=<email>&name=<name>"
 */
function getTokenMeta() {
    try {
        const match = document.cookie.split('; ').find(c => c.startsWith('token_meta='));
        if (!match) return null;
        const value = decodeURIComponent(match.split('=').slice(1).join('='));
        const params = new URLSearchParams(value);
        return {
            exp: parseInt(params.get('exp') || '0', 10),
            email: params.get('email') || '',
            name: params.get('name') || ''
        };
    } catch (e) {
        authError("Failed to parse token_meta cookie:", e.message);
        return null;
    }
}

/**
 * Check if the session has a valid (non-expired) token based on token_meta cookie.
 */
function hasValidSession() {
    const meta = getTokenMeta();
    if (!meta || !meta.exp) return false;
    return (meta.exp * 1000) > Date.now();
}

/**
 * Check if token is expired or about to expire based on token_meta cookie.
 */
function isTokenExpired(bufferMinutes = 0) {
    const meta = getTokenMeta();
    if (!meta || !meta.exp) {
        authWarn("No token_meta cookie - session invalid");
        return true;
    }
    const expiresAt = meta.exp * 1000;
    const bufferMs = bufferMinutes * 60 * 1000;
    const now = Date.now();
    const isExpired = now >= (expiresAt - bufferMs);
    const timeLeft = Math.round((expiresAt - now) / 1000);
    const expiryLocal = formatLocalDateTime(new Date(expiresAt));
    authLog(`Token expires at ${expiryLocal} (in ${timeLeft}s), buffer=${bufferMinutes}min, expired=${isExpired}`);
    return isExpired;
}

/**
 * Refresh access token using refresh_token cookie (sent automatically by browser).
 */
async function refreshAccessToken() {
    authLog("Attempting token refresh via cookie...");

    try {
        const res = await fetch("/oauth-service/token/refresh", {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            credentials: "include",
            body: JSON.stringify({})  // empty body; server reads cookie
        });

        authLog("Refresh response status:", res.status);

        if (res.ok) {
            const data = await res.json();
            if (data.access_token) {
                authLog("Token refreshed successfully via cookie");
                // Server has already set new cookies (access_token, token_meta)
                scheduleTokenRefresh();
                return true;
            } else {
                authError("Refresh response OK but no access_token in body:", data);
            }
        } else {
            const errorText = await res.text().catch(() => "");
            authError("Token refresh failed:", res.status, errorText);
        }
    } catch (e) {
        authError("Token refresh network error:", e.message);
    }
    return false;
}

/**
 * Schedule proactive token refresh (5 minutes before expiry)
 */
function scheduleTokenRefresh() {
    if (tokenRefreshTimeoutId) {
        authLog("Clearing previous scheduled refresh");
        clearTimeout(tokenRefreshTimeoutId);
        tokenRefreshTimeoutId = null;
    }

    const meta = getTokenMeta();
    if (!meta || !meta.exp) {
        authWarn("Cannot schedule refresh - no token_meta cookie");
        return;
    }

    const expiresAt = meta.exp * 1000;
    const now = Date.now();
    const refreshBuffer = 5 * 60 * 1000; // 5 minutes before expiry
    const refreshIn = expiresAt - now - refreshBuffer;
    const expiryLocal = formatLocalDateTime(new Date(expiresAt));
    const refreshAtLocal = formatLocalDateTime(new Date(now + refreshIn));

    authLog(`Token expires at ${expiryLocal}, will refresh at ${refreshAtLocal} (in ${Math.round(refreshIn/1000)}s)`);

    if (refreshIn > 0) {
        authLog(`Scheduling proactive refresh in ${Math.round(refreshIn / 60000)} minutes`);
        tokenRefreshTimeoutId = setTimeout(async () => {
            authLog("Proactive token refresh triggered by timer");
            const success = await refreshAccessToken();
            if (!success) {
                authWarn("Proactive refresh failed - user may be logged out on next API call");
            }
        }, refreshIn);
    } else if (refreshIn > -refreshBuffer) {
        authLog("Token expiring very soon, refreshing immediately");
        refreshAccessToken();
    } else {
        authWarn("Token already expired, cannot schedule refresh");
    }
}

/**
 * Get auth header for API calls.
 * With HttpOnly cookies, the browser sends the cookie automatically.
 * The gateway CookieToAuthFilter converts it to Authorization: Bearer header.
 * Returns empty object — no manual header needed.
 */
function getAuthHeader() {
    return {};
}

/**
 * Fetch with automatic token refresh on 401.
 * Uses credentials: 'include' so cookies are sent automatically.
 */
async function fetchWithAuth(url, options = {}) {
    authLog(`fetchWithAuth: ${options.method || 'GET'} ${url}`);

    // Ensure cookies are sent with every request
    options.credentials = "include";

    // Merge any provided headers (no Authorization header needed)
    options.headers = {
        ...options.headers
    };

    let response;
    try {
        response = await fetch(url, options);
    } catch (e) {
        authError(`fetchWithAuth network error for ${url}:`, e.message);
        throw e;
    }

    authLog(`fetchWithAuth response: ${response.status} for ${url}`);

    // If 401, try to refresh token and retry once
    if (response.status === 401) {
        authWarn(`Got 401 for ${url}, attempting token refresh...`);
        const refreshed = await refreshAccessToken();
        if (refreshed) {
            authLog("Refresh succeeded, retrying original request");
            response = await fetch(url, options);
            authLog(`Retry response: ${response.status} for ${url}`);
        } else {
            // Refresh failed - redirect to login
            authError("LOGOUT TRIGGER: Refresh failed after 401, redirecting to login");
            authError("  URL that caused 401:", url);
            window.location.href = "index.html";
            throw new Error("Session expired");
        }
    }

    return response;
}

/**
 * Initialize token management on page load.
 * Checks session validity via token_meta cookie, triggers refresh if needed.
 */
function initTokenManagement() {
    authLog("=== initTokenManagement called ===");
    authLog("  Page:", window.location.pathname);

    const meta = getTokenMeta();
    authLog("  token_meta present:", !!meta);

    if (!meta || !meta.exp) {
        // No token_meta cookie — try refreshing (browser may have refresh_token cookie)
        authLog("No token_meta cookie, attempting refresh...");
        refreshAccessToken().then(success => {
            if (!success) {
                authError("LOGOUT TRIGGER: initTokenManagement - no session and refresh failed");
                window.location.href = "index.html";
            } else {
                authLog("Refresh succeeded during init");
            }
        });
        return;
    }

    // Check if token is expired
    if (isTokenExpired()) {
        authLog("Token is expired, attempting refresh...");
        refreshAccessToken().then(success => {
            if (!success) {
                authError("LOGOUT TRIGGER: initTokenManagement - token expired and refresh failed");
                window.location.href = "index.html";
            } else {
                authLog("Refresh succeeded for expired token");
            }
        });
        return;
    }

    // Token is valid - schedule refresh
    authLog("Token is valid, scheduling proactive refresh");
    scheduleTokenRefresh();
}

// Auto-initialize when script loads (unless on index.html/login page)
if (!window.location.pathname.includes("index.html") && window.location.pathname !== "/") {
    authLog("Auto-init enabled for page:", window.location.pathname);
    document.addEventListener("DOMContentLoaded", initTokenManagement);
} else {
    authLog("Auto-init SKIPPED for login page:", window.location.pathname);
}

/**
 * Admin email whitelist
 */
const ADMIN_EMAILS = ["panky070@gmail.com"];

function isAdmin() {
    const meta = getTokenMeta();
    if (!meta) return false;
    return ADMIN_EMAILS.includes(meta.email);
}

/**
 * Hide admin-only elements for non-admin users
 */
function hideAdminElements() {
    if (!isAdmin()) {
        document.querySelectorAll('.admin-only').forEach(el => el.style.display = 'none');
    }
}

// Run after DOM is ready
if (!window.location.pathname.includes("index.html") && window.location.pathname !== "/") {
    document.addEventListener("DOMContentLoaded", hideAdminElements);
}

/**
 * Plan/subscription management
 */
let _cachedPlan = null;
let _planFetchPromise = null;

const PRO_PAGES = [
    "portfolio-analytics.html",
    "pead-scanner.html",
    "alerts.html",
    "results-analysis.html",
    "good-results.html",
    "pnl-report.html",
    "announcements.html"
];

/**
 * Fetch user plan from backend (cached for session)
 */
async function getUserPlan() {
    if (_cachedPlan) return _cachedPlan;
    if (_planFetchPromise) return _planFetchPromise;

    _planFetchPromise = (async () => {
        try {
            const res = await fetchWithAuth("/oauth-service/api/user/plan");
            if (res.ok) {
                _cachedPlan = await res.json();
                authLog("User plan:", _cachedPlan.plan, "features:", _cachedPlan.features?.length);
                return _cachedPlan;
            }
        } catch (e) {
            authError("Failed to fetch user plan:", e.message);
        }
        // Default to FREE if fetch fails
        return { plan: "FREE", features: ["dashboard", "marketbreadth", "portfolio", "52w-breakouts"], isAdmin: false };
    })();

    const result = await _planFetchPromise;
    _planFetchPromise = null;
    return result;
}

/**
 * Check if current page requires Pro and show paywall if needed.
 * Call this on DOMContentLoaded for gated pages.
 */
async function checkProAccess() {
    const page = window.location.pathname.split("/").pop();
    if (!PRO_PAGES.includes(page)) return true;

    // Admin always has access
    if (isAdmin()) return true;

    const plan = await getUserPlan();
    if (plan.freeForAll || plan.plan === "PRO" || plan.plan === "ADMIN" || plan.isAdmin) return true;

    // Show paywall
    showPaywall();
    return false;
}

/**
 * Show paywall overlay blocking page content
 */
function showPaywall() {
    // Blur all page content and prevent scrolling
    document.body.style.overflow = "hidden";
    document.querySelectorAll("body > *").forEach(el => {
        if (el.id !== "paywall-overlay") {
            el.style.filter = "blur(8px)";
            el.style.pointerEvents = "none";
            el.style.userSelect = "none";
        }
    });

    const meta = getTokenMeta();
    const prefillEmail = meta ? meta.email : "";

    const overlay = document.createElement("div");
    overlay.id = "paywall-overlay";
    overlay.innerHTML = `
        <div style="position:fixed;inset:0;background:rgba(15,23,42,.6);backdrop-filter:blur(4px);z-index:9999;display:flex;align-items:center;justify-content:center;">
            <div style="background:#fff;border-radius:16px;padding:40px 36px;max-width:440px;width:90%;text-align:center;box-shadow:0 25px 50px rgba(0,0,0,.25);">
                <div style="font-size:48px;margin-bottom:16px;">&#128274;</div>
                <h2 style="margin:0 0 8px;font-size:22px;color:#0f172a;">Pro Feature</h2>
                <p style="color:#6b7280;font-size:15px;margin:0 0 24px;line-height:1.5;">
                    This page is available to Pro subscribers.
                    Upgrade to unlock Portfolio Analytics, PEAD Scanner, Alerts, Results Analysis, and more.
                </p>
                <div style="font-size:32px;font-weight:700;color:#2563eb;margin-bottom:4px;">&#8377;299<span style="font-size:16px;font-weight:400;color:#6b7280;">/month</span></div>
                <p style="font-size:12px;color:#9ca3af;margin:0 0 24px;">Cancel anytime</p>
                <button onclick="startPayment()" style="background:#2563eb;color:#fff;border:none;padding:14px 36px;border-radius:10px;font-size:16px;font-weight:600;cursor:pointer;width:100%;margin-bottom:12px;">
                    Upgrade to Pro
                </button>
                <a href="dashboard.html" style="display:block;color:#6b7280;font-size:14px;text-decoration:none;margin-top:8px;">Back to Dashboard</a>
            </div>
        </div>
    `;
    document.body.appendChild(overlay);
}

/**
 * Start Razorpay payment
 */
async function startPayment() {
    try {
        const res = await fetchWithAuth("/oauth-service/api/payment/create-order", {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify({ amount: 29900 }) // amount in paise
        });

        if (!res.ok) {
            alert("Failed to create payment order. Please try again.");
            return;
        }

        const order = await res.json();
        const meta = getTokenMeta();

        const options = {
            key: order.razorpayKeyId,
            amount: order.amount,
            currency: "INR",
            name: "Stock Analytics",
            description: "Pro Subscription - 1 Month",
            order_id: order.orderId,
            handler: async function(response) {
                // Verify payment on backend
                const verifyRes = await fetchWithAuth("/oauth-service/api/payment/verify", {
                    method: "POST",
                    headers: { "Content-Type": "application/json" },
                    body: JSON.stringify({
                        razorpay_order_id: response.razorpay_order_id,
                        razorpay_payment_id: response.razorpay_payment_id,
                        razorpay_signature: response.razorpay_signature
                    })
                });

                if (verifyRes.ok) {
                    _cachedPlan = null; // clear cache
                    alert("Payment successful! You now have Pro access.");
                    window.location.reload();
                } else {
                    alert("Payment verification failed. Please contact support.");
                }
            },
            prefill: {
                email: meta ? meta.email : ""
            },
            theme: { color: "#2563eb" }
        };

        const rzp = new Razorpay(options);
        rzp.open();
    } catch (e) {
        authError("Payment error:", e.message);
        alert("Payment failed. Please try again.");
    }
}

// Export for use by other scripts
window.TokenUtils = {
    getTokenMeta,
    hasValidSession,
    isTokenExpired,
    refreshAccessToken,
    scheduleTokenRefresh,
    getAuthHeader,
    fetchWithAuth,
    initTokenManagement,
    isAdmin,
    hideAdminElements,
    getUserPlan,
    checkProAccess,
    startPayment
};
