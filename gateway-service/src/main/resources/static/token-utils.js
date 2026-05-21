/**
 * Shared Token Utility
 * Include this in ALL authenticated pages to handle token refresh automatically.
 *
 * Usage: Add <script src="token-utils.js"></script> to your HTML before other scripts.
 */

const TOKEN_KEY = "access_token";
const REFRESH_TOKEN_KEY = "refresh_token";
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
 * Parse JWT to extract claims
 */
function parseJwt(token) {
    try {
        const base64Url = token.split('.')[1];
        const base64 = base64Url.replace(/-/g, '+').replace(/_/g, '/');
        return JSON.parse(atob(base64));
    } catch (e) {
        authError("Failed to parse JWT:", e.message);
        return {};
    }
}

/**
 * Check if token is expired or about to expire
 */
function isTokenExpired(token, bufferMinutes = 0) {
    try {
        const claims = parseJwt(token);
        if (!claims.exp) {
            authWarn("Token has no exp claim");
            return true;
        }
        const expiresAt = claims.exp * 1000;
        const bufferMs = bufferMinutes * 60 * 1000;
        const now = Date.now();
        const isExpired = now >= (expiresAt - bufferMs);
        const timeLeft = Math.round((expiresAt - now) / 1000);
        const expiryLocal = formatLocalDateTime(new Date(expiresAt));
        authLog(`Token expires at ${expiryLocal} (in ${timeLeft}s), buffer=${bufferMinutes}min, expired=${isExpired}`);
        return isExpired;
    } catch (e) {
        authError("isTokenExpired error:", e.message);
        return true;
    }
}

/**
 * Refresh access token using refresh token
 */
async function refreshAccessToken() {
    const refreshToken = localStorage.getItem(REFRESH_TOKEN_KEY);
    if (!refreshToken) {
        authWarn("No refresh token in localStorage - cannot refresh");
        return false;
    }

    authLog("Attempting token refresh with refresh_token:", refreshToken.substring(0, 20) + "...");

    try {
        const res = await fetch("/oauth-service/token/refresh", {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify({ refreshToken: refreshToken })
        });

        authLog("Refresh response status:", res.status);

        if (res.ok) {
            const data = await res.json();
            if (data.access_token) {
                localStorage.setItem(TOKEN_KEY, data.access_token);
                authLog("Token refreshed successfully, new token:", data.access_token.substring(0, 30) + "...");
                scheduleTokenRefresh(data.access_token);
                return true;
            } else {
                authError("Refresh response OK but no access_token in body:", data);
            }
        } else {
            const errorText = await res.text().catch(() => "");
            authError("Token refresh failed:", res.status, errorText);
            if (res.status === 401) {
                authWarn("Refresh token rejected (401), removing from localStorage");
                localStorage.removeItem(REFRESH_TOKEN_KEY);
            }
        }
    } catch (e) {
        authError("Token refresh network error:", e.message);
    }
    return false;
}

/**
 * Schedule proactive token refresh (5 minutes before expiry)
 */
function scheduleTokenRefresh(token) {
    if (tokenRefreshTimeoutId) {
        authLog("Clearing previous scheduled refresh");
        clearTimeout(tokenRefreshTimeoutId);
        tokenRefreshTimeoutId = null;
    }

    try {
        const claims = parseJwt(token);
        if (!claims.exp) {
            authWarn("Cannot schedule refresh - token has no exp claim");
            return;
        }

        const expiresAt = claims.exp * 1000;
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
    } catch (e) {
        authError("Failed to schedule token refresh:", e.message);
    }
}

/**
 * Get auth header for API calls
 */
function getAuthHeader() {
    const token = localStorage.getItem(TOKEN_KEY);
    return { "Authorization": "Bearer " + token };
}

/**
 * Fetch with automatic token refresh on 401
 */
async function fetchWithAuth(url, options = {}) {
    const token = localStorage.getItem(TOKEN_KEY);
    authLog(`fetchWithAuth: ${options.method || 'GET'} ${url}`);
    authLog(`  Token present: ${!!token}, length: ${token ? token.length : 0}`);

    // Merge auth header with provided headers
    options.headers = {
        ...getAuthHeader(),
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
            // Update headers with new token
            options.headers = {
                ...getAuthHeader(),
                ...options.headers
            };
            response = await fetch(url, options);
            authLog(`Retry response: ${response.status} for ${url}`);
        } else {
            // Refresh failed - redirect to login
            authError("LOGOUT TRIGGER: Refresh failed after 401, redirecting to login");
            authError("  URL that caused 401:", url);
            authError("  Tokens in storage: access=", !!localStorage.getItem(TOKEN_KEY), "refresh=", !!localStorage.getItem(REFRESH_TOKEN_KEY));
            localStorage.removeItem(TOKEN_KEY);
            localStorage.removeItem(REFRESH_TOKEN_KEY);
            window.location.href = "index.html";
            throw new Error("Session expired");
        }
    }

    return response;
}

/**
 * Initialize token management on page load
 */
function initTokenManagement() {
    authLog("=== initTokenManagement called ===");
    authLog("  Page:", window.location.pathname);

    const token = localStorage.getItem(TOKEN_KEY);
    const refreshToken = localStorage.getItem(REFRESH_TOKEN_KEY);

    authLog("  access_token present:", !!token, token ? `(${token.length} chars)` : "");
    authLog("  refresh_token present:", !!refreshToken, refreshToken ? `(${refreshToken.length} chars)` : "");

    if (!token) {
        // No token - check if we have refresh token
        if (refreshToken) {
            authLog("No access token but have refresh token, attempting refresh...");
            refreshAccessToken().then(success => {
                if (!success) {
                    authError("LOGOUT TRIGGER: initTokenManagement - no access token and refresh failed");
                    window.location.href = "index.html";
                } else {
                    authLog("Refresh succeeded during init");
                }
            });
        } else {
            authError("LOGOUT TRIGGER: initTokenManagement - no tokens at all");
            window.location.href = "index.html";
        }
        return;
    }

    // Check if token is expired
    if (isTokenExpired(token)) {
        authLog("Token is expired, checking for refresh token...");
        if (refreshToken) {
            authLog("Have refresh token, attempting refresh...");
            refreshAccessToken().then(success => {
                if (!success) {
                    authError("LOGOUT TRIGGER: initTokenManagement - token expired and refresh failed");
                    localStorage.removeItem(TOKEN_KEY);
                    localStorage.removeItem(REFRESH_TOKEN_KEY);
                    window.location.href = "index.html";
                } else {
                    authLog("Refresh succeeded for expired token");
                }
            });
        } else {
            authError("LOGOUT TRIGGER: initTokenManagement - token expired and no refresh token");
            localStorage.removeItem(TOKEN_KEY);
            window.location.href = "index.html";
        }
        return;
    }

    // Token is valid - schedule refresh
    authLog("Token is valid, scheduling proactive refresh");
    scheduleTokenRefresh(token);
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
    const token = localStorage.getItem(TOKEN_KEY);
    if (!token) return false;
    const claims = parseJwt(token);
    return ADMIN_EMAILS.includes(claims.email);
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

// Export for use by other scripts
window.TokenUtils = {
    TOKEN_KEY,
    REFRESH_TOKEN_KEY,
    parseJwt,
    isTokenExpired,
    refreshAccessToken,
    scheduleTokenRefresh,
    getAuthHeader,
    fetchWithAuth,
    initTokenManagement,
    isAdmin,
    hideAdminElements
};
