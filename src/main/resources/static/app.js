'use strict';

/*
 * WorkSphere web app: a small single-page front end for the API, served by the application itself.
 * No build step and no libraries. Everything shown comes from the API and is rendered as text
 * (never as HTML), so names and notes can't inject markup.
 *
 * The tokens live in sessionStorage: they are gone when the tab is closed. Access tokens are
 * short-lived; when one expires, the refresh token gets a new pair once, then the request is retried.
 */

const TOKEN_KEY = 'worksphere.token';
const REFRESH_KEY = 'worksphere.refresh';

const state = {
  token: null,
  refreshToken: null,
  me: null,          // {username, roles, employeeId}
  conversationId: null,
  chat: [],
};

// ---- Helpers ------------------------------------------------------------------

class ApiError extends Error {
  constructor(status, message) {
    super(message);
    this.status = status;
  }
}

async function api(method, path, body, retried = false) {
  const headers = { Accept: 'application/json' };
  if (state.token) headers.Authorization = 'Bearer ' + state.token;
  if (body !== undefined) headers['Content-Type'] = 'application/json';

  const response = await fetch(path, {
    method,
    headers,
    body: body === undefined ? undefined : JSON.stringify(body),
  });

  if (response.status === 401 && state.token) {
    if (!retried && await renewTokens()) return api(method, path, body, true);
    endSession('Your session has expired. Please log in again.');
    throw new ApiError(401, 'Not logged in');
  }

  const text = await response.text();
  let data = null;
  if (text) {
    try { data = JSON.parse(text); } catch { data = text; }
  }
  if (!response.ok) throw new ApiError(response.status, errorMessage(response.status, data));
  return data;
}

function errorMessage(status, data) {
  if (status === 403) return "You don't have access to this.";
  if (typeof data === 'string' && data) return data;
  if (data && typeof data === 'object') {
    if (data.message) return data.message;
    if (data.errors) return Object.entries(data.errors).map(([k, v]) => k + ': ' + v).join('; ');
    const values = Object.values(data).filter(v => typeof v === 'string');
    if (values.length) return values.join('; ');
  }
  return 'Something went wrong (' + status + ').';
}

const get = path => api('GET', path);
const post = (path, body) => api('POST', path, body);

function query(params) {
  const q = new URLSearchParams();
  for (const [k, v] of Object.entries(params)) {
    if (v !== null && v !== undefined && v !== '') q.set(k, v);
  }
  const s = q.toString();
  return s ? '?' + s : '';
}

// h('div', {class: 'card'}, 'text', child) - strings become text nodes.
function h(tag, props, ...children) {
  const el = document.createElement(tag);
  for (const [key, value] of Object.entries(props || {})) {
    if (value === null || value === undefined || value === false) continue;
    if (key.startsWith('on')) el.addEventListener(key.slice(2), value);
    else if (key === 'class') el.className = value;
    else el.setAttribute(key, value === true ? '' : value);
  }
  for (const child of children.flat()) {
    if (child === null || child === undefined || child === false) continue;
    el.append(child instanceof Node ? child : document.createTextNode(String(child)));
  }
  return el;
}

const isHr = () => state.me && state.me.roles.some(r => r === 'HR' || r === 'ADMIN');

const today = () => isoDate(new Date());

function isoDate(d) {
  const pad = n => String(n).padStart(2, '0');
  return d.getFullYear() + '-' + pad(d.getMonth() + 1) + '-' + pad(d.getDate());
}

function formatDate(iso) {
  if (!iso) return '';
  const d = new Date(iso.length === 10 ? iso + 'T00:00:00' : iso);
  return d.toLocaleDateString(undefined, { day: 'numeric', month: 'short', year: 'numeric' });
}

function dateRange(start, end) {
  if (!end) return 'from ' + formatDate(start);
  return start === end ? formatDate(start) : formatDate(start) + ' – ' + formatDate(end);
}

const STATUS_CLASS = { APPROVED: 'ok', PENDING: 'warn', REJECTED: 'bad', CANCELLED: '', ACTIVE: 'ok', ON_LEAVE: 'warn', TERMINATED: 'bad' };

