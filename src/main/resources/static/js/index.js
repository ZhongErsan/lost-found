(() => {
    'use strict';

    const auth = window.LostFoundAuth;
    const $ = (selector) => document.querySelector(selector);
    const chatBody = $('#chat-body');
    const input = $('#message-input');
    const sendButton = $('#send-button');
    const status = $('#chat-status');
    const MAX_LENGTH = 1500;
    const SEARCH_PREFIX = '本轮只查询拾物线索，不新增或修改登记。用户提供的查询内容：\n';
    let conversationCursor = null;
    let conversationHasMore = true;
    let listing = false;
    const registrationModes = {
        lost: {
            title: '新的寻物对话', hero: '找回物品，也找回', accent: '安心。',
            description: '不必独自着急。告诉我发生了什么，让我们一起，从一条线索开始。',
            placeholder: '说说你丢失的物品，比如：我在图书馆丢了一个黑色背包……',
            welcome: '你好呀，我是寻物小助手。\n丢了东西先别着急，我会帮你一步步整理信息，完成失物登记。\n\n你丢失了什么？也可以选择拾物登记，帮助物品找到主人。',
            guide: '找回的第一步，\n是留下线索。', guideIntro: '不用一次说完，跟着小助手慢慢来。',
            itemTitle: '描述丢失的物品', contactTitle: '核对账号联系方式', contactDescription: '使用账号姓名和手机号，由助手展示后请你确认。',
            prompts: [
                ['手机丢了', '我丢失了一部手机，想登记失物信息。'],
                ['找不到钥匙', '我的钥匙丢了，想登记失物信息。'],
                ['背包遗失', '我丢失了一个背包，想登记失物信息。']
            ]
        },
        found: {
            title: '新的拾物登记', hero: '你的一份善意，让物品', accent: '回家。',
            description: '谢谢你为失主多想一步。说说拾到的物品，让我们一起留下重逢的线索。',
            placeholder: '说说你拾到的物品，比如：我捡到了一个黑色钱包，上面有小熊挂件……',
            welcome: '谢谢你的善意！我是寻物小助手。\n我会帮你整理拾到的物品信息，联系人使用当前账号资料。核对内容后请确认登记。\n\n你捡到了什么？可以先说说物品的颜色、品牌或特殊标记。',
            guide: '归还的第一步，\n是记下这份善意。', guideIntro: '留下物品特征，让认领多一点依据。',
            itemTitle: '描述拾到的物品', contactTitle: '核对拾得人信息', contactDescription: '使用账号姓名和手机号，无需重复填写。',
            prompts: [
                ['捡到钱包', '我捡到了一个钱包，想办理拾物登记。'],
                ['捡到钥匙', '我拾到了一串钥匙，想登记拾到的物品。'],
                ['捡到耳机', '我捡到了耳机，想办理拾物登记。']
            ]
        },
        search: {
            title: '新的失物查询', hero: '从一条线索，寻找', accent: '重逢。',
            description: '描述丢失物品的名称和特征，查询有没有相符的拾物登记。',
            placeholder: '查询有没有黑色钱包，拉链上有小熊挂件……',
            welcome: '我可以根据物品名称和特征查询拾物线索。\n请描述你丢失的物品，我会帮你寻找可能匹配的登记。\n\n这里的匹配结果需要进一步核实；若要补充自己的登记，请使用“补充我的登记”。',
            guide: '找到的可能，\n藏在细节里。', guideIntro: '提供清晰特征，帮助缩小查询范围。',
            itemTitle: '描述要查询的物品', contactTitle: '补充可识别特征', contactDescription: '颜色、品牌、挂件或特殊标记，越具体越好。',
            prompts: [
                ['查询钱包', '请查询是否有人登记拾到黑色钱包，有小熊挂件。'],
                ['查询耳机', '请查询是否有人登记拾到白色无线耳机，充电盒上有贴纸。'],
                ['查询钥匙', '请查询是否有人登记拾到一串钥匙，带蓝色钥匙扣。']
            ]
        }
    };
    const sessions = [];
    let currentSession;
    let toastTimer;
    let composing = false;
    let authenticated = false;
    let checkingSession = false;
    let signingOut = false;

    const timeOf = (value) => new Intl.DateTimeFormat('zh-CN', {
        hour: '2-digit', minute: '2-digit', hour12: false
    }).format(new Date(value));

    function icon(name, className = '') {
        const svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
        svg.setAttribute('class', `icon ${className}`);
        svg.setAttribute('aria-hidden', 'true');
        const use = document.createElementNS('http://www.w3.org/2000/svg', 'use');
        use.setAttribute('href', `#i-${name}`);
        svg.append(use);
        return svg;
    }

    function toast(message) {
        const element = $('#toast');
        // Keep feedback visible above the modal backdrop during form validation.
        (document.querySelector('dialog[open]') || document.body).append(element);
        clearTimeout(toastTimer);
        element.textContent = message;
        element.hidden = false;
        toastTimer = setTimeout(() => { element.hidden = true; }, 3500);
    }

    function showInfo(title, content) {
        $('#info-title').textContent = title;
        $('#info-content').textContent = content;
        if (!$('#info-dialog').open) $('#info-dialog').showModal();
    }

    function newSession(mode = currentSession?.mode || 'lost') {
        if (currentSession?.pending) {
            toast('小助手正在处理这条消息，请稍候再开启新对话。');
            return false;
        }
        if (currentSession) currentSession.draft = input.value;
        if (currentSession && !currentSession.sessionId && currentSession.messages.length === 0 && currentSession.mode === mode) {
            input.focus();
            toast('已经是新对话，直接说说你的物品吧。');
            return true;
        }
        if (currentSession && !currentSession.sessionId && !currentSession.messages.length && !currentSession.draft.trim()) {
            currentSession.mode = mode;
            currentSession.title = registrationModes[mode].title;
            renderSession();
            return true;
        }
        // 空白对话先留在页面内，首条消息发送前由后端创建归属于当前用户的会话。
        currentSession = { sessionId: null, mode, title: registrationModes[mode].title, createdAt: Date.now(), messages: [], draft: '', pending: false };
        sessions.unshift(currentSession);

        renderSession();
        return true;
    }

    function selectRegistrationMode(mode) {
        if (currentSession.pending) { toast('请等待当前登记回复完成后再切换。'); return false; }
        if (!currentSession.mode) { toast('请先为当前旧会话补录模式，或开启新对话。'); return false; }
        if (currentSession.mode === mode) return true;
        const hasConversation = currentSession.messages.length > 0 || input.value.trim();
        newSession(mode);
        if (hasConversation) toast(`已开启独立的${mode === 'search' ? '失物查询' : mode === 'found' ? '拾物登记' : '丢失登记'}对话，原对话已保留。`);
        return true;
    }

    function renderRegistrationMode() {
        const mode = currentSession.mode;
        const config = registrationModes[mode] || registrationModes.lost;
        $('#hero-title-text').textContent = config.hero;
        $('#hero-title-accent').textContent = config.accent;
        $('#hero-description').textContent = config.description;
        $('#active-service-title').textContent = mode === 'found' ? '拾到物品登记' : mode === 'search' ? '失物查询' : '丢失物品登记';
        input.placeholder = mode ? config.placeholder : '请先阅读历史记录并保存会话模式';
        input.maxLength = mode === 'search' ? MAX_LENGTH - SEARCH_PREFIX.length : MAX_LENGTH;
        $('#guide-title').textContent = config.guide;
        $('#guide-intro').textContent = config.guideIntro;
        $('#guide-item-title').textContent = config.itemTitle;
        $('#guide-contact-title').textContent = config.contactTitle;
        $('#guide-contact-description').textContent = config.contactDescription;
        const action = !mode ? null : mode === 'search' ? 'search' : mode === 'found' ? 'found' : 'register';
        $('#guide-register-label').textContent = mode === 'search' ? '填写查询条件' : '快速填写登记信息';
        $('#quick-register').firstChild.textContent = mode === 'search' ? '填写查询条件' : '填写登记表';
        $('#guide-final-title').textContent = mode === 'search' ? '核实匹配线索' : '核对并完成登记';
        $('#guide-final-description').textContent = mode === 'search' ? '匹配结果仅供参考，请进一步核实物品归属。' : '补全信息，确认本次登记内容。';
        for (const selector of ['#quick-register', '#guide-register']) $(selector).dataset.action = action;
        document.querySelectorAll('[data-mode]').forEach((button) => {
            const selected = button.dataset.mode === mode;
            button.classList.toggle('selected', selected);
            button.setAttribute('aria-pressed', String(selected));
        });
        for (const navAction of ['register', 'found', 'search']) {
            $(`.main-nav [data-action="${navAction}"]`).setAttribute('aria-pressed', String(action === navAction));
        }
        const chips = $('.prompt-chips');
        chips.replaceChildren();
        for (const [label, prompt] of config.prompts) {
            const button = document.createElement('button');
            button.type = 'button';
            button.textContent = label;
            button.dataset.prompt = prompt;
            chips.append(button);
        }
    }

    function renderSessionList() {
        for (const list of [$('#session-list'), $('#history-session-list')]) {
        list.replaceChildren();
        for (const session of sessions) {
            const button = document.createElement('button');
            button.type = 'button';
            button.className = `session-item${session === currentSession ? ' selected' : ''}`;
            button.setAttribute('aria-pressed', String(session === currentSession));
            button.title = session.title;
            const title = document.createElement('span');
            title.className = 'session-title';
            title.textContent = session.title;
            const time = document.createElement('time');
            time.dateTime = new Date(session.createdAt).toISOString();
            time.textContent = timeOf(session.createdAt);
            button.append(icon(session.mode === 'found' ? 'box' : 'chat'), title, time);
            button.addEventListener('click', () => {
                if (currentSession.pending) {
                    toast('请等待当前回复完成后再切换对话。');
                    return;
                }
                currentSession.draft = input.value;
                currentSession = session;
                renderSession();
                $('#history-dialog').close();
                if (session.sessionId && !session.loaded) void loadHistory(session);
            });
            list.append(button);
        }
        }
    }

    function welcome() {
        const message = createMessage({ role: 'assistant', text: (registrationModes[currentSession.mode] || registrationModes.lost).welcome, at: currentSession.createdAt });
        const content = message.querySelector('.message-content');
        const shortcuts = document.createElement('div');
        shortcuts.className = 'welcome-shortcuts';
        for (const [label, name, action] of [['我丢了东西', 'bag', 'register'], ['我捡到了物品', 'box', 'found'], ['查询失物线索', 'search', 'search']]) {
            const button = document.createElement('button');
            button.type = 'button';
            button.className = 'welcome-shortcut';
            button.dataset.action = action;
            button.append(icon(name), document.createTextNode(label), icon('arrow', 'shortcut-arrow'));
            shortcuts.append(button);
        }
        const hint = document.createElement('p');
        hint.className = 'welcome-hint';
        hint.textContent = '支持丢失登记、拾物登记与失物查询 · 切换类型会单独开启对话';
        content.append(shortcuts, hint);
        chatBody.append(message);
    }

    function createMessage(message) {
        const article = document.createElement('article');
        article.className = `message ${message.role}${message.error ? ' failed' : ''}`;
        const avatar = document.createElement('div');
        avatar.className = 'message-avatar';
        avatar.setAttribute('aria-hidden', 'true');
        avatar.append(message.role === 'user' ? document.createTextNode('我') : icon('spark'));
        const content = document.createElement('div');
        content.className = 'message-content';
        const meta = document.createElement('div');
        meta.className = 'message-meta';
        const time = document.createElement('time');
        time.dateTime = new Date(message.at).toISOString();
        time.textContent = timeOf(message.at);
        meta.append(document.createTextNode(message.role === 'user' ? '我' : '寻物小助手'), time);
        const text = document.createElement('div');
        text.className = 'message-text';
        // User input and model replies are always plain text; no HTML is injected.
        text.textContent = message.text;
        content.append(meta, text);
        if (message.error) {
            const retry = document.createElement('button');
            retry.type = 'button';
            retry.className = 'retry-button';
            retry.textContent = '将原消息放回输入框';
            retry.addEventListener('click', () => {
                if (currentSession.pending) return;
                if (input.value.trim()) {
                    toast('输入框里还有草稿，请先发送或清空。');
                    return;
                }
                input.value = message.original;
                updateComposer();
                input.focus();
            });
            content.append(retry);
        }
        article.append(avatar, content);
        return article;
    }

    function renderSession() {
        renderRegistrationMode();
        chatBody.replaceChildren();
        const date = document.createElement('div');
        date.className = 'date-divider';
        date.textContent = `${new Intl.DateTimeFormat('zh-CN', { month: 'long', day: 'numeric' }).format(currentSession.createdAt)} · 每一条线索都值得被记住`;
        chatBody.append(date);
        if (!currentSession.sessionId) welcome();
        currentSession.messages.forEach((message) => chatBody.append(createMessage(message)));
        input.value = currentSession.draft;
        renderSessionList();
        updateComposer();
        renderHistoryControls();
        renderPhotoState();
        scrollToEnd();
    }

    function scrollToEnd() {
        chatBody.scrollTop = chatBody.scrollHeight;
    }

    function updateComposer() {
        const busy = Boolean(currentSession.pending || currentSession.loading || currentSession.savingMode);
        const blocked = busy || !currentSession.mode || Boolean(currentSession.sessionId && !currentSession.loaded);
        $('#logout-button').disabled = busy || signingOut;
        sendButton.disabled = blocked || !input.value.trim();
        input.readOnly = blocked;
        $('#char-count').textContent = `${input.value.length} / ${input.maxLength}`;
        status.textContent = busy ? '正在处理' : blocked ? '等待恢复会话' : '准备就绪';
        status.classList.toggle('busy', busy);
        $('#chat-form').setAttribute('aria-busy', String(busy));
        document.querySelectorAll('[data-prompt], [data-mode], [data-action="register"], [data-action="found"], [data-action="search"], [data-action="supplement"], #new-chat').forEach((button) => { button.disabled = busy; });
        $('#confirm-details').hidden = !currentSession.mode || currentSession.mode === 'search';
        $('#confirm-details').disabled = blocked || currentSession.messages.at(-1)?.role !== 'assistant' || Boolean(currentSession.messages.at(-1)?.error);
        input.style.height = 'auto';
        input.style.height = `${Math.min(input.scrollHeight, 115)}px`;
        currentSession.draft = input.value;
        renderPhotoState();
    }

    function discardPhoto(session) {
        if (session.photo?.objectUrl) URL.revokeObjectURL(session.photo.objectUrl);
        session.photo = null;
        if (session === currentSession) renderPhotoState();
    }

    function renderPhotoState() {
        const panel = $('#registration-photo-state');
        const photo = currentSession?.photo;
        panel.hidden = !photo;
        const image = $('#registration-photo-thumbnail');
        if (!photo) { image.removeAttribute('src'); return; }
        const source = photo.imageUrl || photo.objectUrl;
        if (image.getAttribute('src') !== source) image.src = source;
        $('#registration-photo-name').textContent = photo.name;
        $('#registration-photo-status').textContent = photo.status === 'uploaded' ? '照片已保存'
            : photo.status === 'uploading' ? '登记已保存，正在上传照片…'
            : photo.error || (photo.target ? '登记已保存，照片待上传' : '照片已保留，确认登记后自动上传');
        panel.dataset.state = photo.error ? 'error' : photo.status;
        $('#registration-photo-retry').hidden = !photo.file || !photo.target || photo.status === 'uploading';
        $('#registration-photo-retry').disabled = Boolean(currentSession.pending);
        $('#registration-photo-remove').hidden = photo.status === 'uploaded';
        $('#registration-photo-remove').disabled = Boolean(currentSession.pending || photo.status === 'uploading');
    }

    async function uploadPhoto(session, photo) {
        if (!photo?.file || !photo.target || photo.status === 'uploading' || photo.status === 'uploaded') return;
        photo.status = 'uploading';
        photo.error = '';
        if (session === currentSession) renderPhotoState();
        try {
            const data = new FormData();
            data.append('file', photo.file);
            const response = await auth.fetch(auth.url(`api/items/${photo.target.type}/${photo.target.id}/image`), {
                method: 'POST', headers: { Accept: 'application/json' }, body: data,
                signal: AbortSignal.timeout(30000), cache: 'no-store'
            });
            const result = await response.json();
            const imageUrl = window.LostFoundImages.imageUrl(result.imageUrl);
            if (!imageUrl) throw new Error('图片响应格式不正确，请重试上传。');
            photo.imageUrl = imageUrl;
            photo.status = 'uploaded';
            photo.file = null;
            URL.revokeObjectURL(photo.objectUrl);
            photo.objectUrl = null;
        } catch (error) {
            photo.status = 'failed';
            photo.error = '登记已保存，照片上传失败：' + auth.errorMessage(error);
        } finally {
            if (session === currentSession) renderPhotoState();
        }
    }

    function associatePhoto(session, registration) {
        const photo = session.photo;
        if (!photo?.file || photo.target) return;
        if (registration.action !== 'CREATE' || registration.type !== photo.type) {
            photo.error = registration.action !== 'CREATE'
                ? '本次保存的是已有登记，照片未自动关联，请核实本次登记。'
                : '照片尚未关联：本次保存的登记类型与照片不匹配，请核实登记。';
            return;
        }
        photo.target = registration;
        photo.error = '';
    }

    $('#registration-photo-retry').addEventListener('click', async () => {
        const session = currentSession;
        if (session.pending || !session.photo?.target) return;
        session.pending = true;
        updateComposer();
        try { await uploadPhoto(session, session.photo); }
        finally { session.pending = false; updateComposer(); }
    });
    $('#registration-photo-remove').addEventListener('click', () => {
        if (!currentSession.pending && currentSession.photo?.status !== 'uploading') discardPhoto(currentSession);
    });
    window.addEventListener('beforeunload', event => {
        if (auth.hasSession() && sessions.some(session => session.photo?.file)) {
            event.preventDefault();
            event.returnValue = '';
        }
    });

    async function sendMessage(value) {
        if (!authenticated || signingOut) return;
        const text = value.trim();
        if (!text || currentSession.pending || currentSession.loading || currentSession.savingMode || !currentSession.mode || (currentSession.sessionId && !currentSession.loaded)) return;
        // 后端通过消息识别业务，查询模式的自由输入和追问也需携带查询意图。
        const requestMessage = currentSession.mode === 'search' ? SEARCH_PREFIX + text : text;
        if (requestMessage.length > MAX_LENGTH) { toast(`请将消息控制在 ${input.maxLength} 字以内。`); return; }
        if (location.protocol === 'file:') {
            showInfo('请通过应用访问', '请启动 Spring Boot 工程，然后打开 http://localhost:8084/ 使用对话登记功能。');
            return;
        }
        const session = currentSession;
        // 创建会话期间同样禁止重复发送或切换，失败时保留输入内容。
        session.pending = true;
        updateComposer();
        try {
            if (!session.sessionId) {
                const response = await auth.fetch(auth.url('api/conversations'), {
                    method: 'POST', headers: { Accept: 'application/json', 'Content-Type': 'application/json' },
                    body: JSON.stringify({ mode: session.mode }),
                    signal: AbortSignal.timeout(15000)
                });
                const conversation = await response.json();
                if (typeof conversation?.sessionId !== 'string'
                        || !conversation.sessionId.trim() || conversation.sessionId.length > 64) {
                    throw new Error('创建会话的响应缺少有效标识，请稍后重试');
                }
                if (conversation.mode !== session.mode) throw new Error('会话模式与请求不一致，请刷新后重试');
                session.sessionId = conversation.sessionId;
                session.loaded = true;
            }
        } catch (error) {
            session.pending = false;
            updateComposer();
            if (error.status !== 401) toast(`创建对话失败：${auth.errorMessage(error)}`);
            return;
        }
        if (session.messages.length === 0) session.title = text.slice(0, 18);
        const userMessage = { role: 'user', text, at: Date.now() };
        session.messages.push(userMessage);
        chatBody.append(createMessage(userMessage));
        input.value = '';
        updateComposer();
        renderSessionList();
        const assistantMessage = { role: 'assistant', text: '', at: Date.now() };
        const pending = createMessage(assistantMessage);
        pending.classList.add('pending');
        const responseText = pending.querySelector('.message-text');
        for (let i = 0; i < 3; i++) {
            const dot = document.createElement('span');
            dot.className = 'loading-dot';
            dot.setAttribute('aria-hidden', 'true');
            responseText.append(dot);
        }
        const waiting = document.createElement('span');
        waiting.className = 'waiting-label';
        waiting.textContent = '正在认真整理你的线索…';
        responseText.append(waiting);
        chatBody.append(pending);
        scrollToEnd();
        const controller = new AbortController();
        const timeout = setTimeout(() => controller.abort(), 120000);
        let savedRegistration = null;
        try {
            // 同一对话持续复用后端返回的会话标识，与登录用户 ID 分开。
            const endpoint = auth.url('ai/chat-stream');

            const response = await auth.fetch(endpoint, {
                method: 'POST',
                headers: { Accept: 'application/x-ndjson', 'Content-Type': 'application/json' },
                body: JSON.stringify({ sessionId: session.sessionId, message: requestMessage }),
                signal: controller.signal,
                cache: 'no-store'
            });
            if (!response.ok) throw new Error(`http:${response.status}`);
            await window.LostFoundChatEvents.read(response, event => {
                if (!event || typeof event !== 'object') throw new Error('invalid-event');
                if (event.type === 'text') {
                    if (typeof event.content !== 'string') throw new Error('invalid-event');
                    const followEnd = chatBody.scrollHeight - chatBody.scrollTop - chatBody.clientHeight < 90;
                    assistantMessage.text += event.content;
                    pending.classList.remove('pending');
                    responseText.textContent = assistantMessage.text;
                    if (followEnd) scrollToEnd();
                } else if (event.type === 'registration_saved') {
                    const registration = window.LostFoundChatEvents.registration(event);
                    if (savedRegistration && (savedRegistration.id !== registration.id
                            || savedRegistration.type !== registration.type || savedRegistration.action !== registration.action)) {
                        throw new Error('invalid-event');
                    }
                    savedRegistration = registration;
                } else {
                    throw new Error('invalid-event');
                }
            });
            if (!assistantMessage.text.trim()) throw new Error('empty-response');
            responseText.textContent = assistantMessage.text;
            pending.classList.remove('pending');
            session.messages.push(assistantMessage);
            if (savedRegistration) {
                associatePhoto(session, savedRegistration);
                clearTimeout(timeout);
                if (session.photo?.target && session.photo.status === 'waiting') await uploadPhoto(session, session.photo);
            }
        } catch (error) {
            if (error.status === 401) return;
            if (savedRegistration) associatePhoto(session, savedRegistration);
            const partial = assistantMessage.text;
            assistantMessage.error = true;
            assistantMessage.original = text;
            assistantMessage.text = partial
                ? `${partial}\n\n连接中断，以上回复可能不完整。若刚才已提交登记，请先核实结果，避免重复登记。`
                : Number.isInteger(error.status)
                    ? `无法执行此操作：${error.message}`
                : error.name === 'AbortError'
                    ? '等待回复超时了。请求可能已被处理，暂时无法确认登记结果。请稍后核实，避免重复提交。'
                    : error.message.startsWith('http:')
                        ? `服务暂时未能完成回复（HTTP ${error.message.slice(5)}）。请稍后再试；若刚才已提交完整信息，请先核实是否已登记。`
                        : '暂时无法获取助手回复，请检查网络或服务是否正常。若刚才已提交完整信息，请先核实登记结果，再决定是否重发。';
            session.messages.push(assistantMessage);
            pending.replaceWith(createMessage(assistantMessage));
        } finally {
            clearTimeout(timeout);
            controller.abort();
            session.pending = false;
            updateComposer();
            scrollToEnd();
            if (matchMedia('(min-width: 741px)').matches) input.focus();
        }
    }

    $('#chat-form').addEventListener('submit', (event) => { event.preventDefault(); void sendMessage(input.value); });
    input.addEventListener('input', updateComposer);
    input.addEventListener('compositionstart', () => { composing = true; });
    input.addEventListener('compositionend', () => { composing = false; });
    input.addEventListener('keydown', (event) => {
        if (event.key === 'Enter' && !event.shiftKey && !event.isComposing && !composing && event.keyCode !== 229) {
            event.preventDefault();
            if (!sendButton.disabled) $('#chat-form').requestSubmit();
        }
    });
    $('#new-chat').addEventListener('click', () => newSession());

    document.addEventListener('click', (event) => {
        const close = event.target.closest('[data-close]');
        if (close) document.getElementById(close.dataset.close).close();
        const mode = event.target.closest('[data-mode]')?.dataset.mode;
        if (mode) selectRegistrationMode(mode);
        const prompt = event.target.closest('[data-prompt]');
        if (prompt && !currentSession.pending) {
            if (input.value.trim()) { toast('输入框里还有草稿，请先发送或清空。'); return; }
            input.value = prompt.dataset.prompt;
            updateComposer();
            input.focus();
        }
        const action = event.target.closest('[data-action]')?.dataset.action;
        if (!action) return;
        if (action === 'register' || action === 'found') {
            if (!selectRegistrationMode(action === 'found' ? 'found' : 'lost')) return;
            const dialog = $(action === 'found' ? '#found-dialog' : '#register-dialog');
            if (!dialog.open) dialog.showModal();
        } else if (action === 'search') {
            if (!selectRegistrationMode('search')) return;
            $('#search-dialog').showModal();
        } else if (action === 'supplement') {
            if (currentSession.pending) return;
            $('#supplement-form').elements.kind.value = currentSession.mode === 'found' ? 'found' : 'lost';
            $('#supplement-dialog').showModal();
        } else if (action === 'help') {
            showInfo('和小助手一起寻找物品', '1. 新增登记：选择丢失或拾物登记，描述物品；联系人由后端读取账号资料，无需重复填写。\n2. 核对：查看助手展示的信息，确认后再提交。\n3. 补充：使用“补充我的登记”，说明原物品与修改内容，保留原联系人。\n4. 查询：选择“失物查询”，按名称和特征查找拾物线索；结果需要进一步核实。\n\n账号姓名或手机号缺失，请联系管理员补全资料。\n切换业务会开启独立对话。历史会话可从“历史对话”恢复，旧会话需要先补录业务模式。认领请打开“认领与归还”。恢复聊天记录不代表模型在重启后恢复了记忆，继续操作前请说明目标物品和登记编号。');
        }
    });

    function submitRegistration(event) {
        event.preventDefault();
        if (currentSession.pending) return;
        const form = event.currentTarget;
        const found = form.id === 'found-form';
        const data = new FormData(form);
        const item = data.get('item').trim();
        if (!item) { toast('请填写物品名称。'); return; }
        const photoType = found ? 'found' : 'lost';
        const photo = window.LostFoundImages.selectedPhoto(photoType);
        if (form.querySelector('.photo-input').files.length && !photo) {
            toast('照片正在校验，请稍候再提交。'); return;
        }
        if (currentSession.photo?.file) {
            toast('当前对话还有未保存的照片，请先完成登记或移除照片。'); return;
        }
        const features = data.get('features').trim();
        const message = found
            ? `我捡到了物品，想办理拾物登记。\n拾到的物品名称：${item}\n物品特征：${features || '暂时没有更多特征'}\n联系人使用当前账号资料，请先展示完整登记内容供我核对，等我确认后再保存。`
            : `我想登记丢失物品。\n失物名称：${item}\n物品特征：${features || '暂时没有更多特征'}\n联系人使用当前账号资料，请先展示完整登记内容供我核对，等我确认后再保存。`;
        if (input.value.trim()) {
            toast('输入框里还有草稿，请先发送或清空后再提交表单。');
            return;
        }
        if (message.length > MAX_LENGTH) { toast(`请将登记信息控制在 ${MAX_LENGTH} 字以内。`); return; }
        if (location.protocol === 'file:') {
            toast('请启动应用并通过 http://localhost:8084/ 提交登记。');
            return;
        }
        discardPhoto(currentSession);
        if (photo) {
            currentSession.photo = { file: photo, name: photo.name, type: photoType, status: 'waiting',
                target: null, objectUrl: URL.createObjectURL(photo), imageUrl: null, error: '' };
        }
        $(found ? '#found-dialog' : '#register-dialog').close();
        // 会话创建失败时，让表单生成的消息也能作为草稿保留。
        input.value = message;
        void sendMessage(message);
        form.reset();
    }

    $('#register-form').addEventListener('submit', submitRegistration);
    $('#found-form').addEventListener('submit', submitRegistration);

    for (const formId of ['search-form', 'supplement-form']) {
        $(`#${formId}`).addEventListener('submit', event => {
            event.preventDefault();
            if (currentSession.pending) return;
            if (input.value.trim()) { toast('输入框里还有草稿，请先发送或清空后再提交表单。'); return; }
            const form = event.currentTarget;
            const data = new FormData(form);
            const item = data.get('item').trim();
            const features = data.get('features').trim();
            if (!item || !features) { toast('请填写物品名称和具体特征。'); return; }
            const search = formId === 'search-form';
            const mode = search ? 'search' : data.get('kind');
            const message = search
                ? `请查询是否有人登记拾到与以下描述相符的物品。\n物品名称：${item}\n物品特征：${features}\n本次只查询拾物线索，不新增或修改登记。`
                : `我要补充或修改我已有的${mode === 'found' ? '拾物' : '丢失'}登记。\n原登记物品：${item}\n补充或修改内容：${features}\n请先查询我本人的已有登记，确定目标记录，再展示合并后的内容等我确认；查不到时不要新增登记，保留原联系人。`;
            if (message.length > MAX_LENGTH) { toast(`请将信息控制在 ${MAX_LENGTH} 字以内。`); return; }
            if (!selectRegistrationMode(mode)) return;
            form.closest('dialog').close();
            input.value = message;
            void sendMessage(message);
            form.reset();
        });
    }

    $('#confirm-details').addEventListener('click', () => {
        if (currentSession.pending || $('#confirm-details').disabled) return;
        if (input.value.trim()) { toast('输入框里还有草稿，请先发送或清空。'); return; }
        input.value = '我确认以上展示的本次登记或修改内容，请按已确认的操作保存。';
        updateComposer();
        input.focus();
        toast('请先核对助手上一条回复，再发送确认消息。');
    });

    $('#export-chat').addEventListener('click', () => {
        if (!currentSession.messages.length) { toast('还没有对话记录，先说说你要登记的物品吧。'); return; }
        if (currentSession.pending) { toast('请等待当前回复完成后下载完整对话。'); return; }
        const transcript = ['寻物 · 对话记录', new Date(currentSession.createdAt).toLocaleString('zh-CN'), '',
            ...currentSession.messages.map((message) => `[${timeOf(message.at)}] ${message.role === 'user' ? '我' : '寻物小助手'}\n${message.text}\n`)
        ].join('\n');
        const url = URL.createObjectURL(new Blob(['\uFEFF', transcript], { type: 'text/plain;charset=utf-8' }));
        const link = document.createElement('a');
        link.href = url;
        link.download = `寻物对话-${new Date(currentSession.createdAt).toISOString().slice(0, 10)}.txt`;
        document.body.append(link);
        link.click();
        link.remove();
        setTimeout(() => URL.revokeObjectURL(url), 1000);
        toast('对话记录已下载，请妥善保管其中的联系信息。');
    });

    for (const dialog of document.querySelectorAll('dialog')) {
        dialog.addEventListener('click', (event) => {
            const box = dialog.getBoundingClientRect();
            if (event.target === dialog && (event.clientX < box.left || event.clientX > box.right || event.clientY < box.top || event.clientY > box.bottom)) dialog.close();
        });
    }

    $('#logout-button').addEventListener('click', async () => {
        if (signingOut || currentSession?.pending) return;
        signingOut = true;
        $('#logout-button').disabled = true;
        $('#logout-button').textContent = '正在退出…';
        try {
            await auth.logout();
        } catch (error) {
            if (error.status !== 401) toast(`退出失败：${auth.errorMessage(error)}`);
        } finally {
            signingOut = false;
            $('#logout-button').disabled = Boolean(currentSession?.pending);
            $('#logout-button').textContent = '退出登录';
        }
    });

    async function initializeAccount() {
        if (checkingSession) return;
        if (!auth.hasSession()) {
            auth.redirectToLogin();
            return;
        }
        checkingSession = true;
        $('#auth-retry').hidden = true;
        $('#auth-login-link').hidden = true;
        $('#auth-gate-title').textContent = '正在确认登录状态';
        $('#auth-gate-message').textContent = '稍等片刻，即可进入寻物工作台。';
        try {
            const user = await auth.currentUser();
            const name = user.realName?.trim() || user.username;
            $('#current-user-name').textContent = name;
            $('#current-user-name').title = `${name}（${user.username}）`;
            $('#user-avatar').textContent = Array.from(name)[0];
            $('#current-user-role').textContent = user.role === 'ADMIN' ? '管理员' : '用户';
            authenticated = true;
            $('#app-shell').hidden = false;
            $('#auth-gate').hidden = true;
            newSession();
            void loadConversations();
        } catch (error) {
            if (error.status !== 401) {
                $('#auth-gate-title').textContent = '暂时无法进入工作台';
                $('#auth-gate-message').textContent = auth.errorMessage(error);
                $('#auth-retry').hidden = false;
                $('#auth-login-link').hidden = false;
            }
        } finally {
            checkingSession = false;
        }
    }


    function renderHistoryControls() {
        const session = currentSession;
        $('#mode-backfill').hidden = Boolean(session.mode);
        $('#history-message-status').textContent = session.loading ? '正在读取历史记录…'
            : session.historyError || (session.sessionId && session.loaded && !session.messages.length ? '此会话暂无消息。' : '');
        $('#history-retry').hidden = !session.historyError;
        $('#history-older').hidden = !session.loaded || !session.hasOlder;
        $('#history-older').disabled = Boolean(session.loading || session.pending);
        $('#mode-save').disabled = Boolean(session.savingMode || session.loading);
    }

    async function loadConversations(reset = false) {
        if (listing) return;
        listing = true;
        $('#history-list-more').disabled = true;
        $('#history-list-status').textContent = '正在读取会话…';
        try {
            const cursor = reset ? null : conversationCursor;
            const query = new URLSearchParams({ size: '20' });
            if (cursor) query.set('beforeId', cursor);
            const response = await auth.fetch(auth.url('api/conversations') + '?' + query, { cache: 'no-store', signal: AbortSignal.timeout(15000) });
            const page = await response.json();
            if (!Array.isArray(page.content)) throw new Error('会话列表格式不正确');
            for (const row of page.content) {
                let session = sessions.find(item => item.sessionId === row.sessionId);
                const mode = Object.hasOwn(registrationModes, row.mode) ? row.mode : null;
                if (session) {
                    session.mode = mode;
                    session.title = row.title || session.title;
                } else {
                    session = { sessionId: row.sessionId, mode, title: row.title || '未命名历史对话',
                        createdAt: Date.parse(row.createdDate) || Date.now(), messages: [], draft: '', pending: false, loaded: false };
                    sessions.push(session);
                }
            }
            conversationCursor = page.nextBeforeId;
            conversationHasMore = Boolean(page.hasMore && conversationCursor);
            $('#history-list-status').textContent = page.content.length ? '会话按创建时间排列' : '暂无更多历史会话';
            $('#history-list-more').hidden = !conversationHasMore;
            renderSessionList();
            renderHistoryControls();
            updateComposer();
        } catch (error) {
            $('#history-list-status').textContent = '读取失败：' + auth.errorMessage(error);
            $('#history-list-more').hidden = false;
        } finally {
            listing = false;
            $('#history-list-more').disabled = false;
        }
    }

    async function loadHistory(session, older = false) {
        if (session.loading || session.pending) return;
        session.loading = true;
        session.historyError = '';
        if (session === currentSession) { renderHistoryControls(); updateComposer(); }
        const previousHeight = chatBody.scrollHeight;
        const previousTop = chatBody.scrollTop;
        try {
            const pageNumber = older ? session.nextPage : 0;
            const query = new URLSearchParams({ sessionId: session.sessionId, page: String(pageNumber), size: '20', sort: 'id,desc' });
            const response = await auth.fetch(auth.url('ai/chat-history') + '?' + query, { cache: 'no-store', signal: AbortSignal.timeout(15000) });
            const page = await response.json();
            if (!Array.isArray(page.content) || !page.page) throw new Error('聊天记录格式不正确');
            const messages = page.content.slice().reverse().map(row => ({ id: row.id,
                role: String(row.role) === '0' ? 'user' : 'assistant', text: row.content || '',
                at: Date.parse(row.createdDate) || session.createdAt }));
            const known = new Set(session.messages.map(message => message.id).filter(id => id != null));
            session.messages = older ? [...messages.filter(message => !known.has(message.id)), ...session.messages] : messages;
            session.loaded = true;
            session.nextPage = pageNumber + 1;
            session.hasOlder = session.nextPage < page.page.totalPages;
            if (session === currentSession) {
                renderSession();
                if (older) chatBody.scrollTop = previousTop + chatBody.scrollHeight - previousHeight;
            }
        } catch (error) {
            session.historyError = '历史记录读取失败，请重试：' + auth.errorMessage(error);
            session.retryOlder = older;
        } finally {
            session.loading = false;
            if (session === currentSession) { renderHistoryControls(); updateComposer(); }
        }
    }

    $('#history-open').addEventListener('click', () => {
        renderSessionList();
        $('#history-dialog').showModal();
    });
    $('#history-list-more').addEventListener('click', () => void loadConversations());
    $('#history-refresh').addEventListener('click', () => void loadConversations(true));
    $('#history-retry').addEventListener('click', () => void loadHistory(currentSession, currentSession.retryOlder));
    $('#history-older').addEventListener('click', () => void loadHistory(currentSession, true));
    $('#mode-save').addEventListener('click', async () => {
        const session = currentSession;
        if (session.mode || session.savingMode || session.loading) return;
        session.savingMode = true;
        updateComposer(); renderHistoryControls();
        try {
            const response = await auth.fetch(auth.url(`api/conversations/${encodeURIComponent(session.sessionId)}/mode`), {
                method: 'PUT', headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ mode: $('#legacy-mode').value }), signal: AbortSignal.timeout(15000)
            });
            const result = await response.json();
            if (result.sessionId !== session.sessionId || !Object.hasOwn(registrationModes, result.mode)) throw new Error('模式保存响应不正确');
            session.mode = result.mode;
            toast('旧会话模式已保存，可以继续对话。');
        } catch (error) {
            toast('模式保存失败：' + auth.errorMessage(error));
            if (error.status === 409) await loadConversations(true);
        } finally {
            session.savingMode = false;
            if (session === currentSession) renderSession();
        }
    });

    $('#auth-retry').addEventListener('click', () => void initializeAccount());
    void initializeAccount();
})();
