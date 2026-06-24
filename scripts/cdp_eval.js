// Evaluate a JS expression in a WebView page via Chrome DevTools Protocol.
// Usage: node cdp_eval.js <wsUrl> "<expression>"
// If evaluation does not return within the timeout, the JS main thread is blocked.
const wsUrl = process.argv[2];
const expr = process.argv[3];
const TIMEOUT_MS = 6000;
const sock = new WebSocket(wsUrl);
const reqId = 1;
sock.addEventListener('open', () => {
    sock.send(JSON.stringify({
        id: reqId,
        method: 'Runtime.evaluate',
        params: { expression: expr, returnByValue: true, awaitPromise: true },
    }));
});
sock.addEventListener('message', (e) => {
    const msg = JSON.parse(e.data);
    if (msg.id === reqId) {
        if (msg.result && msg.result.exceptionDetails) {
            console.log('JS EXCEPTION:', JSON.stringify(msg.result.exceptionDetails.exception));
        }
        else {
            console.log(JSON.stringify(msg.result && msg.result.result && msg.result.result.value));
        }
        sock.close();
        process.exit(0);
    }
});
sock.addEventListener('error', () => { console.error('WS_ERROR'); process.exit(1); });
setTimeout(() => { console.error('TIMEOUT_JS_BLOCKED'); process.exit(2); }, TIMEOUT_MS);