const badge = (text, kind) => h('span', { class: 'badge ' + (kind ?? STATUS_CLASS[text] ?? '') }, label(text));

const label = text => String(text).toLowerCase().replace(/_/g, ' ').replace(/^./, c => c.toUpperCase());

const notice = (text, kind = 'error') => h('div', { class: 'notice ' + kind, role: kind === 'error' ? 'alert' : null }, text);

function personLink(person) {
  return person ? h('a', { href: '#/org/' + encodeURIComponent(person.id) }, person.name) : '–';
}

function table(headers, rows) {
  return h('div', { class: 'table-wrap' },
    h('table', {},
      h('thead', {}, h('tr', {}, headers.map(x => h('th', { class: x.num ? 'num' : null }, x.label ?? x)))),
      h('tbody', {}, rows)));
}

// Runs an action from a button, showing errors next to it and disabling it meanwhile.
async function act(button, errorBox, action) {
  button.disabled = true;
  errorBox.replaceChildren();
  try {
    await action();
  } catch (e) {
    if (e.status !== 401) errorBox.replaceChildren(notice(e.message));
    button.disabled = false;
  }
}

// ---- Shell ----------------------------------------------------------------------

const VIEWS = [
  { path: 'org', title: 'Org chart', show: () => true, render: orgView },
  { path: 'leave', title: 'My leave', show: () => !!state.me.employeeId, render: leaveView },
  { path: 'approvals', title: 'Approvals', show: () => true, render: approvalsView },
  { path: 'calendar', title: 'Team calendar', show: () => true, render: calendarView },
  { path: 'analytics', title: 'Analytics', show: isHr, render: analyticsView },
  { path: 'assistant', title: 'Assistant', show: () => true, render: assistantView },
];

const main = document.getElementById('main');

function renderNav(current) {
  document.getElementById('nav').replaceChildren(...VIEWS.filter(v => v.show()).map(v =>
    h('a', { href: '#/' + v.path, 'aria-current': v.path === current ? 'page' : null }, v.title)));
  document.getElementById('who-name').textContent = state.me.username;
}

async function route() {
  if (!state.me) {
    document.getElementById('topbar').hidden = true;
    loginView();
    return;
  }
  document.getElementById('topbar').hidden = false;

  const [path, ...args] = location.hash.replace(/^#\/?/, '').split('/').map(decodeURIComponent);
  const view = VIEWS.find(v => v.path === path && v.show()) || VIEWS[0];
  renderNav(view.path);
  document.title = view.title + ' – WorkSphere';

  main.replaceChildren(h('p', { class: 'muted' }, 'Loading…'));
  try {
    const content = await view.render(...args);
    main.replaceChildren(content);
  } catch (e) {
    if (e.status !== 401) main.replaceChildren(h('h1', {}, view.title), notice(e.message));
  }
}

// One refresh at a time: requests failing together wait for the same one.
let renewing = null;

function renewTokens() {
  if (!state.refreshToken) return Promise.resolve(false);
  renewing ??= (async () => {
    try {
      const response = await fetch('/auth/refresh', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', Accept: 'application/json' },
        body: JSON.stringify({ refreshToken: state.refreshToken }),
      });
      if (!response.ok) return false;
      saveTokens(await response.json());
      return true;
    } catch {
      return false;
    } finally {
      renewing = null;
    }
  })();
  return renewing;
}

function saveTokens(tokens) {
  state.token = tokens.accessToken;
  state.refreshToken = tokens.refreshToken;
  try {
    sessionStorage.setItem(TOKEN_KEY, tokens.accessToken);
    sessionStorage.setItem(REFRESH_KEY, tokens.refreshToken);
  } catch { /* storage unavailable */ }
}

// Forgets the session in this tab and shows the login page.
function endSession(message) {
  state.token = null;
  state.refreshToken = null;
  state.me = null;
  state.conversationId = null;
  state.chat = [];
  try {
    sessionStorage.removeItem(TOKEN_KEY);
    sessionStorage.removeItem(REFRESH_KEY);
  } catch { /* storage unavailable */ }
  location.hash = '';
  loginView(message);
}

