(() => {
    'use strict';

    const STORAGE_KEY = 'lost-found-auth';
    const appBase = new URL(document.querySelector('meta[name="app-base"]')?.content || '/', location.origin);
    let redirecting = false;

    class ApiError extends Error {
        constructor(status, message) {
            super(message);
            this.name = 'ApiError';
            this.status = status;
        }
    }

    function readSession() {
        try {
            const session = JSON.parse(localStorage.getItem(STORAGE_KEY));
            return typeof session?.token === 'string' && session.token.trim() ? session : null;
        } catch {
            return null;
        }
    }

    const initialToken = readSession()?.token;
    const url = (path = '') => new URL(path, appBase);

    function redirectToLogin(expired = false) {
        if (redirecting) return;
        redirecting = true;
        const target = url('login');
        if (expired) target.searchParams.set('reason', 'expired');
        location.replace(target.href);
    }

    function clearSession() {
        localStorage.removeItem(STORAGE_KEY);
    }

    async function responseError(response) {
        let message = `请求未能完成（HTTP ${response.status}）`;
        try {
            const body = await response.json();
            if (typeof body.message === 'string' && body.message.trim()) message = body.message;
        } catch {
            // A gateway may return a non-JSON error response.
        }
        return new ApiError(response.status, message);
    }

    async function authenticatedFetch(target, options = {}) {
        const session = readSession();
        if (!session || (initialToken && session.token !== initialToken)) {
            redirectToLogin(true);
            throw new ApiError(401, '登录状态已改变，请重新登录');
        }
        const endpoint = new URL(target, appBase);
        if (endpoint.origin !== location.origin) throw new Error('登录凭证只能发送到本站接口');
        const headers = new Headers(options.headers);
        headers.set('Authorization', `Bearer ${session.token}`);
        const response = await fetch(endpoint, { ...options, headers, cache: 'no-store' });
        if (!response.ok) {
            const error = await responseError(response);
            if (response.status === 401) {
                // Do not remove a newer login created by another tab while this request was running.
                if (readSession()?.token === session.token) clearSession();
                redirectToLogin(true);
            }
            throw error;
        }
        return response;
    }

    async function currentUser() {
        const response = await authenticatedFetch(url('api/auth/me'), {
            headers: { Accept: 'application/json' }, signal: AbortSignal.timeout(15000)
        });
        const user = await response.json();
        if (user?.userId == null || typeof user.username !== 'string') {
            throw new Error('用户信息响应格式不正确，请稍后重试');
        }
        return user;
    }

    async function login(username, password) {
        const response = await fetch(url('api/auth/login'), {
            method: 'POST', cache: 'no-store', signal: AbortSignal.timeout(15000),
            headers: { 'Content-Type': 'application/json', Accept: 'application/json' },
            body: JSON.stringify({ username: username.trim(), password })
        });
        if (!response.ok) throw await responseError(response);
        const result = await response.json();
        if (typeof result?.token !== 'string' || !result.token.trim()) {
            throw new Error('登录响应缺少凭证，请稍后重试');
        }
        try {
            localStorage.setItem(STORAGE_KEY, JSON.stringify({ token: result.token }));
        } catch {
            throw new Error('浏览器无法保存登录状态，请允许本站使用本地存储后重试');
        }
    }

    async function logout() {
        const token = readSession()?.token;
        await authenticatedFetch(url('api/auth/logout'), {
            method: 'POST', signal: AbortSignal.timeout(15000)
        });
        if (readSession()?.token === token) clearSession();
        redirectToLogin();
    }

    async function register({ username, password, realName, phone }) {
        const response = await fetch(url('api/auth/register'), {
            method: 'POST', cache: 'no-store', signal: AbortSignal.timeout(15000),
            headers: { 'Content-Type': 'application/json', Accept: 'application/json' },
            body: JSON.stringify({
                username: username.trim(), password,
                realName: realName.trim(), phone: phone.trim()
            })
        });
        if (!response.ok) throw await responseError(response);
        // 注册不创建登录会话；凭证仅由登录接口签发。
        return response.json();
    }

    function errorMessage(error) {
        if (error.name === 'TimeoutError' || error.name === 'AbortError') {
            return '请求超时，请稍后重试。';
        }
        if (error instanceof TypeError) return '无法连接服务，请检查网络或稍后重试。';
        return error.message || '请求未能完成，请稍后重试。';
    }

    // Another tab logging out or switching accounts must hide this tab's conversation too.
    window.addEventListener('storage', (event) => {
        if ((event.key === STORAGE_KEY || event.key === null) && readSession()?.token !== initialToken) {
            redirectToLogin();
        }
    });
    window.addEventListener('pageshow', (event) => {
        if (event.persisted) location.reload();
    });

    window.LostFoundAuth = Object.freeze({
        hasSession: () => Boolean(readSession()), url, redirectToLogin,
        fetch: authenticatedFetch, currentUser, login, register, logout, errorMessage
    });
})();
