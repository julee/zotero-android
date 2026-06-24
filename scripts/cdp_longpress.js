// Long-press at (x,y) in the WebView via CDP to trigger native word selection
// (which triggers the Android text-selection ActionMode). Usage:
//   node cdp_longpress.js <wsUrl> <x> <y>
const wsUrl = process.argv[2];
const x = parseInt(process.argv[3], 10);
const y = parseInt(process.argv[4], 10);
const sock = new WebSocket(wsUrl);
let id = 0;
const pending = new Map();
function send(method, params) {
    return new Promise((resolve) => {
        const myId = ++id;
        pending.set(myId, resolve);
        sock.send(JSON.stringify({ id: myId, method, params }));
    });
}
sock.addEventListener('message', (e) => {
    const m = JSON.parse(e.data);
    if (m.id && pending.has(m.id)) { pending.get(m.id)(m.result); pending.delete(m.id); }
});
function sleep(ms) { return new Promise(r => setTimeout(r, ms)); }
async function main() {
    await new Promise(r => sock.addEventListener('open', r));
    await send('Input.dispatchTouchEvent', { type: 'touchStart', touchPoints: [{ x, y }] });
    await sleep(700); // long-press
    await send('Input.dispatchTouchEvent', { type: 'touchEnd', touchPoints: [] });
    await sleep(300);
    let sel = await send('Runtime.evaluate', { expression: "(function(){try{var d=document.querySelector('iframe').contentDocument;return d.getSelection().toString().slice(0,40);}catch(e){return 'err:'+e.message}})()", returnByValue: true });
    console.log('selectedText=' + JSON.stringify(sel && sel.result && sel.result.value));
    process.exit(0);
}
sock.addEventListener('error', () => { console.error('WS_ERROR'); process.exit(1); });
main();