// Logs out on the server too, so the tokens stop working everywhere.
async function logout() {
  try { await api('POST', '/auth/logout'); } catch { /* ends locally either way */ }
  endSession('You have been logged out.');
}

document.getElementById('logout').addEventListener('click', () => logout());
window.addEventListener('hashchange', route);

// ---- Login ----------------------------------------------------------------------

function loginView(message) {
  document.getElementById('topbar').hidden = true;
  const errors = h('div', {}, message ? notice(message, 'info') : null);
  const username = h('input', { name: 'username', autocomplete: 'username', required: true });
  const password = h('input', { name: 'password', type: 'password', autocomplete: 'current-password', required: true });
  const submit = h('button', { class: 'primary', type: 'submit' }, 'Log in');

  const form = h('form', {
    onsubmit: event => {
      event.preventDefault();
      act(submit, errors, async () => {
        const tokens = await post('/auth/login', { username: username.value.trim(), password: password.value });
        saveTokens(tokens);
        await startSession();
        route();
      });
    },
  }, h('label', {}, 'Username', username), h('label', {}, 'Password', password), submit);

  main.replaceChildren(h('div', { class: 'login card' }, h('h1', {}, 'WorkSphere'),
    h('p', { class: 'subtitle' }, 'Log in to continue.'), errors, form));
  username.focus();
}

async function startSession() {
  state.me = await get('/auth/me');
}

// ---- Org chart --------------------------------------------------------------------

async function orgView(id) {
  const employeeId = id || state.me.employeeId;
  const results = h('div', {});
  const search = h('input', { type: 'search', placeholder: 'Find someone by name', 'aria-label': 'Find someone by name' });
  let timer;
  search.addEventListener('input', () => {
    clearTimeout(timer);
    timer = setTimeout(async () => {
      const name = search.value.trim();
      if (!name) { results.replaceChildren(); return; }
      try {
        const page = await get('/employees' + query({ name, size: 8 }));
        results.replaceChildren(page.content.length
          ? h('div', { class: 'grid' }, page.content.map(personCard))
          : h('p', { class: 'empty' }, 'Nobody found.'));
      } catch (e) { results.replaceChildren(notice(e.message)); }
    }, 250);
  });

  const header = h('div', {}, h('h1', {}, 'Org chart'), h('div', { class: 'row' }, search), results);
  if (!employeeId) {
    return h('div', {}, header, notice('Your login is not linked to an employee. Search for someone to start.', 'info'));
  }

  const [person, chain, reports, projects] = await Promise.all([
    get('/employees/' + encodeURIComponent(employeeId)),
    get('/employees/' + encodeURIComponent(employeeId) + '/reporting-chain'),
    get('/employees/' + encodeURIComponent(employeeId) + '/direct-reports?size=100'),
    get('/employees/' + encodeURIComponent(employeeId) + '/projects'),
  ]);

  const history = h('div', {});
  const historyButton = h('button', { type: 'button' }, 'Show reporting history');
  historyButton.addEventListener('click', () => act(historyButton, history, async () => {
    history.replaceChildren(reportingHistory(await get('/employees/' + encodeURIComponent(employeeId) + '/reporting-history')));
    historyButton.remove();
  }));

  return h('div', {}, header,
    h('h2', {}, 'Reporting line'),
    chain.length
      ? h('div', { class: 'chain' }, [...chain].reverse().flatMap(m => [personLink(m), ' › ']), h('strong', {}, person.name))
      : h('p', { class: 'muted' }, person.name + ' is at the top of the organisation.'),
    h('div', { class: 'card' }, profile(person)),
    h('h2', {}, 'Direct reports (' + reports.totalElements + ')'),
    reports.content.length ? h('div', { class: 'grid' }, reports.content.map(personCard)) : h('p', { class: 'empty' }, 'No direct reports.'),
    h('h2', {}, 'Projects'),
    projectsTable(projects),
    h('h2', {}, 'History'),
    historyButton, history);
}

function personCard(person) {
  return h('a', { class: 'card', href: '#/org/' + encodeURIComponent(person.id) },
    h('h3', {}, person.name), h('p', { class: 'muted small' }, person.jobTitle || 'No job title'));
}

