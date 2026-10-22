// Run with: node scripts/verify-frontend-auth.cjs
// Uses a local mock API and headless Edge; never connects to MySQL, Redis or an AI model.
const http = require('node:http');
const fs = require('node:fs');
const path = require('node:path');
const { spawn } = require('node:child_process');
const assert = require('node:assert/strict');

const root = path.resolve(__dirname, '../src/main/resources');
const previewMode = process.argv.includes('--serve');
const output = path.resolve(__dirname, '../target/frontend-auth');
fs.mkdirSync(output, { recursive: true });
const user = { userId: 7, username: 'test-user', realName: '测试用户', role: 'USER' };
let loginMode = 200;
let meMode = 200;
let logoutMode = 200;
let chatMode = 200;
let conversationMode = 201;
let registerMode = 201;
let meCalls = 0;
const chats = [];
const conversations = [];
let listMode = 200, historyMode = 200, modeSaveStatus = 200;
const historyRequests = [], modeRequests = [], claimRequests = [];
let legacyMode = null;
let claimStatus = 'PENDING';
let claimRole = 'CLAIMANT';
let claimActionStatus = 200;
const seedConversations = [
    { id: 100, sessionId: 'legacy-session', mode: null, title: '旧会话：黑色钱包', createdDate: '2026-10-20T10:00:00' },
    { id: 99, sessionId: 'restored-search', mode: 'search', title: '已保存的失物查询', createdDate: '2026-10-19T10:00:00' }
];
const logouts = [];
const logins = [];
const registrations = [];
let savedEvent = null;
let streamStyle = 'valid';
let imageStatus = 200;
const imageRequests = [];
const imagePng = Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+a7x8AAAAASUVORK5CYII=', 'base64');
const server = http.createServer((req, res) => {
    const url = new URL(req.url, 'http://localhost');
    const json = (status, body) => {
        res.writeHead(status, { 'Content-Type': 'application/json;charset=utf-8' });
        res.end(JSON.stringify(body));
    };
    if (url.pathname === '/uploads/frontend-test.png') {
        res.writeHead(200, { 'Content-Type': 'image/png' });
        res.end(imagePng);
        return;
    }
    if (/^\/api\/items\/(lost|found)\/\d+\/image$/.test(url.pathname)) {
        const status = imageStatus;
        const chunks = [];
        req.on('data', chunk => chunks.push(chunk));
        req.on('end', () => {
            imageRequests.push({ path: url.pathname, authorization: req.headers.authorization,
                contentType: req.headers['content-type'], body: Buffer.concat(chunks) });
            setTimeout(() => status === 200 ? json(200, { imageUrl: '/uploads/frontend-test.png' })
                : json(status, { message: '图片保存失败，请重试' }), 150);
        });
        return;
    }
    if (url.pathname === '/api/auth/register') {
        let body = '';
        req.on('data', chunk => { body += chunk; });
        req.on('end', () => {
            registrations.push({ data: JSON.parse(body), authorization: req.headers.authorization });
            if (registerMode !== 201) return json(registerMode, { message: registerMode === 409 ? '用户名已存在' : registerMode === 429 ? '请求过于频繁，请稍后再试' : '注册服务暂不可用' });
            json(201, { userId: 8, username: 'new-user', realName: '注册用户', phone: '13800000000', role: 'USER' });
        });
        return;
    }
    if (url.pathname === '/api/auth/login') {
        let body = '';
        req.on('data', chunk => { body += chunk; });
        req.on('end', () => {
            logins.push(JSON.parse(body));
            if (loginMode !== 200) return json(loginMode, {
                code: loginMode, message: loginMode === 401 ? '用户名或密码错误' : loginMode === 429 ? '密码错误次数过多，请稍后再试' : '登录服务暂不可用，请稍后重试'
            });
            json(200, { token: 'mock-token', ...user });
        });
        return;
    }
    if (url.pathname === '/api/auth/me') {
        meCalls++;
        if (req.headers.authorization !== 'Bearer mock-token') return json(401, { code: 401, message: '登录状态已失效' });
        return meMode === 200 ? json(200, user) : json(meMode, { code: meMode, message: '登录会话服务暂不可用' });
    }
    if (url.pathname === '/api/auth/logout') {
        logouts.push({ method: req.method, authorization: req.headers.authorization });
        return logoutMode === 200 ? (res.writeHead(200), res.end()) : json(logoutMode, { code: logoutMode, message: '退出服务暂不可用' });
    }
    const readJson = callback => {
        let body = ''; req.on('data', chunk => { body += chunk; });
        req.on('end', () => { try { callback(body ? JSON.parse(body) : {}); } catch (error) { json(400, { message: error.message }); } });
    };
    if (url.pathname === '/api/conversations' && req.method === 'GET') {
        if (listMode !== 200) return json(listMode, { message: '会话列表暂不可用' });
        const beforeId = url.searchParams.get('beforeId');
        const content = beforeId ? [seedConversations[1]] : [{ ...seedConversations[0], mode: legacyMode }, ...conversations.slice().reverse()];
        return json(200, { content, hasMore: !beforeId, nextBeforeId: beforeId ? null : 100 });
    }
    if (url.pathname === '/api/conversations' && req.method === 'POST') {
        if (req.headers.authorization !== 'Bearer mock-token') return json(401, { code: 401, message: '登录状态已失效' });
        return readJson(data => {
            if (!['lost', 'found', 'search'].includes(data.mode)) return json(400, { message: '必须提交模式' });
            if (conversationMode !== 201) return json(conversationMode, { code: conversationMode, message: '创建会话暂不可用' });
            const sessionId = `server-session-${conversations.length + 1}`;
            conversations.push({ id: conversations.length + 101, sessionId, mode: data.mode, authorization: req.headers.authorization });
            json(201, { sessionId, mode: data.mode });
        });
    }
    if (/^\/api\/conversations\/[^/]+\/mode$/.test(url.pathname) && req.method === 'PUT') {
        return readJson(data => {
            modeRequests.push({ sessionId: url.pathname.split('/')[3], ...data });
            if (modeSaveStatus === 409) { legacyMode = 'found'; return json(409, { message: '会话已设置模式' }); }
            if (modeSaveStatus !== 200) return json(modeSaveStatus, { message: '保存模式失败' });
            legacyMode = data.mode; json(200, { sessionId: 'legacy-session', mode: legacyMode });
        });
    }
    if (url.pathname === '/ai/chat-history') {
        historyRequests.push(Object.fromEntries(url.searchParams));
        if (historyMode !== 200) return json(historyMode, { message: '历史服务暂不可用' });
        const page = Number(url.searchParams.get('page'));
        const sessionId = url.searchParams.get('sessionId');
        const content = page === 0 ? [
            { id: 4, sessionId, role: '1', content: '历史回复<script>window.injected=true</script>', createdDate: '2026-10-20T10:04:00' },
            { id: 3, sessionId, role: '0', content: '历史用户消息', createdDate: '2026-10-20T10:03:00' }
        ] : [
            { id: 2, sessionId, role: '1', content: '更早助手回复', createdDate: '2026-10-20T10:02:00' },
            { id: 1, sessionId, role: '0', content: '更早用户消息', createdDate: '2026-10-20T10:01:00' }
        ];
        return json(200, { content, page: { number: page, size: 20, totalElements: 4, totalPages: 2 } });
    }
    if (url.pathname === '/ai/chat-stream') {
        if (req.method !== 'POST' || url.search) return json(405, { message: '聊天必须通过POST JSON提交' });
        return readJson(data => {
            chats.push({ ...data, method: req.method, authorization: req.headers.authorization, accept: req.headers.accept });
            if (![...conversations, ...seedConversations].some(item => item.sessionId === data.sessionId)) return json(404, { message: '聊天会话不存在或无权访问' });
            if (chatMode !== 200) return json(chatMode, { code: chatMode, message: ({ 403: '没有操作权限', 404: '登记不存在或无权修改', 409: '登记保存冲突，请重新查询后确认' })[chatMode] || '登录状态已失效' });
            res.writeHead(200, { 'Content-Type': 'application/x-ndjson;charset=UTF-8' });
            const events = [{ type: 'text', content: '登记线索已收到。\n' },
                { type: 'text', content: '<script>window.injected=true</script>' }];
            const previewRegistration = previewMode && /^(我确认|确认)/.test(data.message)
                && ['lost', 'found'].includes(conversations.find(row => row.sessionId === data.sessionId)?.mode)
                ? { type: 'registration_saved', registrationType: conversations.find(row => row.sessionId === data.sessionId).mode,
                    registrationId: conversations.length + 80, action: 'CREATE' } : null;
            const registration = savedEvent || previewRegistration;
            if (registration) events.push(registration, registration);
            let text = events.map(event => JSON.stringify(event) + '\n').join('');
            if (streamStyle === 'invalid') text += '{broken}\n';
            if (streamStyle === 'truncated') text = text.slice(0, -1);
            const bytes = Buffer.from(text);
            const cut = bytes.indexOf(Buffer.from('登记')) + 2;
            res.write(bytes.subarray(0, cut));
            setTimeout(() => res.end(bytes.subarray(cut)), 80);
        });
    }
    if (url.pathname.startsWith('/api/claims')) {
        const claim = { id: 21, lostPropertyId: 8, lostRegisterId: 9, lostPropertyName: '钱包', lostRegisterName: '黑色钱包', status: claimStatus, viewerRole: claimRole, verificationText: '<script>window.injected=true</script>', lostPropertyImageUrl: '/uploads/frontend-test.png', lostRegisterImageUrl: '/uploads/frontend-test.png' };
        claimRequests.push({ path: url.pathname, method: req.method });
        if (['/api/claims/mine', '/api/claims/incoming'].includes(url.pathname)) return json(200, { content: [claim], hasMore: false, nextBeforeId: null });
        if (url.pathname.endsWith('/contact')) return ['APPROVED', 'HANDOVER_PENDING', 'COMPLETED'].includes(claimStatus) ? json(200, { realName: '拾得人', phone: '13900000000' }) : json(403, { message: '尚未同意申请' });
        if (url.pathname === '/api/claims/21' && req.method === 'GET') return json(200, claim);
        if (req.method === 'POST') return readJson(data => {
            if (url.pathname === '/api/claims') {
                claimStatus = 'PENDING'; claimRole = 'CLAIMANT';
                claimRequests.at(-1).data = data;
            } else {
                const action = url.pathname.split('/').at(-1);
                const transitions = { approve: 'APPROVED', reject: 'REJECTED', handover: 'HANDOVER_PENDING', receive: 'COMPLETED', cancel: 'CANCELLED' };
                if (claimActionStatus !== 200) return json(claimActionStatus, { message: '申请已被处理，请刷新后重试' });
                claimStatus = transitions[action];
            }
            json(200, { ...claim, status: claimStatus });
        });
        return json(404, { message: '申请不存在' });
    }
    const template = url.pathname === '/' ? 'index' : ['/login', '/login.html'].includes(url.pathname) ? 'login' : null;
    const filename = template ? path.join(root, 'templates', `${template}.html`) : path.resolve(root, 'static', `.${url.pathname}`);
    if (!filename.startsWith(root + path.sep) || !fs.existsSync(filename) || !fs.statSync(filename).isFile()) {
        res.writeHead(404); res.end(); return;
    }
    res.setHeader('Content-Type', filename.endsWith('.css') ? 'text/css' : filename.endsWith('.js') ? 'text/javascript' : 'text/html;charset=utf-8');
    res.end(fs.readFileSync(filename));
});

