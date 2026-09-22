package io.github.jahn2007.fourma;

/** JavaScript injected only into the confirmed 7MA rendering WebView. */
final class EnhancementScript {
    static final String SOURCE = """
            (function () {
              'use strict';
              const VERSION = '0.2.0';
              if (!document.body || document.getElementsByTagName('*').length < 40) {
                return '4MA_ENHANCE|skip_shell';
              }
              if (window.__fourma && window.__fourma.version === VERSION) {
                window.__fourma.scan();
                return '4MA_ENHANCE|already_ready';
              }
              if (window.__fourma && typeof window.__fourma.destroy === 'function') {
                try { window.__fourma.destroy(); } catch (_) {}
              }

              const STORAGE = 'fourma.state.v1';
              function config() {
                const value = window.__fourmaConfig || {};
                return {
                  guardUnlockMs: Number(value.guardUnlockMs) || 250000,
                  rerentMs: Number(value.rerentMs) || 1110000,
                  actionCooldownMs: Number(value.actionCooldownMs) || 2500,
                  stepDelayMs: Number(value.stepDelayMs) || 2800,
                  flowTimeoutMs: Number(value.flowTimeoutMs) || 6000,
                  promptCooldownMs: Number(value.promptCooldownMs) || 30000
                };
              }
              const state = {
                expanded: false,
                auto: false,
                busy: false,
                startedAt: 0,
                lockSeenAt: 0,
                vehicleNo: '',
                lastActionAt: 0,
                lastPromptAt: 0,
                status: '等待识别页面'
              };
              try {
                const saved = JSON.parse(sessionStorage.getItem(STORAGE) || '{}');
                state.auto = saved.auto === true;
                state.startedAt = Number(saved.startedAt) || 0;
                state.vehicleNo = /^\\d{6,12}$/.test(saved.vehicleNo || '') ? saved.vehicleNo : '';
              } catch (_) {}

              function persist() {
                try {
                  sessionStorage.setItem(STORAGE, JSON.stringify({
                    auto: state.auto,
                    startedAt: state.startedAt,
                    vehicleNo: state.vehicleNo
                  }));
                } catch (_) {}
              }

              function normalize(value) {
                return String(value || '').replace(/\\s+/g, '').trim();
              }

              function visible(element) {
                if (!element || element.closest('#fourma-host')) return false;
                const rect = element.getBoundingClientRect();
                const style = getComputedStyle(element);
                return rect.width > 1 && rect.height > 1 && style.display !== 'none'
                    && style.visibility !== 'hidden' && Number(style.opacity || 1) !== 0;
              }

              function findText(labels) {
                const wanted = labels.map(normalize);
                const nodes = document.querySelectorAll(
                    'button,[role="button"],wx-button,wx-view,view,div,span');
                let best = null;
                for (const node of nodes) {
                  if (!visible(node)) continue;
                  const text = normalize(node.innerText || node.textContent);
                  if (!wanted.includes(text)) continue;
                  if (!best || node.childElementCount < best.childElementCount) best = node;
                }
                return best;
              }

              function safeClick(element, reason) {
                if (!element || state.busy && reason === 'prompt') return false;
                const now = Date.now();
                if (now - state.lastActionAt < config().actionCooldownMs) return false;
                state.lastActionAt = now;
                try {
                  element.click();
                  setStatus('已执行：' + reason);
                  return true;
                } catch (_) {
                  setStatus(reason + '失败');
                  return false;
                }
              }

              function captureVehicleNo() {
                const text = String(document.body.innerText || '');
                const labeled = text.match(/(?:车辆编号|车辆号|单车编号|编号)\\s*[:：]?\\s*(\\d{6,12})/);
                if (labeled) state.vehicleNo = labeled[1];
                const inputs = document.querySelectorAll('input');
                for (const input of inputs) {
                  if (/^\\d{6,12}$/.test(input.value || '')) state.vehicleNo = input.value;
                }
                persist();
                updateUi();
                return state.vehicleNo;
              }

              function setInputValue(input, value) {
                if (!input) return false;
                try {
                  const setter = Object.getOwnPropertyDescriptor(
                      window.HTMLInputElement.prototype, 'value').set;
                  setter.call(input, value);
                  input.dispatchEvent(new Event('input', { bubbles: true }));
                  input.dispatchEvent(new Event('change', { bubbles: true }));
                  return true;
                } catch (_) { return false; }
              }

              function delay(ms) {
                return new Promise(resolve => setTimeout(resolve, ms));
              }

              async function waitFor(factory, timeout) {
                const end = Date.now() + timeout;
                while (Date.now() < end) {
                  const result = factory();
                  if (result) return result;
                  await delay(400);
                }
                return null;
              }

              async function borrowCurrent() {
                if (state.busy) return;
                const number = captureVehicleNo();
                const input = document.querySelector('input[type="number"],input[inputmode="numeric"],input');
                if (input && number) {
                  setInputValue(input, number);
                  await delay(config().stepDelayMs);
                }
                const start = findText(['开始用车', '立即用车', '确认用车', '扫码开锁']);
                if (!start) {
                  setStatus(number ? '未找到开始用车按钮' : '请先保存完整车辆编号');
                  return;
                }
                if (safeClick(start, '一键借车')) {
                  state.startedAt = Date.now();
                  persist();
                  updateUi();
                }
              }

              async function returnAndReborrow() {
                if (state.busy) return;
                const number = captureVehicleNo();
                if (!number) {
                  setStatus('缺少完整车辆编号，请在面板中填写');
                  state.auto = false;
                  persist();
                  updateUi();
                  return;
                }
                const returnButton = findText(['我要还车', '还车']);
                if (!returnButton) {
                  setStatus('未找到还车按钮，流程已停止');
                  state.auto = false;
                  persist();
                  updateUi();
                  return;
                }

                state.busy = true;
                setStatus('正在还车…');
                safeClick(returnButton, '还车');
                await delay(config().stepDelayMs);
                const confirm = await waitFor(
                    () => findText(['确认还车', '确定还车', '确认']), config().flowTimeoutMs);
                if (confirm) {
                  safeClick(confirm, '确认还车');
                  await delay(config().stepDelayMs);
                }

                let input = await waitFor(
                    () => document.querySelector('input[type="number"],input[inputmode="numeric"],input'),
                    config().flowTimeoutMs);
                if (!input) {
                  const manual = findText(['输入车辆编号', '输入车编号', '编号用车']);
                  if (manual) {
                    safeClick(manual, '打开编号用车');
                    await delay(config().stepDelayMs);
                    input = document.querySelector('input[type="number"],input[inputmode="numeric"],input');
                  }
                }
                if (!input || !setInputValue(input, number)) {
                  state.busy = false;
                  state.auto = false;
                  setStatus('未进入编号借车页，流程已停止');
                  persist();
                  updateUi();
                  return;
                }

                await delay(config().stepDelayMs);
                const start = findText(['开始用车', '立即用车', '确认用车']);
                if (!start || !safeClick(start, '重新借车')) {
                  state.busy = false;
                  state.auto = false;
                  setStatus('未找到借车按钮，流程已停止');
                  persist();
                  updateUi();
                  return;
                }
                state.startedAt = Date.now();
                state.lockSeenAt = 0;
                state.busy = false;
                setStatus('已提交重新借车');
                persist();
                updateUi();
              }

              function dismissPrompt() {
                if (window.__fourmaAutoDismiss === false) return;
                const now = Date.now();
                if (now - state.lastPromptAt < config().promptCooldownMs) return;
                const bodyText = normalize(document.body.innerText || '');
                if (!bodyText.includes('车辆所属运营区')) return;
                const button = findText(['继续用车']);
                if (button && safeClick(button, 'prompt')) state.lastPromptAt = now;
              }

              function installInlineBorrow() {
                if (document.querySelector('[data-fourma-inline="borrow"]')) return;
                const number = captureVehicleNo();
                if (!number || !findText(['开始用车', '立即用车', '确认用车'])) return;
                const nodes = document.querySelectorAll('span,div,wx-view,view');
                let anchor = null;
                for (const node of nodes) {
                  if (visible(node) && normalize(node.innerText || node.textContent).includes(number)
                      && node.childElementCount < 4) { anchor = node; break; }
                }
                if (!anchor) return;
                const button = document.createElement('button');
                button.dataset.fourmaInline = 'borrow';
                button.textContent = '一键借车';
                button.style.cssText = 'margin-left:8px;padding:5px 10px;border:0;border-radius:14px;'
                    + 'background:#1677ff;color:#fff;font-size:12px;z-index:2147483646;';
                button.addEventListener('click', event => {
                  event.preventDefault(); event.stopPropagation(); borrowCurrent();
                });
                anchor.appendChild(button);
              }

              function setStatus(message) {
                state.status = message;
                updateUi();
              }

              const host = document.createElement('div');
              host.id = 'fourma-host';
              host.style.cssText = 'position:fixed;right:12px;bottom:118px;z-index:2147483647;'
                  + 'font-family:sans-serif;color:#fff;';
              const shadow = host.attachShadow ? host.attachShadow({mode: 'open'}) : host;
              shadow.innerHTML = `
                <style>
                  *{box-sizing:border-box}.pill{width:54px;height:54px;border:0;border-radius:27px;
                  background:#1677ff;color:#fff;font-weight:700;box-shadow:0 3px 12px #0005}
                  .panel{display:none;width:248px;padding:12px;border-radius:14px;background:#20242bcc;
                  backdrop-filter:blur(10px);box-shadow:0 4px 20px #0007}.panel.open{display:block}
                  .title{font-size:16px;font-weight:700;margin-bottom:8px}.status{min-height:34px;font-size:12px;
                  color:#d9e1ec;margin-bottom:8px}.grid{display:grid;grid-template-columns:1fr 1fr;gap:7px}
                  button.action{min-height:38px;border:0;border-radius:9px;background:#1677ff;color:white;
                  font-size:13px}.danger{background:#d94b4b!important}.number{display:flex;gap:6px;margin-top:8px}
                  input{min-width:0;flex:1;border:1px solid #667085;border-radius:8px;padding:8px;background:#fff;
                  color:#111}.save{border:0;border-radius:8px;background:#667085;color:#fff}.foot{font-size:11px;
                  color:#b9c1cc;margin-top:8px}.close{float:right;border:0;background:transparent;color:#fff;font-size:18px}
                </style>
                <button class="pill" id="pill">4MA</button>
                <div class="panel" id="panel">
                  <button class="close" id="close">×</button><div class="title">4MA 用车助手</div>
                  <div class="status" id="status"></div>
                  <div class="grid">
                    <button class="action" id="auto"></button>
                    <button class="action" id="borrow">一键借车</button>
                    <button class="action danger" id="rerent">还车重借</button>
                    <button class="action" id="rescan">重新识别</button>
                  </div>
                  <div class="number"><input id="number" inputmode="numeric" placeholder="完整车辆编号">
                  <button class="save" id="save">保存</button></div>
                  <div class="foot" id="foot"></div>
                </div>`;
              document.documentElement.appendChild(host);

              const ui = id => shadow.getElementById(id);
              ui('pill').onclick = () => { state.expanded = true; updateUi(); };
              ui('close').onclick = () => { state.expanded = false; updateUi(); };
              ui('auto').onclick = () => {
                state.auto = !state.auto;
                if (state.auto && !state.startedAt) state.startedAt = Date.now();
                setStatus(state.auto ? '守护已启动' : '守护已停止');
                persist(); updateUi();
              };
              ui('borrow').onclick = borrowCurrent;
              ui('rerent').onclick = returnAndReborrow;
              ui('rescan').onclick = () => { captureVehicleNo(); scan(); setStatus('页面已重新识别'); };
              ui('save').onclick = () => {
                const value = String(ui('number').value || '').trim();
                if (!/^\\d{6,12}$/.test(value)) { setStatus('车辆编号应为 6–12 位数字'); return; }
                state.vehicleNo = value; persist(); setStatus('车辆编号已保存'); updateUi();
              };

              function updateUi() {
                if (!host.isConnected) return;
                ui('panel').classList.toggle('open', state.expanded);
                ui('pill').style.display = state.expanded ? 'none' : 'block';
                ui('status').textContent = state.status;
                ui('auto').textContent = state.auto ? '停止守护' : '启动守护';
                ui('auto').classList.toggle('danger', state.auto);
                if (shadow.activeElement !== ui('number')) ui('number').value = state.vehicleNo;
                const elapsed = state.startedAt ? Math.max(0, Date.now() - state.startedAt) : 0;
                const remaining = Math.max(0, config().rerentMs - elapsed);
                const minutes = Math.floor(remaining / 60000);
                const seconds = Math.floor((remaining % 60000) / 1000);
                ui('foot').textContent = (state.vehicleNo ? '车辆 ' + state.vehicleNo : '未识别完整编号')
                    + (state.auto ? ' · 重借倒计时 ' + minutes + ':' + String(seconds).padStart(2, '0') : '');
              }

              function scan() {
                captureVehicleNo();
                dismissPrompt();
                installInlineBorrow();
                const unlock = findText(['再次开锁', '继续骑行', '解除临时锁车']);
                if (unlock) {
                  if (!state.lockSeenAt) state.lockSeenAt = Date.now();
                  if (state.auto && Date.now() - state.lockSeenAt >= config().guardUnlockMs
                      && !state.busy) {
                    if (safeClick(unlock, '临时锁车超时前开锁')) state.lockSeenAt = 0;
                  }
                } else {
                  state.lockSeenAt = 0;
                }
                updateUi();
              }

              let scanTimer = 0;
              const observer = new MutationObserver(() => {
                clearTimeout(scanTimer);
                scanTimer = setTimeout(scan, 250);
              });
              observer.observe(document.documentElement, {childList:true, subtree:true, characterData:true});
              const ticker = setInterval(() => {
                scan();
                if (state.auto && state.startedAt && Date.now() - state.startedAt >= config().rerentMs
                    && !state.busy) returnAndReborrow();
              }, 1000);

              window.__fourma = {
                version: VERSION,
                scan,
                destroy: function () {
                  observer.disconnect(); clearInterval(ticker); clearTimeout(scanTimer);
                  host.remove();
                  document.querySelectorAll('[data-fourma-inline]').forEach(node => node.remove());
                }
              };
              scan();
              return '4MA_ENHANCE|ready';
            })();
            """;

    private EnhancementScript() {
    }
}
