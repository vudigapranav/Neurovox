// Small DOM helpers shared by the recorder, results and history pages.
// Text is always set with textContent (never innerHTML), so server text cannot inject markup.

export function h(tag, props = {}, ...children) {
  const el = document.createElement(tag);
  for (const [key, value] of Object.entries(props)) {
    if (value === undefined || value === null || value === false) continue;
    if (key === 'class') el.className = value;
    else if (key === 'text') el.textContent = value;
    else if (key.startsWith('on')) el.addEventListener(key.slice(2), value);
    else el.setAttribute(key, value === true ? '' : value);
  }
  for (const child of children.flat()) {
    if (child === null || child === undefined || child === false) continue;
    el.append(child instanceof Node ? child : document.createTextNode(String(child)));
  }
  return el;
}

export function button(label, onclick, { primary = false, id, disabled = false, danger = false } = {}) {
  const cls = primary ? 'button button-primary' : danger ? 'button button-danger' : 'button button-quiet';
  return h('button', { class: cls, type: 'button', id, disabled, onclick }, label);
}

export function alert(text, kind = 'error') {
  return h('p', { class: `alert alert-${kind}`, role: kind === 'error' ? 'alert' : 'status', text });
}

/** "2 Oct 2026, 14:05" in the person's own locale and time zone. */
export function formatDate(iso) {
  if (!iso) return '';
  const d = new Date(iso);
  return d.toLocaleString(undefined, { day: 'numeric', month: 'short', year: 'numeric', hour: '2-digit', minute: '2-digit' });
}

/** Asks for confirmation in an accessible modal dialog. Resolves true or false. */
export function confirmDialog({ title, body, confirmLabel, typeToConfirm = null }) {
  return new Promise((resolve) => {
    const input = typeToConfirm
      ? h('input', { class: 'confirm-input', type: 'text', autocomplete: 'off', 'aria-label': `Type ${typeToConfirm} to confirm` })
      : null;
    const ok = button(confirmLabel, () => close(true), { danger: true });
    const cancel = button('Cancel', () => close(false), { primary: false });
    if (input) {
      ok.disabled = true;
      input.addEventListener('input', () => { ok.disabled = input.value.trim().toLowerCase() !== typeToConfirm; });
    }
    const dialog = h('dialog', { class: 'confirm-dialog', 'aria-labelledby': 'confirmTitle' },
      h('h2', { id: 'confirmTitle', text: title }),
      h('p', { text: body }),
      typeToConfirm ? h('p', { class: 'confirm-hint' }, 'Type ', h('code', { text: typeToConfirm }), ' to confirm.') : null,
      input,
      h('div', { class: 'recorder-actions' }, ok, cancel));
    function close(value) {
      dialog.close();
      dialog.remove();
      resolve(value);
    }
    dialog.addEventListener('cancel', (event) => { event.preventDefault(); close(false); });
    document.body.append(dialog);
    dialog.showModal();
    (input || cancel).focus();
  });
}