function profile(p) {
  return h('div', {},
    h('div', { class: 'spread' }, h('div', {}, h('h3', {}, p.name), h('p', { class: 'muted' }, p.jobTitle || 'No job title')), badge(p.status)),
    h('p', { class: 'small' }, [
      p.department ? 'Department: ' + p.department.name : 'No department',
      p.office ? ' · Office: ' + p.office.name + ', ' + p.office.city : '',
      ' · Allocated today: ' + p.allocationPercent + '%',
      p.email ? ' · ' + p.email : '',
    ].join('')),
    p.skills.length
      ? h('div', { class: 'row', style: 'margin-top:0.5rem' }, p.skills.map(s => h('span', { class: 'badge', title: 'Level ' + s.level + ' of 5' }, s.name + ' · ' + s.level)))
      : h('p', { class: 'muted small' }, 'No skills recorded.'));
}

function projectsTable(projects) {
  if (!projects.assignments.length) return h('p', { class: 'empty' }, 'Not on any projects.');
  return table(['Project', 'Status', 'Role', { label: 'Allocation', num: true }, 'Dates'],
    projects.assignments.map(a => h('tr', {},
      h('td', {}, a.project.name), h('td', {}, badge(a.project.status, a.project.status === 'ACTIVE' ? 'ok' : '')),
      h('td', {}, a.role || '–'), h('td', { class: 'num' }, a.allocationPercent + '%'),
      h('td', {}, dateRange(a.startDate, a.endDate)))));
}

function reportingHistory(history) {
  const periods = (list, empty) => list.length
    ? table(['Name', 'From', 'To'], list.map(p => h('tr', {},
        h('td', {}, p.name || '(no longer exists)'),
        h('td', {}, p.from ? formatDate(p.from) : 'Before records began'),
        h('td', {}, p.to ? formatDate(p.to) : 'Now'))))
    : h('p', { class: 'empty' }, empty);
  return h('div', {},
    h('h3', {}, 'Managers'), periods(history.managers, 'No managers recorded.'),
    h('h3', { style: 'margin-top:1rem' }, 'Departments'), periods(history.departments, 'No departments recorded.'));
}

// ---- My leave ------------------------------------------------------------------------

async function leaveView() {
  const me = encodeURIComponent(state.me.employeeId);
  const year = new Date().getFullYear();
  const [balance, mine] = await Promise.all([
    get('/employees/' + me + '/leave-balance?year=' + year),
    get('/leave-requests/mine?size=100'),
  ]);

  const errors = h('div', {});
  const type = h('select', { name: 'type' }, ['ANNUAL', 'SICK', 'UNPAID', 'OTHER'].map(t => h('option', { value: t }, label(t))));
  const start = h('input', { type: 'date', required: true, value: today() });
  const end = h('input', { type: 'date', required: true, value: today() });
  const note = h('input', { maxlength: 500, placeholder: 'Optional, shown to your manager' });
  const submit = h('button', { class: 'primary', type: 'submit' }, 'Request leave');
  const form = h('form', {
    class: 'form',
    onsubmit: event => {
      event.preventDefault();
      act(submit, errors, async () => {
        await post('/leave-requests', { type: type.value, startDate: start.value, endDate: end.value, note: note.value || null });
        route();
      });
    },
  }, h('label', {}, 'Type', type), h('label', {}, 'First day', start), h('label', {}, 'Last day', end),
    h('label', { class: 'wide' }, 'Note', note), h('div', {}, submit));

  const rows = mine.content.slice().reverse().map(l => {
    const cancellable = l.status === 'PENDING' || (l.status === 'APPROVED' && l.startDate > today());
    const rowErrors = h('div', {});
    const cancel = cancellable ? h('button', { type: 'button', class: 'danger' }, 'Cancel') : null;
    cancel?.addEventListener('click', () => act(cancel, rowErrors, async () => {
      await post('/leave-requests/' + encodeURIComponent(l.id) + '/cancel');
      route();
    }));
    return h('tr', {},
      h('td', {}, label(l.type)), h('td', {}, dateRange(l.startDate, l.endDate)),
      h('td', { class: 'num' }, l.workingDays), h('td', {}, badge(l.status), l.reason ? h('div', { class: 'small muted' }, l.reason) : null),
      h('td', {}, cancel, rowErrors));
  });

  return h('div', {},
    h('h1', {}, 'My leave'),
    h('p', { class: 'subtitle' }, 'Annual leave for ' + year + ', in working days.'),
    h('div', { class: 'grid' },
      stat(balance.remainingDays, 'Left to request'), stat(balance.approvedDays, 'Approved'),
      stat(balance.pendingDays, 'Waiting for approval'), stat(balance.allowanceDays, 'Allowance')),
    h('h2', {}, 'Request leave'),
    h('div', { class: 'card' }, form, errors),
    h('h2', {}, 'My requests'),
    rows.length ? table(['Type', 'Dates', { label: 'Days', num: true }, 'Status', ''], rows) : h('p', { class: 'empty' }, 'No leave requested yet.'));
}

