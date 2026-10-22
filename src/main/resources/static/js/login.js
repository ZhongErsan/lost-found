(() => {
    'use strict';

    const auth = window.LostFoundAuth;
    const form = document.querySelector('#login-form');
    const username = document.querySelector('#username');
    const password = document.querySelector('#password');
    const button = document.querySelector('#login-button');
    const feedback = document.querySelector('#login-feedback');
    const toggle = document.querySelector('#toggle-password');
    const registrationPanel = document.querySelector('#registration-panel');
    const registrationForm = document.querySelector('#registration-form');
    const registrationFeedback = document.querySelector('#register-feedback');
    const registerButton = document.querySelector('#register-button');
    const loginContent = [...document.querySelector('.login-card').children]
        .filter(element => element !== registrationPanel);
    let busy = false;
    let registering = false;

    function switchPanel(register) {
        if (busy || registering) return;
        loginContent.forEach(element => { element.hidden = register; });
        registrationPanel.hidden = !register;
        document.querySelector('.login-panel').setAttribute('aria-labelledby', register ? 'registration-title' : 'login-title');
        if (!register) feedback.hidden = !feedback.textContent;
        password.value = '';
        registrationForm.elements.password.value = '';
        registrationForm.elements.confirmPassword.value = '';
        document.title = `${register ? '注册' : '登录'} · 寻物`;
        (register ? registrationForm.elements.username : username).focus();
    }

    document.querySelector('#show-register').addEventListener('click', () => switchPanel(true));
    document.querySelector('#show-login').addEventListener('click', () => switchPanel(false));

    registrationForm.addEventListener('submit', async event => {
        event.preventDefault();
        if (registering || busy) return;
        const data = Object.fromEntries(new FormData(registrationForm));
        let errorMessage = '';
        const normalizedPhone = data.phone.trim().replace(/[ ()-]/g, '');
        if (!/^[A-Za-z0-9._-]{3,32}$/.test(data.username.trim())) errorMessage = '用户名需为3–32位字母、数字、点、下划线或连字符。';
        else if (!data.realName.trim()) errorMessage = '请填写真实姓名。';
        else if (!/^\+?[0-9 ()-]{7,20}$/.test(data.phone.trim()) || !/^\+?\d{7,15}$/.test(normalizedPhone)) errorMessage = '请填写有效联系电话，包含7–15位数字。';
        else if (data.password.length < 8 || data.password.length > 72 || new TextEncoder().encode(data.password).length > 72) errorMessage = '密码需为8–72个字符，UTF-8编码不超过72字节。';
        else if (data.password !== data.confirmPassword) errorMessage = '两次输入的密码不一致。';
        registrationFeedback.textContent = errorMessage;
        registrationFeedback.hidden = !errorMessage;
        if (errorMessage) return;
        registering = true;
        registerButton.disabled = true;
        registerButton.textContent = '正在注册…';
        registrationForm.setAttribute('aria-busy', 'true');
        [...registrationForm.querySelectorAll('input')].forEach(element => { element.readOnly = true; });
        document.querySelector('#show-login').disabled = true;
        let registered = false;
        try {
            await auth.register(data);
            registered = true;
        } catch (error) {
            registrationFeedback.textContent = (error.name === 'TimeoutError' || error.name === 'AbortError' || error instanceof TypeError)
                ? '暂时无法确认注册结果。请先尝试登录；若账号不存在，再重新注册。'
                : auth.errorMessage(error);
            registrationFeedback.hidden = false;
        } finally {
            registering = false;
            registerButton.disabled = false;
            registerButton.textContent = '注册账号 →';
            registrationForm.setAttribute('aria-busy', 'false');
            [...registrationForm.querySelectorAll('input')].forEach(element => { element.readOnly = false; });
            document.querySelector('#show-login').disabled = false;
        }
        if (registered) {
            username.value = data.username.trim();
            registrationForm.reset();
            switchPanel(false);
            showMessage('注册成功，请输入密码登录。');
            password.focus();
        }
    });

    function showMessage(message) {
        feedback.textContent = message;
        feedback.hidden = !message;
    }

    function setBusy(value) {
        busy = value;
        button.disabled = value;
        document.querySelector('#show-register').disabled = value;
        username.readOnly = value;
        password.readOnly = value;
        form.setAttribute('aria-busy', String(value));
        button.textContent = value ? '正在登录…' : '登录并进入工作台 →';
    }

    toggle.addEventListener('click', () => {
        const visible = password.type === 'password';
        password.type = visible ? 'text' : 'password';
        toggle.textContent = visible ? '隐藏' : '显示';
        toggle.setAttribute('aria-label', visible ? '隐藏密码' : '显示密码');
        toggle.setAttribute('aria-pressed', String(visible));
    });

    form.addEventListener('submit', async (event) => {
        event.preventDefault();
        if (busy) return;
        if (!username.value.trim()) {
            showMessage('请输入用户名。');
            username.focus();
            return;
        }
        showMessage('');
        setBusy(true);
        try {
            await auth.login(username.value, password.value);
            password.value = '';
            location.replace(auth.url('').href);
        } catch (error) {
            showMessage(auth.errorMessage(error));
            setBusy(false);
        }
    });

    async function initialize() {
        if (new URLSearchParams(location.search).get('reason') === 'expired') {
            showMessage('登录状态已失效，请重新登录。');
        }
        if (!auth.hasSession()) {
            setBusy(false);
            return;
        }
        setBusy(true);
        try {
            await auth.currentUser();
            location.replace(auth.url('').href);
        } catch (error) {
            if (error.status !== 401) {
                showMessage(auth.errorMessage(error));
                setBusy(false);
            }
        }
    }

    void initialize();
})();
