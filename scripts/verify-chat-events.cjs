const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const context = { window: {}, TextDecoder };
vm.runInNewContext(fs.readFileSync(path.join(__dirname, '../src/main/resources/static/js/chat-events.js'), 'utf8'), context);
const events = context.window.LostFoundChatEvents;
const text = { type: 'text', content: '钱包\n黑色。<script>window.injected=true</script>' };
const saved = { type: 'registration_saved', registrationType: 'lost', registrationId: 81, action: 'CREATE' };
const wire = JSON.stringify(text) + '\r\n\n' + JSON.stringify(saved) + '\n';

async function parse(parts) {
    const received = [];
    const body = new ReadableStream({ start(controller) { parts.forEach(part => controller.enqueue(part)); controller.close(); } });
    await events.read(new Response(body, { headers: { 'Content-Type': 'application/x-ndjson;charset=UTF-8' } }), event => received.push(event));
    return JSON.parse(JSON.stringify(received));
}

(async () => {
    const bytes = new TextEncoder().encode(wire);
    assert.deepEqual(await parse(Array.from(bytes, byte => Uint8Array.of(byte))), [text, saved]);
    assert.deepEqual(await parse([bytes]), [text, saved]);
    const fallback = [];
    await events.read({ headers: new Headers({ 'Content-Type': 'application/x-ndjson' }),
        text: async () => wire }, event => fallback.push(event));
    assert.equal(fallback.length, 2);
    for (const invalid of ['{"type":"text"', '{broken}\n', JSON.stringify(text), '\ufffd\n']) {
        await assert.rejects(parse([new TextEncoder().encode(invalid)]));
    }
    await assert.rejects(parse([Uint8Array.of(0xff)]));
    await assert.rejects(events.read(new Response('plain', { headers: { 'Content-Type': 'text/plain' } }), () => {}));
    for (const id of [null, 0, -1, 1.5, Number.MAX_SAFE_INTEGER + 1, '../1', '0', '9223372036854775808']) {
        assert.throws(() => events.registration({ ...saved, registrationId: id }));
    }
    assert.equal(events.registration(saved).id, '81');
    assert.equal(events.registration({ ...saved, registrationId: '9223372036854775807' }).id, '9223372036854775807');
    assert.throws(() => events.registration({ ...saved, registrationType: 'search' }));
    assert.throws(() => events.registration({ ...saved, action: 'DELETE' }));
    console.log('PASS: NDJSON byte boundaries, UTF-8, CRLF, coalesced events, fallback, malformed/truncated responses and registration identity validation.');
})().catch(error => { console.error(error); process.exitCode = 1; });