const delay = ms => new Promise(resolve => setTimeout(resolve, ms));
let browser;
let socket;
const deadline = setTimeout(() => { console.error('Browser verification timed out'); process.exitCode = 1; socket?.close(); browser?.kill(); server.closeAllConnections(); server.close(); }, 90000);
(async () => {
    await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
    const origin = `http://127.0.0.1:${server.address().port}`;
    if (previewMode) {
        clearTimeout(deadline);
        console.log(`Frontend preview (mock API): ${origin}`);
        return;
    }
    const edge = process.env.EDGE_PATH || 'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe';
    browser = spawn(edge, [
        '--headless=new', '--disable-gpu', '--no-first-run', '--no-default-browser-check',
        '--remote-debugging-port=0', `--user-data-dir=${path.join(output, 'browser-profile')}`, 'about:blank'
    ], { windowsHide: true, stdio: 'ignore' });
    let port;
    const activePort = path.join(output, 'browser-profile', 'DevToolsActivePort');
    for (let i = 0; i < 80; i++) {
        try {
            port = Number(fs.readFileSync(activePort, 'utf8').split('\n')[0]);
            await fetch(`http://127.0.0.1:${port}/json/list`);
            break;
        } catch { port = null; await delay(100); }
    }
    assert.ok(port, 'Headless Edge must be available');
    const tabs = await (await fetch(`http://127.0.0.1:${port}/json/list`)).json();
    socket = new WebSocket(tabs.find(tab => tab.type === 'page').webSocketDebuggerUrl);
    await new Promise(resolve => socket.addEventListener('open', resolve, { once: true }));
    let sequence = 0;
    const pending = new Map();
    const errors = [];
    socket.addEventListener('message', event => {
        const packet = JSON.parse(event.data);
        if (packet.id) {
            const callback = pending.get(packet.id);
            if (callback) { pending.delete(packet.id); packet.error ? callback.reject(packet.error) : callback.resolve(packet.result); }
        }
        if (packet.method === 'Runtime.exceptionThrown') errors.push(packet.params.exceptionDetails.text);
    });
    const cdp = (method, params = {}) => new Promise((resolve, reject) => {
        const id = ++sequence;
        pending.set(id, { resolve, reject });
        socket.send(JSON.stringify({ id, method, params }));
    });
    const evaluate = async expression => {
        const result = await cdp('Runtime.evaluate', { expression, returnByValue: true, awaitPromise: true });
        if (result.exceptionDetails) throw new Error(result.exceptionDetails.exception?.description || result.exceptionDetails.text);
        return result.result.value;
    };
    const waitFor = async expression => {
        for (let i = 0; i < 100; i++) {
            try { if (await evaluate(expression)) return; } catch (error) {
                if (!String(error.message).includes('context')) throw error;
            }
            await delay(60);
        }
        throw new Error(`Timed out: ${expression}`);
    };
    const navigate = pathname => cdp('Page.navigate', { url: origin + pathname });
    const screenshot = async name => {
        await evaluate(`window.scrollTo(0, 0)`);
        const result = await cdp('Page.captureScreenshot', { format: 'png', captureBeyondViewport: true });
        fs.writeFileSync(path.join(output, name), Buffer.from(result.data, 'base64'));
    };
    const submitLogin = () => evaluate(`(() => { document.querySelector('#username').value = ' test-user '; document.querySelector('#password').value = ' password-with-spaces '; document.querySelector('#login-form').requestSubmit(); })()`);
    const sendChat = text => evaluate(`(() => { const input = document.querySelector('#message-input'); input.value = ${JSON.stringify(text)}; input.dispatchEvent(new Event('input')); document.querySelector('#chat-form').requestSubmit(); })()`);
    await cdp('Runtime.enable');
    await cdp('Page.enable');
    await cdp('Emulation.setDeviceMetricsOverride', { width: 1440, height: 1000, deviceScaleFactor: 1, mobile: false });
    await navigate('/');
    await waitFor(`location.pathname === '/login' && document.querySelector('#login-button')?.disabled === false`);
    assert.equal(meCalls, 0, 'Unauthenticated home must redirect without an API call');
    await screenshot('login-desktop.png');
    await evaluate(`document.querySelector('#show-register').click()`);
    await waitFor(`document.querySelector('#registration-panel').hidden === false`);
    await screenshot('register-desktop.png');
    await cdp('Emulation.setDeviceMetricsOverride', { width: 390, height: 844, deviceScaleFactor: 1, mobile: true });
    assert.equal(await evaluate(`document.documentElement.scrollWidth <= innerWidth`), true);
    await screenshot('register-mobile.png');
    await cdp('Emulation.setDeviceMetricsOverride', { width: 1440, height: 1000, deviceScaleFactor: 1, mobile: false });
    const fillRegistration = (password = ' password-with-spaces ', confirm = password) => evaluate(`(() => {
        const form = document.querySelector('#registration-form');
        form.elements.username.value = 'new-user'; form.elements.realName.value = '注册用户';
        form.elements.phone.value = '13800000000'; form.elements.password.value = ${JSON.stringify(password)};
        form.elements.confirmPassword.value = ${JSON.stringify(confirm)}; form.requestSubmit();
    })()`);
    await fillRegistration('abcdefgh', 'abcdefgi');
    assert.match(await evaluate(`document.querySelector('#register-feedback').textContent`), /不一致/);
    assert.equal(registrations.length, 0);
    await fillRegistration('密'.repeat(25));
    assert.match(await evaluate(`document.querySelector('#register-feedback').textContent`), /72字节/);
    assert.equal(registrations.length, 0);
    for (const code of [409, 429, 503]) {
        registerMode = code;
        await fillRegistration();
        await waitFor(`document.querySelector('#register-button').disabled === false && !document.querySelector('#register-feedback').hidden`);
        assert.match(await evaluate(`document.querySelector('#register-feedback').textContent`), code === 409 ? /已存在/ : code === 429 ? /频繁/ : /暂不可用/);
        assert.equal(await evaluate(`document.querySelector('#registration-form').elements.realName.value`), '注册用户');
    }
    registerMode = 201;
    await fillRegistration();
    await waitFor(`document.querySelector('#registration-panel').hidden === true && document.querySelector('#login-feedback').textContent.includes('注册成功')`);
    assert.deepEqual(registrations.at(-1).data, { username: 'new-user', password: ' password-with-spaces ', realName: '注册用户', phone: '13800000000' });
    assert.equal(registrations.at(-1).authorization, undefined);
    assert.equal(await evaluate(`localStorage.getItem('lost-found-auth')`), null);
    assert.equal(await evaluate(`document.querySelector('#password').value`), '');
    assert.equal(await evaluate(`document.querySelector('#username').value`), 'new-user');
    await evaluate(`document.querySelector('#password').value = 'example'; document.querySelector('#toggle-password').click()`);
    assert.equal(await evaluate(`document.querySelector('#password').type`), 'text');
    await evaluate(`document.querySelector('#toggle-password').click()`);
    assert.equal(await evaluate(`document.querySelector('#password').type`), 'password');
    for (const status of [401, 429, 503]) {
        loginMode = status;
        await submitLogin();
        await waitFor(`document.querySelector('#login-button').disabled === false && !document.querySelector('#login-feedback').hidden`);
        assert.equal(await evaluate(`location.pathname`), '/login');
        assert.equal(await evaluate(`localStorage.getItem('lost-found-auth')`), null);
        assert.match(await evaluate(`document.querySelector('#login-feedback').textContent`), status === 401 ? /用户名或密码错误/ : status === 429 ? /错误次数过多/ : /暂不可用/);
    }
    loginMode = 200;
    await submitLogin();
    await waitFor(`location.pathname === '/' && document.querySelector('#app-shell')?.hidden === false`);
    assert.deepEqual(logins.at(-1), { username: 'test-user', password: ' password-with-spaces ' });
    assert.equal(await evaluate(`document.querySelector('#current-user-name').textContent`), '测试用户');
    assert.equal(await evaluate(`document.querySelector('#current-user-role').textContent`), '用户');
    assert.equal(await evaluate(`localStorage.getItem('lost-found-auth').includes('password')`), false);
    await screenshot('workspace-desktop.png');
    assert.equal(await evaluate(`document.querySelectorAll('.main-nav .nav-item').length`), 4, 'All four item workflows must share one navigation level');
    const chooseRegistrationPhoto = (formId, mode = 'valid') => evaluate(`(() => {
        const bytes = Uint8Array.from(atob('${imagePng.toString('base64')}'), c => c.charCodeAt(0));
        const file = ${mode === 'oversize' ? "new File([new Uint8Array(5 * 1024 * 1024 + 1)], 'large.png', {type:'image/png'})"
            : mode === 'invalid' ? "new File(['text'], 'note.txt', {type:'text/plain'})"
            : mode === 'corrupt' ? "new File(['not a PNG'], 'broken.png', {type:'image/png'})"
            : "new File([bytes], 'wallet.png', {type:'image/png'})"};
        const transfer = new DataTransfer(); transfer.items.add(file);
        const input = document.querySelector('#' + '${formId}' + ' .photo-input'); input.files = transfer.files;
        input.dispatchEvent(new Event('change'));
    })()`);
    await evaluate(`document.querySelector('.main-nav [data-action="register"]').click()`);
    await evaluate(`(() => { const form = document.querySelector('#register-form'); form.elements.namedItem('item').value = '娴嬭瘯閽卞寘'; form.elements.features.value = '钃濊壊锛屾湁鎸備欢'; })()`);
    for (const [mode, expected] of [['invalid', 'JPG 或 PNG'], ['oversize', '不能超过'], ['corrupt', '无法预览']]) {
        await chooseRegistrationPhoto('register-form', mode);
        await waitFor(`document.querySelector('[data-photo-feedback="lost"]').textContent.includes('${expected}')`);
        assert.equal(await evaluate(`document.querySelector('[data-photo-preview="lost"]').hidden`), true);
        assert.equal(await evaluate(`document.querySelector('#register-form .photo-input').files.length`), 0);
    }
    await chooseRegistrationPhoto('register-form');
    await waitFor(`document.querySelector('[data-photo-preview="lost"]').hidden === false`);
    assert.equal(await evaluate(`window.LostFoundImages.selectedPhoto('lost').name`), 'wallet.png');
    const chatCountBeforePhotoConfirm = chats.length;
    await evaluate(`document.querySelector('[data-photo-remove="lost"]').click(); document.querySelector('#register-dialog').close()`);
    assert.equal(chats.length, chatCountBeforePhotoConfirm, 'Photo preview and removal must not submit a registration');
    await cdp('Emulation.setDeviceMetricsOverride', { width: 390, height: 844, deviceScaleFactor: 1, mobile: true });
    assert.equal(await evaluate(`document.documentElement.scrollWidth <= innerWidth`), true);
    await screenshot('registration-photo-mobile.png');
    await cdp('Emulation.setDeviceMetricsOverride', { width: 1440, height: 1000, deviceScaleFactor: 1, mobile: false });
    const conversationsBeforeFoundPhoto = conversations.length;
    await evaluate(`document.querySelector('.main-nav [data-action="found"]').click()`);
    await chooseRegistrationPhoto('found-form');
    await waitFor(`document.querySelector('[data-photo-preview="found"]').hidden === false`);
    assert.equal(await evaluate(`window.LostFoundImages.selectedPhoto('found').name`), 'wallet.png');
    await evaluate(`document.querySelector('[data-photo-remove="found"]').click()`);
    assert.equal(await evaluate(`document.querySelector('#found-form .photo-input').files.length`), 0);
    assert.equal(await evaluate(`window.LostFoundImages.selectedPhoto('found')`), null);
    await evaluate(`document.querySelector('#found-dialog').close()`);
    assert.equal(conversations.length, conversationsBeforeFoundPhoto, 'Photo preview must not create chat conversations');
    assert.equal(conversations.length, 0, 'Blank conversations must not create server records');
    conversationMode = 503;
    await sendChat('创建失败后保留草稿');
    await waitFor(`document.querySelector('#chat-form').getAttribute('aria-busy') === 'false' && document.querySelector('#toast').textContent.includes('创建对话失败')`);
    assert.equal(chats.length, 0, 'Failed conversation creation must not call AI');
    assert.equal(await evaluate(`document.querySelector('#message-input').value`), '创建失败后保留草稿');
    assert.equal(await evaluate(`document.querySelectorAll('.message.user').length`), 0);
    conversationMode = 201;
    await sendChat('我捡到了一个黑色钱包');
    await waitFor(`document.querySelectorAll('.message.assistant:not(.pending)').length === 2 && document.querySelector('#chat-form').getAttribute('aria-busy') === 'false'`);
    assert.equal(chats.at(-1).authorization, 'Bearer mock-token');
    assert.match(chats.at(-1).sessionId, /^server-session-/);
    assert.equal(chats.at(-1).userId, undefined);
    assert.equal(conversations.at(-1).authorization, 'Bearer mock-token');
    assert.equal(chats.at(-1).sessionId, conversations.at(-1).sessionId);
    assert.equal(await evaluate(`window.injected === undefined`), true);
    const lostConversationId = chats.at(-1).sessionId;
    const createdCount = conversations.length;
    await sendChat('钱包上有小熊挂件');
    await waitFor(`document.querySelectorAll('.message.assistant:not(.pending)').length === 3 && document.querySelector('#chat-form').getAttribute('aria-busy') === 'false'`);
    assert.equal(conversations.length, createdCount, 'Follow-up messages must reuse the existing server conversation');
    assert.equal(chats.at(-1).sessionId, lostConversationId);
    for (const [action, formId, expectedText] of [
        ['register', 'register-form', '我想登记丢失物品'],
        ['found', 'found-form', '我捡到了物品，想办理拾物登记']
    ]) {
        const previousCount = chats.length;
        await evaluate(`document.querySelector('.main-nav [data-action="${action}"]').click()`);
        await evaluate(`(() => { const form = document.querySelector('#${formId}'); form.elements.namedItem('item').value = '测试背包'; form.elements.features.value = '蓝色，带挂件'; form.requestSubmit(); })()`);
        await waitFor(`document.querySelector('#chat-form').getAttribute('aria-busy') === 'false' && document.querySelector('#${formId}').closest('dialog').open === false`);
        assert.equal(chats.length, previousCount + 1);
        assert.equal(chats.at(-1).authorization, 'Bearer mock-token');
        assert.ok(chats.at(-1).message.includes(expectedText));
        assert.ok(chats.at(-1).message.includes('蓝色，带挂件'));
        assert.ok(chats.at(-1).message.includes('联系人使用当前账号资料'));
        assert.equal(await evaluate(`document.querySelector('#${formId} [name="phone"]') === null`), true);
    }
    assert.notEqual(chats.at(-1).sessionId, lostConversationId, 'Found registration must use its own conversation');
    const foundConversationId = chats.at(-1).sessionId;
    for (const kind of ['found', 'lost']) {
        await evaluate(`document.querySelector('[data-action="supplement"]').click()`);
        await evaluate(`(() => { const form = document.querySelector('#supplement-form'); form.elements.kind.value = '${kind}'; form.elements.namedItem('item').value = '测试背包'; form.elements.features.value = '拉链有白色标志'; form.requestSubmit(); })()`);
        await waitFor(`document.querySelector('#chat-form').getAttribute('aria-busy') === 'false' && !document.querySelector('#supplement-dialog').open`);
        assert.ok(chats.at(-1).message.includes(`已有的${kind === 'found' ? '拾物' : '丢失'}登记`));
        assert.ok(chats.at(-1).message.includes('查不到时不要新增登记'));
        if (kind === 'found') assert.equal(chats.at(-1).sessionId, foundConversationId);
        const beforeConfirm = chats.length;
        const beforeId = chats.at(-1).sessionId;
        await evaluate(`document.querySelector('#confirm-details').click()`);
        assert.equal(chats.length, beforeConfirm, 'Confirmation must first become a reviewable draft');
        await evaluate(`document.querySelector('#chat-form').requestSubmit()`);
        await waitFor(`document.querySelector('#chat-form').getAttribute('aria-busy') === 'false' && document.querySelector('#message-input').value === ''`);
        assert.equal(chats.at(-1).sessionId, beforeId, 'Confirmation must reuse the business conversation');
    }
    const beforeSearchId = chats.at(-1).sessionId;
    await evaluate(`document.querySelector('.main-nav [data-action="search"]').click()`);
    await evaluate(`(() => { const form = document.querySelector('#search-form'); form.elements.namedItem('item').value = '钱包'; form.elements.features.value = '黑色，有小熊挂件'; form.requestSubmit(); })()`);
    await waitFor(`document.querySelector('#chat-form').getAttribute('aria-busy') === 'false' && !document.querySelector('#search-dialog').open`);
    assert.match(chats.at(-1).message, /只查询拾物线索，不新增或修改登记/);
    assert.notEqual(chats.at(-1).sessionId, beforeSearchId);
    assert.equal(chats.at(-1).authorization, 'Bearer mock-token');
    assert.equal(await evaluate(`document.querySelector('#confirm-details').hidden`), true);
    const searchId = chats.at(-1).sessionId;
    await sendChat('还有一个蓝色钥匙扣，请继续查询');
    await waitFor(`document.querySelector('#chat-form').getAttribute('aria-busy') === 'false'`);
    assert.equal(chats.at(-1).sessionId, searchId);
    assert.match(chats.at(-1).message, /^本轮只查询拾物线索，不新增或修改登记/);
    chatMode = 403;
    await sendChat('继续登记');
    await waitFor(`document.querySelector('.message.failed') !== null`);
    assert.match(await evaluate(`document.querySelector('.message.failed').textContent`), /没有操作权限/);
    assert.equal(await evaluate(`location.pathname`), '/');
    assert.ok(await evaluate(`localStorage.getItem('lost-found-auth')`));
    for (const code of [404, 409]) {
        chatMode = code;
        const failedCount = await evaluate(`document.querySelectorAll('.message.failed').length`);
        await sendChat('补充我的登记');
        await waitFor(`document.querySelectorAll('.message.failed').length === ${failedCount + 1}`);
        assert.match(await evaluate(`[...document.querySelectorAll('.message.failed')].at(-1).textContent`), code === 404 ? /不存在或无权修改/ : /保存冲突/);
    }
    // Recover a saved conversation, paginate messages, and keep raw model content as text.
    chatMode = 200;
    await evaluate(`document.querySelector('#history-open').click()`);
    await waitFor(`document.querySelector('#history-session-list').textContent.includes('旧会话')`);
    await evaluate(`[...document.querySelectorAll('#history-session-list button')].find(b => b.textContent.includes('旧会话')).click()`);
    await waitFor(`document.querySelector('#chat-body').textContent.includes('历史用户消息')`);
    assert.equal(await evaluate(`document.querySelector('#mode-backfill').hidden`), false);
    assert.equal(await evaluate(`document.querySelector('#message-input').readOnly`), true);
    assert.equal(await evaluate(`window.injected === undefined`), true);
    assert.equal(await evaluate(`document.querySelector('#chat-body .message').classList.contains('user')`), true);
    await evaluate(`document.querySelector('#history-older').click()`);
    await waitFor(`document.querySelectorAll('#chat-body .message').length === 4`);
    assert.match(await evaluate(`document.querySelector('#chat-body .message').textContent`), /更早用户消息/);
    assert.equal(historyRequests.at(-1).page, '1');
    modeSaveStatus = 503;
    await evaluate(`document.querySelector('#legacy-mode').value='search'; document.querySelector('#mode-save').click()`);
    await waitFor(`document.querySelector('#toast').textContent.includes('模式保存失败') && !document.querySelector('#mode-save').disabled`);
    assert.equal(await evaluate(`document.querySelector('#message-input').readOnly`), true);
    modeSaveStatus = 200;
    await evaluate(`document.querySelector('#mode-save').click()`);
    await waitFor(`document.querySelector('#mode-backfill').hidden && !document.querySelector('#message-input').readOnly`);
    assert.equal(legacyMode, 'search');
    // Reload an old NULL-mode row to exercise a concurrent backfill conflict independently.
    legacyMode = null;
    await evaluate(`document.querySelector('#history-open').click(); document.querySelector('#history-refresh').click()`);
    await waitFor(`document.querySelector('#mode-backfill').hidden === false && !document.querySelector('#history-list-more').disabled`);
    await evaluate(`document.querySelector('#history-dialog').close()`);
    modeSaveStatus = 409;
    await evaluate(`document.querySelector('#mode-save').click()`);
    await waitFor(`document.querySelector('[data-mode="found"]').getAttribute('aria-pressed') === 'true' && !document.querySelector('#message-input').readOnly`);
    assert.equal(modeRequests.at(-1).sessionId, 'legacy-session');
    const countBeforeRestore = conversations.length;
    await sendChat('补充旧会话，挂件是蓝色');
    await waitFor(`document.querySelector('#chat-form').getAttribute('aria-busy') === 'false'`);
    assert.equal(chats.at(-1).sessionId, 'legacy-session');
    assert.equal(chats.at(-1).method, 'POST');
    assert.equal(conversations.length, countBeforeRestore);
    await evaluate(`document.querySelector('#history-open').click(); document.querySelector('#history-list-more').click()`);
    await waitFor(`document.querySelector('#history-session-list').textContent.includes('已保存的失物查询')`);
    historyMode = 503;
    await evaluate(`[...document.querySelectorAll('#history-session-list button')].find(b => b.textContent.includes('已保存的失物查询')).click()`);
    await waitFor(`!document.querySelector('#history-retry').hidden`);
    assert.equal(await evaluate(`document.querySelector('#message-input').readOnly`), true);
    assert.equal(await evaluate(`document.querySelectorAll('#chat-body .message').length`), 0);
    historyMode = 200;
    await evaluate(`document.querySelector('#history-retry').click()`);
    await waitFor(`!document.querySelector('#message-input').readOnly`);
    await sendChat('继续查询我的钱包');
    await waitFor(`document.querySelector('#chat-form').getAttribute('aria-busy') === 'false'`);
    assert.equal(chats.at(-1).sessionId, 'restored-search');
    assert.match(chats.at(-1).message, /^本轮只查询拾物线索/);
    // Claim application, status-specific actions, contact access and completed return.
    await evaluate(`document.querySelector('#claims-open').click()`);
    await waitFor(`document.querySelector('#claim-list').textContent.includes('申请 #21')`);
    await evaluate(`document.querySelector('#claim-list button').click()`);
    await waitFor(`!document.querySelector('#claim-detail').hidden && document.querySelector('#claims-dialog').getAttribute('aria-busy') === 'false'`);
    assert.equal(await evaluate(`document.querySelectorAll('#claim-list .item-photo img').length`), 1);
    assert.equal(await evaluate(`document.querySelectorAll('#claim-detail .item-photo img').length`), 2);
    assert.equal(await evaluate(`(() => { const node=document.createElement('div'); window.LostFoundImages.appendImage(node, undefined, 'missing'); window.LostFoundImages.appendImage(node, 'https://example.com/x.png', 'external'); return node.childElementCount; })()`), 0);
    assert.equal(await evaluate(`document.querySelector('#claim-detail').textContent.includes('查看对方联系方式')`), false);
    assert.equal(await evaluate(`window.injected === undefined`), true);
    await evaluate(`document.querySelector('#claim-create-open').click(); const f=document.querySelector('#claim-create-form'); f.elements.lostPropertyId.value='8'; f.elements.lostRegisterId.value='9'; f.elements.verificationText.value='钱包内有蓝色卡片'; f.requestSubmit()`);
    await waitFor(`document.querySelector('#claim-feedback').textContent.includes('申请已提交')`);
    assert.deepEqual(claimRequests.findLast(r => r.path === '/api/claims' && r.method === 'POST').data, { lostPropertyId: 8, lostRegisterId: 9, verificationText: '钱包内有蓝色卡片' });
    await evaluate(`window.confirm=()=>true; [...document.querySelectorAll('#claim-detail button')].find(b=>b.textContent==='取消申请').click()`);
    await waitFor(`document.querySelector('#claim-detail').textContent.includes('已取消') && document.querySelector('#claims-dialog').getAttribute('aria-busy') === 'false'`);
    claimStatus = 'PENDING';
    claimRole = 'FINDER';
    await evaluate(`document.querySelector('[data-claim-tab="incoming"]').click()`);
    await waitFor(`document.querySelector('#claims-dialog').getAttribute('aria-busy') === 'false'`);
    await evaluate(`document.querySelector('#claim-list button').click()`);
    await waitFor(`document.querySelector('#claim-detail').textContent.includes('同意认领')`);
    claimActionStatus = 409;
    await evaluate(`window.confirm=()=>true; [...document.querySelectorAll('#claim-detail button')].find(b=>b.textContent==='同意认领').click()`);
    await waitFor(`document.querySelector('#claim-feedback').textContent.includes('操作未确认') && document.querySelector('#claims-dialog').getAttribute('aria-busy') === 'false'`);
    assert.equal(claimRequests.filter(r => r.path.endsWith('/approve')).length, 1, 'Conflicted mutations are never automatically retried');
    claimActionStatus = 200;
    await evaluate(`window.confirm=()=>true; [...document.querySelectorAll('#claim-detail button')].find(b=>b.textContent==='同意认领').click()`);
    await waitFor(`document.querySelector('#claim-detail').textContent.includes('确认已交接') && document.querySelector('#claims-dialog').getAttribute('aria-busy') === 'false'`);
    await evaluate(`[...document.querySelectorAll('#claim-detail button')].find(b=>b.textContent==='查看对方联系方式').click()`);
    await waitFor(`document.querySelector('#claim-contact').textContent.includes('13900000000')`);
    await evaluate(`[...document.querySelectorAll('#claim-detail button')].find(b=>b.textContent==='确认已交接').click()`);
    await waitFor(`document.querySelector('#claim-detail').textContent.includes('已交接，待确认收到') && document.querySelector('#claims-dialog').getAttribute('aria-busy') === 'false'`);
    claimRole = 'CLAIMANT';
    await evaluate(`document.querySelector('[data-claim-tab="mine"]').click()`);
    await waitFor(`document.querySelector('#claims-dialog').getAttribute('aria-busy') === 'false'`);
    await evaluate(`document.querySelector('#claim-list button').click()`);
    await waitFor(`document.querySelector('#claim-detail').textContent.includes('确认已收到物品')`);
    await evaluate(`[...document.querySelectorAll('#claim-detail button')].find(b=>b.textContent==='确认已收到物品').click()`);
    await waitFor(`document.querySelector('#claim-detail').textContent.includes('已归还') && document.querySelector('#claims-dialog').getAttribute('aria-busy') === 'false'`);
    assert.equal(await evaluate(`document.querySelector('#claim-detail').textContent.includes('取消申请')`), false);
    await screenshot('claims-completed-desktop.png');
    await evaluate(`document.querySelector('#claims-dialog').close()`);
    assert.equal(await evaluate(`document.querySelector('#claim-contact').textContent`), '');
    logoutMode = 503;
    await evaluate(`document.querySelector('#logout-button').click()`);
    await waitFor(`document.querySelector('#toast').textContent.includes('退出失败')`);
    assert.ok(await evaluate(`localStorage.getItem('lost-found-auth')`));
    logoutMode = 200;
    await evaluate(`document.querySelector('#logout-button').click()`);
    await waitFor(`location.pathname === '/login' && document.querySelector('#login-button')?.disabled === false`);
    assert.equal(await evaluate(`localStorage.getItem('lost-found-auth')`), null);
    assert.deepEqual(logouts.at(-1), { method: 'POST', authorization: 'Bearer mock-token' });
    await cdp('Emulation.setDeviceMetricsOverride', { width: 390, height: 844, deviceScaleFactor: 1, mobile: true });
    assert.equal(await evaluate(`document.documentElement.scrollWidth <= innerWidth`), true);
    await screenshot('login-mobile.png');
    await submitLogin();
    await waitFor(`location.pathname === '/' && document.querySelector('#app-shell')?.hidden === false`);
    assert.equal(await evaluate(`document.documentElement.scrollWidth <= innerWidth`), true);
    await screenshot('workspace-mobile.png');
    await evaluate(`document.querySelector('[data-mode="search"]').click(); document.querySelector('#quick-register').click()`);
    await waitFor(`document.querySelector('#search-dialog').open`);
    assert.equal(await evaluate(`document.documentElement.scrollWidth <= innerWidth`), true);
    await screenshot('search-mobile.png');
    await evaluate(`document.querySelector('#search-dialog').close()`);
    await evaluate(`document.querySelector('#history-open').click()`);
    await waitFor(`document.querySelector('#history-dialog').open`);
    assert.equal(await evaluate(`document.documentElement.scrollWidth <= innerWidth`), true);
    await screenshot('history-mobile.png');
    await evaluate(`document.querySelector('#history-dialog').close(); document.querySelector('#claims-open').click()`);
    await waitFor(`document.querySelector('#claims-dialog').getAttribute('aria-busy') === 'false'`);
    assert.equal(await evaluate(`document.querySelector('#claims-dialog').scrollWidth <= document.querySelector('#claims-dialog').clientWidth`), true);
    await screenshot('claims-mobile.png');
    await evaluate(`document.querySelector('#claims-dialog').close()`);
    // A photo belongs to its registration conversation until a trusted CREATE event binds its ID.
    const startPhotoRegistration = async (type) => {
        await evaluate(`document.querySelector('#new-chat').click(); document.querySelector('.main-nav [data-action="${type === 'lost' ? 'register' : 'found'}"]').click()`);
        const formId = type === 'lost' ? 'register-form' : 'found-form';
        await chooseRegistrationPhoto(formId);
        await waitFor(`document.querySelector('[data-photo-preview="${type}"]').hidden === false`);
        await evaluate(`(() => { const form = document.querySelector('#${formId}'); form.elements.namedItem('item').value = '测试钱包'; form.elements.features.value = '黑色，带挂件'; form.requestSubmit(); })()`);
        await waitFor(`document.querySelector('#chat-form').getAttribute('aria-busy') === 'false'`);
    };
    const uploadCountBefore = imageRequests.length;
    conversationMode = 503;
    await startPhotoRegistration('lost');
    assert.match(await evaluate(`document.querySelector('#registration-photo-status').textContent`), /确认登记后自动上传/);
    assert.equal(imageRequests.length, uploadCountBefore, 'Failed conversation creation must preserve the photo without uploading');
    conversationMode = 201;
    await evaluate(`document.querySelector('#chat-form').requestSubmit()`);
    await waitFor(`document.querySelector('#chat-form').getAttribute('aria-busy') === 'false' && document.querySelector('#message-input').value === ''`);
    const photoSessionId = chats.at(-1).sessionId;
    await evaluate(`document.querySelector('#new-chat').click()`);
    assert.equal(await evaluate(`document.querySelector('#registration-photo-state').hidden`), true, 'New sessions must not inherit a photo');
    await evaluate(`document.querySelector('#history-open').click(); [...document.querySelectorAll('#history-session-list button')].find(button => button.textContent.includes('我想登记丢失物品')).click()`);
    assert.equal(await evaluate(`document.querySelector('#registration-photo-state').hidden`), false);
    savedEvent = { type: 'registration_saved', registrationType: 'lost', registrationId: 81, action: 'CREATE' };
    imageStatus = 500;
    await sendChat('确认以上登记内容');
    await waitFor(`document.querySelector('#chat-form').getAttribute('aria-busy') === 'false' && document.querySelector('#registration-photo-state').dataset.state === 'error'`);
    assert.equal(chats.at(-1).sessionId, photoSessionId);
    assert.equal(chats.at(-1).accept, 'application/x-ndjson');
    assert.equal(imageRequests.length, uploadCountBefore + 1, 'Duplicate registration events must not duplicate uploads');
    assert.equal(imageRequests.at(-1).path, '/api/items/lost/81/image');
    assert.equal(imageRequests.at(-1).authorization, 'Bearer mock-token');
    assert.match(imageRequests.at(-1).contentType, /^multipart\/form-data; boundary=/);
    assert.ok(imageRequests.at(-1).body.includes(imagePng));
    assert.ok(imageRequests.at(-1).body.includes(Buffer.from('name="file"; filename="wallet.png"')));
    await screenshot('registration-photo-retry-mobile.png');
    const chatCountBeforeRetry = chats.length;
    const conversationCountBeforeRetry = conversations.length;
    imageStatus = 200;
    await evaluate(`document.querySelector('#registration-photo-retry').click(); document.querySelector('#registration-photo-retry').click()`);
    await waitFor(`document.querySelector('#registration-photo-state').dataset.state === 'uploaded' && document.querySelector('#chat-form').getAttribute('aria-busy') === 'false'`);
    assert.equal(imageRequests.length, uploadCountBefore + 2, 'Repeated retry clicks must produce one upload');
    assert.equal(chats.length, chatCountBeforeRetry, 'Upload retries must not call the model');
    assert.equal(conversations.length, conversationCountBeforeRetry, 'Upload retries must not create another registration conversation');
    await waitFor(`document.querySelector('#registration-photo-thumbnail').complete && document.querySelector('#registration-photo-thumbnail').naturalWidth > 0`);
    savedEvent = null;
    await startPhotoRegistration('found');
    assert.match(await evaluate(`document.querySelector('#registration-photo-status').textContent`), /确认登记后自动上传/);
    const beforeFoundUpload = imageRequests.length;
    savedEvent = { type: 'registration_saved', registrationType: 'found', registrationId: 82, action: 'CREATE' };
    await sendChat('确认拾物登记');
    await waitFor(`document.querySelector('#registration-photo-state').dataset.state === 'uploaded' && document.querySelector('#chat-form').getAttribute('aria-busy') === 'false'`);
    assert.equal(imageRequests.at(-1).path, '/api/items/found/82/image');
    assert.equal(imageRequests.length, beforeFoundUpload + 1);
    assert.equal(await evaluate(`document.documentElement.scrollWidth <= innerWidth`), true);
    await screenshot('registration-photo-saved-mobile.png');
    for (const width of [320, 390, 740]) {
        await cdp('Emulation.setDeviceMetricsOverride', { width, height: 844, deviceScaleFactor: 1, mobile: true });
        assert.equal(await evaluate(`document.documentElement.scrollWidth <= innerWidth`), true, `No page overflow at ${width}px`);
        assert.equal(await evaluate(`getComputedStyle(document.querySelector('.main-nav')).display`), 'grid');
        assert.equal(await evaluate(`(() => { const boxes=[...document.querySelectorAll('.main-nav button')].map(button=>button.getBoundingClientRect()); return boxes.every(box => box.width > 0 && box.right <= innerWidth) && boxes[0].top === boxes[1].top && boxes[2].top === boxes[3].top; })()`), true);
        assert.equal(await evaluate(`document.querySelector('#registration-photo-state').scrollWidth <= document.querySelector('#registration-photo-state').clientWidth`), true);
    }
    await cdp('Emulation.setDeviceMetricsOverride', { width: 1440, height: 1000, deviceScaleFactor: 1, mobile: false });
    await screenshot('registration-photo-saved-desktop.png');
    savedEvent = null;
    await startPhotoRegistration('lost');
    const beforeMismatch = imageRequests.length;
    for (const event of [
        { type: 'registration_saved', registrationType: 'lost', registrationId: 83, action: 'UPDATE' },
        { type: 'registration_saved', registrationType: 'found', registrationId: 83, action: 'CREATE' },
        { type: 'registration_saved', registrationType: 'lost', registrationId: -1, action: 'CREATE' }
    ]) {
        savedEvent = event;
        await sendChat('核实本次登记');
        await waitFor(`document.querySelector('#chat-form').getAttribute('aria-busy') === 'false'`);
        assert.equal(imageRequests.length, beforeMismatch, 'Updates, mismatched types and invalid IDs must never bind a new photo');
    }
    savedEvent = null;
    for (const style of ['invalid', 'truncated']) {
        streamStyle = style;
        const failedCount = await evaluate(`document.querySelectorAll('.message.failed').length`);
        await sendChat('测试不完整响应');
        await waitFor(`document.querySelector('#chat-form').getAttribute('aria-busy') === 'false'`);
        assert.equal(await evaluate(`document.querySelectorAll('.message.failed').length`), failedCount + 1);
        assert.equal(imageRequests.length, beforeMismatch);
    }
    savedEvent = { type: 'registration_saved', registrationType: 'lost', registrationId: 84, action: 'CREATE' };
    streamStyle = 'invalid';
    await sendChat('确认保存后发生响应中断');
    await waitFor(`document.querySelector('#chat-form').getAttribute('aria-busy') === 'false'`);
    assert.equal(imageRequests.length, beforeMismatch, 'A malformed response must not trigger automatic upload');
    assert.equal(await evaluate(`document.querySelector('#registration-photo-retry').hidden`), false, 'An observed saved ID remains available for upload-only retry');
    savedEvent = null;
    streamStyle = 'valid';
    await evaluate(`document.querySelector('#registration-photo-remove').click()`);
    assert.equal(await evaluate(`document.querySelector('#registration-photo-state').hidden`), true);
    meMode = 503;
    await cdp('Page.reload');
    await waitFor(`document.querySelector('#auth-retry')?.hidden === false`);
    assert.equal(await evaluate(`document.querySelector('#app-shell').hidden`), true);
    assert.ok(await evaluate(`localStorage.getItem('lost-found-auth')`));
    meMode = 200;
    await evaluate(`document.querySelector('#auth-retry').click()`);
    await waitFor(`document.querySelector('#app-shell').hidden === false`);
    chatMode = 401;
    await sendChat('测试登录过期');
    await waitFor(`location.pathname === '/login' && document.querySelector('#login-feedback')?.textContent.includes('已失效')`);
    assert.equal(await evaluate(`localStorage.getItem('lost-found-auth')`), null);
    // Restored or invalid credentials must be checked against /me before showing the workspace.
    await evaluate(`localStorage.setItem('lost-found-auth', JSON.stringify({ token: 'invalid-token' }))`);
    await navigate('/');
    await waitFor(`location.pathname === '/login' && document.querySelector('#login-button')?.disabled === false`);
    assert.equal(await evaluate(`localStorage.getItem('lost-found-auth')`), null);
    await submitLogin();
    await waitFor(`location.pathname === '/' && document.querySelector('#app-shell')?.hidden === false`);
    await evaluate(`localStorage.removeItem('lost-found-auth'); window.dispatchEvent(new StorageEvent('storage', { key: 'lost-found-auth' }))`);
    await waitFor(`location.pathname === '/login' && document.querySelector('#login-button')?.disabled === false`);
    assert.deepEqual(errors, [], 'Pages must have no uncaught JavaScript errors');
    console.log('PASS: NDJSON streaming with split UTF-8 and coalesced events, malformed/truncated streams, photo validation, per-session drafts, lost/found automatic authenticated multipart upload, duplicate prevention, upload retry without chat or registration replay, mismatched/update/invalid registration events, desktop/mobile layouts; existing authentication, history and claim workflows.');
})().catch(error => {
    console.error(error);
    process.exitCode = 1;
}).finally(() => {
    if (previewMode && !process.exitCode) return;
    clearTimeout(deadline);
    socket?.close();
    browser?.kill();
    server.closeAllConnections();
    server.close();
});
