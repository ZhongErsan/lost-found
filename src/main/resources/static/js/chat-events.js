(() => {
    'use strict';

    async function read(response, onEvent) {
        const mediaType = (response.headers.get('content-type') || '').split(';')[0].trim().toLowerCase();
        if (mediaType !== 'application/x-ndjson') throw new Error('unexpected-response');
        let buffer = '';
        function consume(text, end = false) {
            buffer += text;
            let boundary;
            while ((boundary = buffer.indexOf('\n')) !== -1) {
                const line = buffer.slice(0, boundary).trim();
                buffer = buffer.slice(boundary + 1);
                if (line) onEvent(JSON.parse(line));
            }
            if (buffer.length > 1_000_000) throw new Error('invalid-event');
            if (end && buffer.trim()) throw new Error('incomplete-event');
        }

        if (!response.body?.getReader) {
            consume(await response.text(), true);
            return;
        }
        const reader = response.body.getReader();
        const decoder = new TextDecoder('utf-8', { fatal: true });
        let completed = false;
        try {
            while (true) {
                const { value, done } = await reader.read();
                if (done) break;
                consume(decoder.decode(value, { stream: true }));
            }
            consume(decoder.decode(), true);
            completed = true;
        } finally {
            if (!completed) await reader.cancel().catch(() => {});
            reader.releaseLock();
        }
    }

    function registration(event) {
        const id = event.registrationId;
        const validId = typeof id === 'number' ? Number.isSafeInteger(id) && id > 0
            : typeof id === 'string' && /^[1-9]\d{0,18}$/.test(id) && BigInt(id) <= 9223372036854775807n;
        if (!validId || !['lost', 'found'].includes(event.registrationType)
                || !['CREATE', 'UPDATE'].includes(event.action)) throw new Error('invalid-event');
        return { type: event.registrationType, id: String(id), action: event.action };
    }

    window.LostFoundChatEvents = Object.freeze({ read, registration });
})();