const stat = (value, text) => h('div', { class: 'card stat' }, h('div', { class: 'value' }, value), h('div', { class: 'label' }, text));

// ---- Approvals ------------------------------------------------------------------------

async function approvalsView() {
  const parts = [h('h1', {}, 'Approvals'),
    h('p', { class: 'subtitle' }, 'Leave from people below you in the org chart' + (isHr() ? ', and staffing requests for HR.' : '.'))];

  const leave = state.me.employeeId ? await get('/leave-requests/team?status=PENDING&size=100') : { content: [] };
  parts.push(h('h2', {}, 'Leave'));
  parts.push(leave.content.length
    ? h('div', {}, leave.content.map(l => decisionCard(
        [h('h3', {}, l.employee ? l.employee.name : 'Unknown'),
         h('p', {}, label(l.type) + ' leave · ' + dateRange(l.startDate, l.endDate) + ' · ' + l.workingDays + ' working days'),
         l.note ? h('p', { class: 'muted small' }, '“' + l.note + '”') : null],
        '/leave-requests/' + encodeURIComponent(l.id))))
    : h('p', { class: 'empty' }, 'No leave waiting for you.'));

  if (isHr()) {
    const staffing = await get('/staffing-requests?status=PENDING&size=100');
    parts.push(h('h2', {}, 'Staffing'));
    parts.push(staffing.content.length
      ? h('div', {}, staffing.content.map(r => decisionCard(
          [h('h3', {}, (r.action === 'REMOVE' ? 'Remove ' : 'Add ') + (r.employee ? r.employee.name : 'someone') + ' · ' + (r.project ? r.project.name : '')),
           h('p', {}, r.action === 'REMOVE' ? 'Remove from the project'
             : (r.role || 'No role') + ' · ' + r.allocationPercent + '%' + (r.startDate || r.endDate ? ' · ' + dateRange(r.startDate, r.endDate) : '')),
           h('p', { class: 'muted small' }, 'Requested by ' + r.requestedBy)],
          '/staffing-requests/' + encodeURIComponent(r.id))))
      : h('p', { class: 'empty' }, 'No staffing requests waiting.'));
  }
  return h('div', {}, parts);
}

function decisionCard(content, base) {
  const errors = h('div', {});
  const approve = h('button', { type: 'button', class: 'primary' }, 'Approve');
  const reason = h('input', { placeholder: 'Reason (needed to reject)', maxlength: 500, 'aria-label': 'Reason' });
  const reject = h('button', { type: 'button', class: 'danger' }, 'Reject');
  approve.addEventListener('click', () => act(approve, errors, async () => { await post(base + '/approve'); route(); }));
  reject.addEventListener('click', () => act(reject, errors, async () => {
    if (!reason.value.trim()) throw new ApiError(400, 'Give a reason to reject.');
    await post(base + '/reject', { reason: reason.value.trim() });
    route();
  }));
  return h('div', { class: 'card' }, content, h('div', { class: 'row', style: 'margin-top:0.6rem' }, approve, reason, reject), errors);
}

// ---- Team calendar ----------------------------------------------------------------------

