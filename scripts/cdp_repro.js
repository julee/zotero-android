// Reproduce annotation freeze via CDP: set text tool, dispatch taps to create
// several text annotations, and after each check whether the JS thread is still
// responsive (Runtime.evaluate returns). Reports if/when it blocks.
const wsUrl = process.argv[2];
const sock = new WebSocket(wsUrl);
let id = 0;
const pending = new Map();
function send(method, params) {
    return new Promise((resolve, reject) => {
        const myId = ++id;
        pending.set(myId, { resolve, reject });
        const timer = setTimeout(() => {
            if (pending.has(myId)) { pending.delete(myId); reject(new Error('TIMEOUT (JS blocked) on ' + method)); }
        }, 6000);
        pending.get(myId).timer = timer;
        sock.send(JSON.stringify({ id: myId, method, params }));
    });
}
sock.addEventListener('message', (e) => {
    const msg = JSON.parse(e.data);
    if (msg.id && pending.has(msg.id)) {
        const p = pending.get(msg.id); clearTimeout(p.timer); pending.delete(msg.id);
        p.resolve(msg.result);
    }
});
function evalJs(expr) {
    return send('Runtime.evaluate', { expression: expr, returnByValue: true, awaitPromise: true })
        .then(r => r && r.result && r.result.value);
}
async function tap(x, y) {
    await send('Input.dispatchTouchEvent', { type: 'touchStart', touchPoints: [{ x, y }] });
    await send('Input.dispatchTouchEvent', { type: 'touchEnd', touchPoints: [] });
}
async function main() {
    await new Promise(r => sock.addEventListener('open', r));
    await send('Runtime.enable', {});
    for (let i = 0; i < 10; i++) {
        await evalJs("window._view.setTool({type:'text',color:'#ffd400'})");
        let x = 200 + (i % 3) * 200;
        let y = 500 + (i % 4) * 250;
        try {
            await tap(x, y);
        } catch (err) {
            console.log('FREEZE during tap #' + i + ': ' + err.message);
            process.exit(0);
        }
        // check responsiveness + annotation count
        try {
            let info = await evalJs("(function(){try{var d=document.querySelector('iframe').contentDocument;return JSON.stringify({editEls:d.querySelectorAll('.annotationEditorLayer .freeTextEditor, .textAnnotation, [contenteditable]').length});}catch(e){return 'err:'+e.message}})()");
            console.log('tap #' + i + ' at (' + x + ',' + y + ') OK -> ' + info);
        } catch (err) {
            console.log('FREEZE after tap #' + i + ' (JS blocked): ' + err.message);
            process.exit(0);
        }
    }
    console.log('completed 10 taps, no JS block detected');
    process.exit(0);
}
sock.addEventListener('error', () => { console.error('WS_ERROR'); process.exit(1); });
main();
