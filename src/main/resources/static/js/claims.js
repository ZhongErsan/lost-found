(() => {
    'use strict';
    const auth = window.LostFoundAuth;
    const $ = selector => document.querySelector(selector);
    const statuses = { PENDING: '待审核', APPROVED: '已同意，待交接', HANDOVER_PENDING: '已交接，待确认收到', COMPLETED: '已归还', REJECTED: '已拒绝', CANCELLED: '已取消', CLOSED: '已关闭' };
    let tab = 'mine', cursor = null, busy = false;
    const api = async (path, options = {}) => {
        const response = await auth.fetch(auth.url('api/claims' + path), { cache: 'no-store', signal: AbortSignal.timeout(15000), ...options });
        return response.json();
    };
    function feedback(text) { $('#claim-feedback').textContent = text; }
    function setBusy(value) {
        busy = value;
        $('#claims-dialog').setAttribute('aria-busy', String(value));
        $('#claims-dialog').querySelectorAll('button:not([data-close]), input, textarea').forEach(node => { node.disabled = value; });
    }
    function paragraph(parent, text) {
        const node = document.createElement('p'); node.textContent = text; parent.append(node); return node;
    }
    function button(parent, text, handler) {
        const node = document.createElement('button'); node.type = 'button'; node.textContent = text;
        node.addEventListener('click', handler); parent.append(node); return node;
    }
    async function list(more = false) {
        if (busy) return;
        setBusy(true);
        if (!more) {
            cursor = null;
            $('#claim-list').replaceChildren(); $('#claim-detail').hidden = true;
            $('#claim-create-form').hidden = true;
        }
        feedback('正在读取申请…');
        try {
            const query = new URLSearchParams({ size: '20' });
            if (more && cursor) query.set('beforeId', cursor);
            const page = await api('/' + tab + '?' + query);
            if (!Array.isArray(page.content)) throw new Error('认领列表格式不正确');
            for (const claim of page.content) {
                const card = document.createElement('article'); card.className = 'claim-card';
                paragraph(card, `申请 #${claim.id} · ${statuses[claim.status] || claim.status}`);
                paragraph(card, `拾物：${claim.lostPropertyName || '未命名'}（#${claim.lostPropertyId}）\n丢失登记：${claim.lostRegisterName || '未命名'}（#${claim.lostRegisterId}）`);
                window.LostFoundImages.appendImage(card, claim.lostPropertyImageUrl, '拾到物品的照片');
                button(card, '查看详情', () => void detail(claim.id));
                $('#claim-list').append(card);
            }
            cursor = page.nextBeforeId;
            $('#claim-more').hidden = !page.hasMore || !cursor;
            feedback(page.content.length ? '请选择申请查看详情和可用操作。' : '暂无认领申请。');
        } catch (error) {
            feedback('读取申请失败：' + auth.errorMessage(error));
            $('#claim-more').hidden = true;
        } finally { setBusy(false); }
    }
    function renderDetail(claim) {
        const container = $('#claim-detail');
        container.replaceChildren(); container.hidden = false;
        paragraph(container, `申请 #${claim.id} · ${statuses[claim.status] || claim.status}`);
        paragraph(container, `拾物：${claim.lostPropertyName || '未命名'}（#${claim.lostPropertyId}）\n丢失登记：${claim.lostRegisterName || '未命名'}（#${claim.lostRegisterId}）`);
        paragraph(container, '认领依据：' + (claim.verificationText || '无'));
        window.LostFoundImages.appendImage(container, claim.lostPropertyImageUrl, '拾到物品的照片');
        window.LostFoundImages.appendImage(container, claim.lostRegisterImageUrl, '丢失物品的照片');
        if (claim.rejectReason) paragraph(container, '拒绝原因：' + claim.rejectReason);
        const actions = document.createElement('div'); actions.className = 'claim-actions'; container.append(actions);
        const finder = claim.viewerRole === 'FINDER';
        const claimant = claim.viewerRole === 'CLAIMANT';
        if (finder && claim.status === 'PENDING') {
            button(actions, '同意认领', () => void mutate(claim, 'approve'));
            const label = document.createElement('label'); label.className = 'field-label'; label.textContent = '拒绝原因';
            const reason = document.createElement('textarea'); reason.id = 'claim-reject-reason'; reason.maxLength = 255;
            label.append(reason); container.append(label);
            button(actions, '拒绝申请', () => {
                const rejectReason = reason.value.trim();
                if (!rejectReason) { feedback('请填写拒绝原因。'); reason.focus(); return; }
                void mutate(claim, 'reject', { rejectReason });
            });
        }
        if (finder && claim.status === 'APPROVED') button(actions, '确认已交接', () => void mutate(claim, 'handover'));
        if (claimant && claim.status === 'HANDOVER_PENDING') button(actions, '确认已收到物品', () => void mutate(claim, 'receive'));
        if (claimant && ['PENDING', 'APPROVED'].includes(claim.status)) button(actions, '取消申请', () => void mutate(claim, 'cancel'));
        const contact = document.createElement('p'); contact.id = 'claim-contact'; container.append(contact);
        if (['APPROVED', 'HANDOVER_PENDING', 'COMPLETED'].includes(claim.status)) {
            button(actions, '查看对方联系方式', async () => {
                if (busy) return;
                setBusy(true); contact.textContent = '';
                try {
                    const result = await api(`/${claim.id}/contact`);
                    if (!$('#claims-dialog').open || !contact.isConnected) return;
                    contact.textContent = `${result.realName || '姓名未填写'} · ${result.phone || '手机号未填写'}`;
                } catch (error) { feedback('读取联系方式失败：' + auth.errorMessage(error)); }
                finally { setBusy(false); }
            });
        }
    }
    async function detail(id) {
        if (busy) return;
        setBusy(true);
        $('#claim-detail').replaceChildren(); $('#claim-detail').hidden = true;
        feedback('正在读取申请详情…');
        try {
            const claim = await api('/' + id);
            renderDetail(claim); feedback('请核对物品和认领依据后操作。');
        } catch (error) { feedback('读取详情失败：' + auth.errorMessage(error)); }
        finally { setBusy(false); }
    }
    async function mutate(claim, action, data) {
        if (busy) return;
        const confirmation = {
            approve: '确认认领依据后同意此申请？物品将进入待交接状态。',
            reject: '确认拒绝此申请？', handover: '确认已经把物品交给申请人？',
            receive: '确认已实际收到物品？确认后登记将标记为已归还。', cancel: '确认取消此认领申请？'
        };
        if (!window.confirm(confirmation[action])) return;
        setBusy(true);
        // Never retry a mutation automatically; an interrupted response may already be committed.
        let message;
        try {
            await api(`/${claim.id}/${action}`, { method: 'POST',
                ...(data ? { headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(data) } : {}) });
            message = '操作成功，状态已更新。';
        } catch (error) { message = '操作未确认，请核对最新状态后再决定是否重试：' + auth.errorMessage(error); }
        finally { setBusy(false); }
        await list();
        await detail(claim.id);
        feedback(message);
    }
    $('#claims-open').addEventListener('click', () => {
        if (!$('#claims-dialog').open) $('#claims-dialog').showModal();
        void list();
    });
    document.querySelectorAll('[data-claim-tab]').forEach(node => node.addEventListener('click', () => {
        if (busy) return;
        tab = node.dataset.claimTab;
        document.querySelectorAll('[data-claim-tab]').forEach(item => item.setAttribute('aria-pressed', String(item === node)));
        void list();
    }));
    $('#claim-more').addEventListener('click', () => void list(true));
    $('#claim-create-open').addEventListener('click', () => {
        if (busy) return;
        $('#claim-create-form').hidden = false; $('#claim-detail').hidden = true;
        $('#claim-detail').replaceChildren();
        feedback('请先核对拾物编号和本人的丢失登记编号。');
    });
    $('#claim-create-form').addEventListener('submit', async event => {
        event.preventDefault(); if (busy) return;
        const form = event.currentTarget;
        const data = Object.fromEntries(new FormData(form));
        const lostPropertyId = Number(data.lostPropertyId), lostRegisterId = Number(data.lostRegisterId);
        const verificationText = data.verificationText.trim();
        if (![lostPropertyId, lostRegisterId].every(id => Number.isSafeInteger(id) && id > 0) || !verificationText) {
            feedback('请填写有效的登记编号和认领依据。'); return;
        }
        setBusy(true);
        try {
            const result = await api('', { method: 'POST', headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ lostPropertyId, lostRegisterId, verificationText }) });
            form.reset(); $('#claim-create-form').hidden = true; tab = 'mine';
            setBusy(false); await list(); await detail(result.id); feedback('申请已提交，请等待拾得人审核。');
        } catch (error) { feedback('提交结果未确认，请先查看“我发起的”核实：' + auth.errorMessage(error)); }
        finally { setBusy(false); }
    });
    $('#claims-dialog').addEventListener('close', () => {
        // Contact data is only displayed on demand and never written to browser storage.
        const contact = $('#claim-contact'); if (contact) contact.textContent = '';
    });
})();