async function calendarView(month) {
  const [y, m] = (month && /^\d{4}-\d{2}$/.test(month) ? month : today().slice(0, 7)).split('-').map(Number);
  const first = new Date(y, m - 1, 1);
  const days = new Date(y, m, 0).getDate();
  const from = isoDate(first);
  const to = isoDate(new Date(y, m - 1, days));
  const shift = n => isoDate(new Date(y, m - 1 + n, 1)).slice(0, 7);

  const range = '&from=' + from + '&to=' + to + '&size=100';
  let requests = [];
  if (isHr()) {
    requests = (await get('/leave-requests?' + range.slice(1))).content;
  } else if (state.me.employeeId) {
    const [mine, team] = await Promise.all([get('/leave-requests/mine?' + range.slice(1)), get('/leave-requests/team?' + range.slice(1))]);
    requests = [...mine.content, ...team.content];
  }
  requests = requests.filter(r => r.status === 'APPROVED' || r.status === 'PENDING');

  const people = new Map();
  for (const r of requests) {
    const key = r.employee ? r.employee.id : '?';
    if (!people.has(key)) people.set(key, { name: r.employee ? r.employee.name : 'Unknown', leave: [] });
    people.get(key).leave.push(r);
  }

  const dayDates = Array.from({ length: days }, (_, i) => isoDate(new Date(y, m - 1, i + 1)));
  const isWeekend = iso => { const d = new Date(iso + 'T00:00:00').getDay(); return d === 0 || d === 6; };
  const now = today();

  const header = h('tr', {}, h('th', { class: 'person' }, 'Person'),
    dayDates.map(d => h('th', { class: (isWeekend(d) ? 'weekend' : '') + (d === now ? ' today' : '') }, String(Number(d.slice(8))))));
  const rows = [...people.values()].sort((a, b) => a.name.localeCompare(b.name)).map(person =>
    h('tr', {}, h('td', { class: 'person' }, person.name), dayDates.map(d => {
      const leave = person.leave.find(l => l.startDate <= d && l.endDate >= d);
      const classes = [isWeekend(d) ? 'weekend' : '', leave ? leave.status.toLowerCase() : '', d === now ? 'today' : ''].join(' ').trim();
      return h('td', { class: classes || null, title: leave ? person.name + ': ' + label(leave.type) + ' leave (' + label(leave.status) + ')' : null });
    })));

  const title = first.toLocaleDateString(undefined, { month: 'long', year: 'numeric' });
  return h('div', {},
    h('div', { class: 'spread' },
      h('div', {}, h('h1', {}, 'Team calendar'), h('p', { class: 'subtitle' }, isHr() ? "Everyone's leave." : 'Your leave and your team’s.')),
      h('div', { class: 'row' },
        h('a', { href: '#/calendar/' + shift(-1) }, '‹ Previous'), h('strong', {}, title), h('a', { href: '#/calendar/' + shift(1) }, 'Next ›'))),
    h('div', { class: 'legend' }, h('span', { class: 'approved' }, 'Approved'), h('span', { class: 'pending' }, 'Waiting for approval')),
    rows.length
      ? h('div', { class: 'table-wrap' }, h('table', { class: 'calendar' }, h('thead', {}, header), h('tbody', {}, rows)))
      : h('p', { class: 'empty' }, 'Nobody is away in ' + title + '.'));
}

// ---- Analytics ----------------------------------------------------------------------------

async function analyticsView() {
  const [overview, managers, risks, gaps] = await Promise.all([
    get('/analytics/overview'), get('/analytics/managers?size=20'),
    get('/analytics/project-skill-risks'), get('/analytics/skill-gaps'),
  ]);

  return h('div', {},
    h('h1', {}, 'Analytics'),
    h('p', { class: 'subtitle' }, 'The shape of the organisation, counting current employees.'),
    h('div', { class: 'grid' },
      stat(overview.headcount, 'People'), stat(overview.managers, 'Managers'),
      stat(overview.averageSpan, 'Average direct reports'), stat(overview.orgDepth, 'Levels'),
      stat(overview.onLeave, 'On leave'), stat(overview.withoutDepartment, 'Without a department')),

    h('h2', {}, 'Span of control'),
    managers.content.length
      ? table(['Manager', { label: 'Direct', num: true }, { label: 'Total', num: true }, { label: 'Levels below', num: true }, ''],
          managers.content.map(m => h('tr', {}, h('td', {}, personLink(m.manager)), h('td', { class: 'num' }, m.directReports),
            h('td', { class: 'num' }, m.totalReports), h('td', { class: 'num' }, m.layersBelow),
            h('td', {}, m.flag ? badge(m.flag === 'WIDE' ? 'Wide' : 'Narrow', 'warn') : null))))
      : h('p', { class: 'empty' }, 'Nobody has reports yet.'),

    h('h2', {}, 'Skills at risk on projects'),
    h('p', { class: 'muted small' }, 'Required skills that one person, or nobody, on the project has at the required level.'),
    risks.length
      ? h('div', {}, risks.map(r => h('div', { class: 'card' },
          h('h3', {}, r.project.name), h('div', { class: 'row' }, r.skills.filter(s => s.atRisk).map(s =>
            h('span', { class: 'badge ' + (s.coveredBy.length ? 'warn' : 'bad') },
              s.skill + ' ' + s.requiredLevel + '+ · ' + (s.coveredBy.length ? 'only ' + s.coveredBy[0].name : 'nobody')))))))
      : h('p', { class: 'empty' }, 'No project depends on a single person for a skill.'),

    h('h2', {}, 'Skill gaps by department'),
    h('p', { class: 'muted small' }, 'Skills the department’s open projects need, and how many active people in it have them.'),
    gaps.length
      ? h('div', {}, gaps.map(g => h('div', { class: 'card' },
          h('h3', {}, g.department.name),
          table(['Skill', { label: 'Level', num: true }, { label: 'Projects', num: true }, { label: 'Qualified people', num: true }, ''],
            g.skills.map(s => h('tr', {}, h('td', {}, s.skill), h('td', { class: 'num' }, s.requiredLevel),
              h('td', { class: 'num' }, s.projectsNeedingIt), h('td', { class: 'num' }, s.qualifiedPeople),
              h('td', {}, s.shortage ? badge('Short', 'bad') : null)))))))
      : h('p', { class: 'empty' }, 'No department has open projects with required skills.'));
}

// ---- Assistant ------------------------------------------------------------------------------

async function assistantView() {
  const log = h('div', { class: 'chat', 'aria-live': 'polite' });
  const drawLog = () => log.replaceChildren(...state.chat.map(m => h('div', { class: 'bubble ' + m.from }, m.text)));
  drawLog();

  const errors = h('div', {});
  const question = h('textarea', { rows: 2, maxlength: 1000, placeholder: 'e.g. Who in my team is away next week?', 'aria-label': 'Question' });
  const ask = h('button', { class: 'primary', type: 'submit' }, 'Ask');
  const form = h('form', {
    onsubmit: event => {
      event.preventDefault();
      const text = question.value.trim();
      if (!text) return;
      act(ask, errors, async () => {
        state.chat.push({ from: 'me', text });
        drawLog();
        question.value = '';
        const reply = await post('/ai', { question: text, conversationId: state.conversationId });
        state.conversationId = reply.conversationId;
        state.chat.push({ from: 'bot', text: reply.answer });
        drawLog();
        ask.disabled = false;
        question.focus();
      });
    },
  }, h('label', {}, 'Ask about people, teams, projects, skills or leave', question), h('div', { class: 'row' }, ask,
    h('button', { type: 'button', class: 'link', onclick: () => { state.chat = []; state.conversationId = null; drawLog(); } }, 'New conversation')));

  return h('div', {}, h('h1', {}, 'Assistant'),
    h('p', { class: 'subtitle' }, 'Answers come from the directory. The assistant can look things up but never changes anything.'),
    log, h('div', { class: 'card' }, form, errors));
}

// ---- Start ----------------------------------------------------------------------------------

(async function start() {
  try {
    state.token = sessionStorage.getItem(TOKEN_KEY);
    state.refreshToken = sessionStorage.getItem(REFRESH_KEY);
  } catch { /* storage unavailable */ }
  if (state.token) {
    try {
      await startSession();
    } catch {
      state.token = null;
      state.refreshToken = null;
      state.me = null;
    }
  }
  route();
})();
