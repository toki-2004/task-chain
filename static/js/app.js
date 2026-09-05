/* 协同任务链 - 前端 SPA */
"use strict";

/* ================= API ================= */
async function api(method, url, body, isForm) {
  const opts = { method, credentials: "same-origin", headers: {} };
  if (body !== undefined && !isForm) {
    opts.headers["Content-Type"] = "application/json";
    opts.body = JSON.stringify(body);
  } else if (isForm) {
    opts.body = body; // FormData
  }
  const resp = await fetch(url, opts);
  if (resp.status === 401 && !url.includes("/api/login")) {
    ME = null;
    const h = location.hash || "#/home";
    // 登录/注册页本身或 render 的会话探测不应触发跳转
    if (!url.includes("/api/me") && h !== "#/login" && h !== "#/register") {
      location.hash = "#/login";
    }
    throw new Error("未登录");
  }
  let data = null;
  try { data = await resp.json(); } catch (e) { data = {}; }
  if (!resp.ok) throw new Error(data.detail || `请求失败(${resp.status})`);
  return data;
}
const GET = (u) => api("GET", u);
const POST = (u, b) => api("POST", u, b);

/* ================= 状态 ================= */
let ME = null;
let homeTab = "unfinished";

/* ================= 工具 ================= */
function esc(s) {
  return String(s == null ? "" : s)
    .replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;").replace(/'/g, "&#39;");
}
function toast(msg, isErr) {
  const root = document.getElementById("toast-root");
  const t = document.createElement("div");
  t.className = "toast" + (isErr ? " err" : "");
  t.textContent = msg;
  root.appendChild(t);
  setTimeout(() => t.remove(), 2400);
}
function fmtDT(s) { return (s || "").replace("T", " ").slice(0, 16); }

function deadlineInfo(dl) {
  if (!dl) return { text: "无截止时间", cls: "" };
  const d = new Date(dl.replace(" ", "T"));
  const now = new Date();
  const diff = d - now;
  const days = Math.floor(diff / 86400000);
  const hours = Math.floor(diff / 3600000);
  if (diff < 0) return { text: "已超期 " + (-days ? (-days + " 天 ") : "") + Math.floor(-diff / 3600000) + " 小时", cls: "dl-over" };
  if (days >= 1) return { text: "剩 " + days + " 天", cls: hours < 48 ? "dl-near" : "" };
  if (hours >= 1) return { text: "剩 " + hours + " 小时", cls: "dl-near" };
  return { text: "剩 " + Math.max(1, Math.floor(diff / 60000)) + " 分钟", cls: "dl-near" };
}

const STATUS_MAP = {
  in_progress: { t: "进行中", c: "blue" },
  pending_review: { t: "待审核", c: "orange" },
  approved: { t: "已完成", c: "green" },
  rejected: { t: "被驳回", c: "red" },
};
function statusChip(st, extra) {
  const m = STATUS_MAP[st] || { t: st, c: "grey" };
  return `<span class="chip ${m.c}">${esc(extra || m.t)}</span>`;
}

/* 弹窗：modal({title, body, okText, okClass, onOk}) 返回关闭函数 */
function modal({ title, body, okText, okClass, onOk, showCancel = true }) {
  const root = document.getElementById("modal-root");
  const mask = document.createElement("div");
  mask.className = "modal-mask";
  mask.innerHTML = `<div class="modal">
    <div class="m-title">${esc(title)}</div>
    <div class="m-body">${body}</div>
    <div class="m-foot">
      ${showCancel ? '<button class="btn plain m-cancel">取消</button>' : ""}
      <button class="btn ${okClass || ""} m-ok">${esc(okText || "确定")}</button>
    </div></div>`;
  const close = () => mask.remove();
  const cancelBtn = mask.querySelector(".m-cancel");
  if (cancelBtn) cancelBtn.onclick = close;
  mask.addEventListener("click", (e) => { if (e.target === mask) close(); });
  mask.querySelector(".m-ok").onclick = async () => {
    try {
      if (onOk) await onOk(mask, close);
      else close();
    } catch (e) { toast(e.message, true); }
  };
  root.appendChild(mask);
  return { close, mask };
}
function confirmModal(title, msg, okText, okClass, onOk) {
  modal({
    title, okText, okClass,
    body: `<div style="font-size:14.5px;white-space:pre-wrap">${esc(msg)}</div>`,
    onOk: async (mask, close) => { await onOk(); close(); },
  });
}

/* 文件展示（图片网格/视频） */
function fileGrid(files) {
  if (!files || !files.length) return "";
  const items = files.map((f) => {
    if ((f.mime || "").startsWith("image") || /\.(jpe?g|png|gif|webp|bmp)$/i.test(f.name || "")) {
      return `<img src="/files/${f.file_id}" data-fid="${f.file_id}" data-mime="${esc(f.mime || "")}" data-name="${esc(f.name || "")}" class="preview-file">`;
    }
    return `<video src="/files/${f.file_id}" data-fid="${f.file_id}" data-mime="${esc(f.mime || "")}" data-name="${esc(f.name || "")}" class="preview-file" controls preload="metadata"></video>`;
  }).join("");
  return `<div class="file-grid">${items}</div>`;
}
function bindViewer(root) {
  root.querySelectorAll("img.preview-file").forEach((el) => {
    el.onclick = () => {
      const v = document.createElement("div");
      v.className = "viewer";
      v.innerHTML = `<span class="close">✕</span><img src="${el.src}">`;
      v.onclick = () => v.remove();
      document.body.appendChild(v);
    };
  });
}

/* 底部导航 */
function tabBar(active) {
  const bU = ME && ME.badges ? ME.badges.unfinished + ME.badges.pending_review + (ME.badges.feedback || 0) : 0;
  return `<div class="tabbar">
    <div class="tab ${active === "home" ? "active" : ""}" data-go="#/home">
      <div class="ico">🏠<span class="badge" style="display:${bU ? "inline" : "none"}">${bU}</span></div>首页
    </div>
    <div class="tab ${active === "devices" ? "active" : ""}" data-go="#/devices"><div class="ico">📱</div>设备</div>
    <div class="tab ${active === "me" ? "active" : ""}" data-go="#/me"><div class="ico">👤</div>我的</div>
  </div>`;
}
function bindTabBar(root) {
  root.querySelectorAll(".tabbar .tab").forEach((el) => {
    el.onclick = () => { location.hash = el.dataset.go; };
  });
}

function taskCard(t, opts = {}) {
  const di = deadlineInfo(t.deadline);
  const sub = t.creator_id === (ME && ME.user.id) && opts.showSubmitter
    ? `<span>受任人：${esc(t.assignee_name)}</span>` : "";
  const chainTag = t.seq > 1 || opts.alwaysChain ? `<span class="chip dark">节点 ${t.seq}</span>` : "";
  const terminated = t.chain_status === "terminated" ? '<span class="chip grey">链已结束</span>' : "";
  return `<div class="card task-card" data-node="${t.id}">
    <div style="display:flex;gap:6px;align-items:center;flex-wrap:wrap">
      ${chainTag}${statusChip(t.status, t.status === "approved" && !opts.keepStatus ? undefined : undefined)}
      ${terminated}
      <span class="chip">${esc(t.chain_title || t.title)}</span>
    </div>
    <div class="card-title" style="margin-top:6px">${esc(t.title)}</div>
    <div class="meta">
      <span>受任人：${esc(t.assignee_name)}</span>
      ${sub}
      <span class="${di.cls}">⏰ ${di.text}</span>
      ${t.deadline ? `<span>${fmtDT(t.deadline)}</span>` : ""}
    </div>
  </div>`;
}

async function refreshMe() {
  try { ME = await GET("/api/me"); } catch (e) { /* ignore */ }
}

/* ================= 登录页 ================= */
function renderLogin(app) {
  app.innerHTML = `<div class="login-wrap">
    <div class="login-logo">🔗</div>
    <div class="login-title">协同任务链</div>
    <div class="login-sub">任务发布 · 协同流转 · 全程留痕</div>
    <div class="form-item"><input id="lg-user" placeholder="账号" autocapitalize="off"></div>
    <div class="form-item"><input id="lg-pass" type="password" placeholder="密码"></div>
    <button class="btn block" id="lg-btn">登 录</button>
    <div style="text-align:center;margin-top:14px;font-size:13.5px">
      没有账号？<a href="#/register">注册新账号</a>
    </div>
    <div style="text-align:center;color:var(--grey);font-size:12px;margin-top:18px">服务器：${esc(location.host)}</div>
  </div>`;
  const doLogin = async () => {
    const u = document.getElementById("lg-user").value.trim();
    const p = document.getElementById("lg-pass").value;
    if (!u || !p) return toast("请输入账号和密码", true);
    try {
      await api("POST", "/api/login", { username: u, password: p });
      ME = await GET("/api/me");
      toast("欢迎，" + ME.user.name);
      location.hash = "#/home";
    } catch (e) { toast(e.message, true); }
  };
  document.getElementById("lg-btn").onclick = doLogin;
  app.addEventListener("keydown", (e) => { if (e.key === "Enter") doLogin(); });
}

/* ================= 注册页 ================= */
function renderRegister(app) {
  app.innerHTML = `<div class="login-wrap">
    <div class="login-logo">📝</div>
    <div class="login-title">注册新账号</div>
    <div class="login-sub">注册后即可登录使用</div>
    <div class="form-item"><input id="rg-user" placeholder="账号（2-32 位字母/数字/下划线）" autocapitalize="off"></div>
    <div class="form-item"><input id="rg-name" placeholder="姓名（任务中显示）"></div>
    <div class="form-item"><input id="rg-pass" type="password" placeholder="密码（至少 8 位）"></div>
    <div class="form-item"><input id="rg-pass2" type="password" placeholder="确认密码"></div>
    <button class="btn block" id="rg-btn">注 册</button>
    <div style="text-align:center;margin-top:14px;font-size:13.5px">
      已有账号？<a href="#/login">去登录</a>
    </div>
    <div style="text-align:center;color:var(--grey);font-size:12px;margin-top:18px">服务器：${esc(location.host)}</div>
  </div>`;
  const doRegister = async () => {
    const u = document.getElementById("rg-user").value.trim();
    const n = document.getElementById("rg-name").value.trim();
    const p1 = document.getElementById("rg-pass").value;
    const p2 = document.getElementById("rg-pass2").value;
    if (!u || !n || !p1) return toast("请填写完整", true);
    if (p1 !== p2) return toast("两次输入的密码不一致", true);
    try {
      await api("POST", "/api/register", { username: u, name: n, password: p1 });
      ME = await GET("/api/me");
      toast("注册成功，欢迎，" + ME.user.name);
      location.hash = "#/home";
    } catch (e) { toast(e.message, true); }
  };
  document.getElementById("rg-btn").onclick = doRegister;
  app.addEventListener("keydown", (e) => { if (e.key === "Enter") doRegister(); });
}

/* ================= 首页 ================= */
async function renderHome(app) {
  const tabs = [
    ["unfinished", "未完成"],
    ["pending", "待审核"],
    ["done", "已完成"],
  ];
  app.innerHTML = `<div class="topbar">协同任务链<div class="spacer"></div>
      <span class="action" data-go="#/create">＋ 发布任务</span></div>
    <div class="page">
      <div class="seg" id="home-seg"></div>
      <div id="home-list"><div class="empty">加载中…</div></div>
    </div>
    <button class="fab" id="fab-add">＋</button>
    ${tabBar("home")}`;
  bindTabBar(app);
  app.querySelector("#fab-add").onclick = () => { location.hash = "#/create"; };
  app.querySelector("[data-go='#/create']").onclick = () => { location.hash = "#/create"; };

  const seg = app.querySelector("#home-seg");
  const listEl = app.querySelector("#home-list");
  const cache = { unfinished: [], pending: [], done: [], terminations: [], feedback: [] };
  const counts = () => ({
    unfinished: cache.unfinished.length,
    pending: cache.pending.length + cache.terminations.length + cache.feedback.length,
    done: cache.done.length,
  });

  const drawSeg = () => {
    const ct = counts();
    seg.innerHTML = tabs.map(([k, label]) => `<div class="seg-item ${homeTab === k ? "active" : ""}" data-tab="${k}">${label}${ct[k] ? `<span class="count">${ct[k]}</span>` : ""}</div>`).join("");
    seg.querySelectorAll(".seg-item").forEach((el) => {
      el.onclick = () => { homeTab = el.dataset.tab; drawSeg(); drawList(); };
    });
  };

  const drawList = () => {
    let html = "";
    if (homeTab === "pending" && cache.feedback.length) {
      html += `<div class="section-title">反馈与申诉（待我回复）</div>` + cache.feedback.map((f) => `
        <div class="card task-card" data-node="${f.node_id}">
          <div style="display:flex;gap:6px;align-items:center;flex-wrap:wrap">
            <span class="chip ${f.kind === "appeal" ? "orange" : "blue"}">${f.kind === "appeal" ? "申诉·待处理" : "反馈·待回复"}</span>
            <span class="chip">${esc(f.chain_title)}</span>
          </div>
          <div class="card-title" style="margin-top:6px">${esc(f.node_title)}</div>
          <div class="meta"><span>${esc(f.sender)}</span><span>${fmtDT(f.created_at)}</span></div>
          <div style="font-size:13px;color:var(--muted);margin-top:4px">${esc(f.text.slice(0, 50))}${f.text.length > 50 ? "…" : ""}</div>
        </div>`).join("");
    }
    if (homeTab === "pending" && cache.terminations.length) {
      html += `<div class="section-title">结束申请（待我审核）</div>` + cache.terminations.map((t) => `
        <div class="card">
          <div style="display:flex;gap:6px;align-items:center"><span class="chip red">结束申请</span><span class="chip">${esc(t.chain_title)}</span></div>
          <div class="card-title" style="margin-top:6px">${esc(t.chain_title)}</div>
          <div class="meta"><span>申请人：${esc(t.applicant_name)}</span><span>${fmtDT(t.created_at)}</span></div>
          ${t.reason ? `<div style="font-size:13px;color:var(--muted);margin-top:4px">理由：${esc(t.reason)}</div>` : ""}
          <div class="btn-row">
            <button class="btn green small" data-term="${t.chain_id}" data-approve="1">同意结束</button>
            <button class="btn plain small" data-term="${t.chain_id}" data-approve="0">拒绝</button>
          </div>
        </div>`).join("");
    }
    const label = homeTab === "pending" && (cache.feedback.length || cache.terminations.length) ? "待审核任务" : "";
    if (label) html += `<div class="section-title">${label}</div>`;
    if (cache[homeTab] && cache[homeTab].length) {
      html += cache[homeTab].map((t) => taskCard(t, { showSubmitter: true })).join("");
    } else if (!(homeTab === "pending" && (cache.feedback.length || cache.terminations.length))) {
      const emptyText = { unfinished: "暂无未完成任务", pending: "暂无待审核内容", done: "暂无已完成任务" }[homeTab];
      html += `<div class="empty">${emptyText}</div>`;
    }
    listEl.innerHTML = html;
    bindViewer(listEl);
    listEl.querySelectorAll(".task-card").forEach((el) => {
      el.onclick = () => { location.hash = "#/node/" + el.dataset.node; };
    });
    listEl.querySelectorAll("[data-term]").forEach((el) => {
      el.onclick = (ev) => {
        ev.stopPropagation();
        decideTerminate(el.dataset.term, el.dataset.approve === "1", refresh);
      };
    });
  };

  async function refresh() {
    const rsq = renderSeqNow();
    try {
      const [u, p, d] = await Promise.all([
        GET("/api/tasks?bucket=unfinished"),
        GET("/api/tasks?bucket=pending"),
        GET("/api/tasks?bucket=done"),
      ]);
      if (renderSeqNow() !== rsq) return;
      cache.unfinished = u.tasks; cache.done = d.tasks;
      cache.pending = p.tasks; cache.terminations = p.terminations || []; cache.feedback = p.feedback || [];
      await refreshMe();
      if (renderSeqNow() !== rsq) return;
      updateTabBadge();
    } catch (e) { /* 静默 */ }
    if (renderSeqNow() !== rsq) return;
    drawSeg();
    drawList();
  }
  await refresh();
}

async function decideTerminate(chainId, approve, after) {
  const doIt = async (comment) => {
    try {
      await POST(`/api/chains/${chainId}/terminate/review`, { approve, comment: comment || "" });
      toast(approve ? "已同意结束，任务链结束" : "已拒绝结束申请");
      await refreshMe(); after && after();
    } catch (e) { toast(e.message, true); }
  };
  if (approve) {
    confirmModal("同意结束", "结束后整个任务链将立即结束，且不可恢复。确定同意结束？", "同意结束", "danger", () => doIt(""));
  } else {
    modal({
      title: "拒绝结束申请", okText: "拒绝", okClass: "danger",
      body: `<div class="form-item"><textarea id="term-comment" placeholder="拒绝原因（选填）"></textarea></div>`,
      onOk: async (mask, close) => { await doIt(mask.querySelector("#term-comment").value); close(); },
    });
  }
}

/* ================= 发布任务 / 创建下一节点 ================= */
async function renderCreate(app) {
  const nextOf = new URLSearchParams(location.hash.split("?")[1] || "").get("next");
  let chainCtx = null;
  if (nextOf) {
    try {
      const d = await GET("/api/nodes/" + nextOf);
      chainCtx = { prevNode: d.node, chain: d.chain };
    } catch (e) { toast(e.message, true); location.hash = "#/home"; return; }
  }

  const prereqs = []; // {type, ref_node_id|device_id, label, sub, penalty_text, penalty_url}
  let attachments = []; // {id,name}
  let users = [], devices = [], pickNodes = [];
  const crsq = renderSeqNow();
  try {
    [users, devices, pickNodes] = await Promise.all([GET("/api/users"), GET("/api/devices"), GET("/api/pick/nodes")]);
  } catch (e) { toast(e.message, true); }
  if (renderSeqNow() !== crsq) return;
  const freeDevices = devices;

  app.innerHTML = `<div class="topbar"><span class="back" data-back>‹</span>${chainCtx ? "创建下一节点" : "发布新任务"}<div class="spacer"></div></div>
  <div class="page">
    ${chainCtx ? `<div class="banner">正在为「${esc(chainCtx.chain.title)}」创建第 ${chainCtx.prevNode.seq + 1} 个节点。上一节点：${esc(chainCtx.prevNode.title)}（已完成）</div>` : ""}
    <div class="card">
      <div class="form-item"><label>主题 <span class="req">*</span></label><input id="f-title" placeholder="任务主题"></div>
      <div class="form-item"><label>任务内容</label><textarea id="f-content" placeholder="详细描述任务内容（选填）"></textarea></div>
      <div class="form-item"><label>任务完成条件</label><textarea id="f-criteria" placeholder="怎样算完成？如：验收标准、交付物要求（选填）"></textarea></div>
      <div class="form-item"><label>截止时间</label><input id="f-deadline" type="datetime-local"></div>
      <div class="form-item"><label>受任人 <span class="req">*</span></label><select id="f-assignee">
        <option value="">请选择受任人</option>
        ${users.filter((u) => u.active).map((u) => `<option value="${u.id}">${esc(u.name)}（${esc(u.username)}）</option>`).join("")}
      </select></div>
      <div class="form-item"><label>图片 / 视频附件</label>
        <input type="file" id="f-files" multiple accept="image/*,video/*" style="padding:8px">
        <div id="f-filelist" class="file-grid"></div>
      </div>
    </div>
    <div class="card">
      <div style="display:flex;align-items:center"><b style="font-size:14.5px">前置要求</b>
        <span style="flex:1;color:var(--muted);font-size:12px;margin-left:8px">满足全部前置后才能提交本任务</span></div>
      <div id="f-prereqs"></div>
      <div class="btn-row">
        <button class="btn plain" id="add-task-pre">＋ 前置任务</button>
        <button class="btn plain" id="add-dev-pre">＋ 前置设备</button>
      </div>
    </div>
    <button class="btn block" id="f-submit">${chainCtx ? "创建节点" : "发布任务"}</button>
  </div>${tabBar("")}`;
  bindTabBar(app);
  app.querySelector("[data-back]").onclick = () => history.back();

  const drawPrereqs = () => {
    const box = app.querySelector("#f-prereqs");
    if (!prereqs.length) { box.innerHTML = `<div style="color:var(--grey);font-size:13px;padding:8px 0">暂无前置要求（可留空）</div>`; return; }
    box.innerHTML = prereqs.map((p, i) => `<div class="prereq-item">
      <div class="body">
        <div class="name">${p.type === "task" ? "📋 前置任务" : "📱 前置设备"}：${esc(p.label)}</div>
        <div class="desc">${esc(p.sub || "")}</div>
        ${p.penalty_text || p.penalty_url ? `<div class="penalty">⚠ ${esc(p.penalty_text || "未按要求处理将按规定惩罚")}${p.penalty_url ? ` <a href="${esc(p.penalty_url)}" target="_blank">查看条例↗</a>` : ""}</div>` : ""}
      </div>
      <button class="btn plain small" data-rm="${i}">移除</button>
    </div>`).join("");
    box.querySelectorAll("[data-rm]").forEach((el) => {
      el.onclick = () => { prereqs.splice(+el.dataset.rm, 1); drawPrereqs(); };
    });
  };
  drawPrereqs();

  app.querySelector("#add-task-pre").onclick = () => {
    const others = pickNodes.filter((n) => !chainCtx || n.chain_id !== chainCtx.chain.id);
    if (!others.length) return toast("暂无可选的前置任务（你参与过的任务链中暂无其他任务）", true);
    modal({
      title: "选择前置任务", okText: "关闭", showCancel: false,
      body: `<input id="pick-search" placeholder="🔍 搜索：任务名 / 链名 / 受任人" style="margin-bottom:8px" autofocus>
             <div id="pick-list"></div>`,
      onOk: async (mask, close) => close(),
    });
    const drawPickList = (kw) => {
      const k = (kw || "").trim().toLowerCase();
      const list = others.filter((n) => !k || [n.chain_title, n.title, n.assignee_name]
        .some((v) => (v || "").toLowerCase().includes(k)));
      const box = document.getElementById("pick-list");
      box.innerHTML = list.length ? list.map((n) => `<div class="pick-item" data-pick="${n.id}">
        <div class="t">${esc(n.chain_title)} · 节点${n.seq}：${esc(n.title)}</div>
        <div class="s">受任人：${esc(n.assignee_name)} · 状态：${STATUS_MAP[n.status] ? STATUS_MAP[n.status].t : n.status}</div>
      </div>`).join("") : `<div style="color:var(--grey);font-size:13px;padding:10px 0">无匹配任务</div>`;
      box.querySelectorAll(".pick-item").forEach((el) => {
        el.onclick = () => {
          const n = others.find((x) => x.id === +el.dataset.pick);
          prereqs.push({ type: "task", ref_node_id: n.id, label: `${n.chain_title} · 节点${n.seq}`, sub: n.title, penalty_text: "", penalty_url: "" });
          drawPrereqs();
          document.querySelector(".modal-mask").remove();
        };
      });
    };
    drawPickList("");
    document.getElementById("pick-search").oninput = (e) => drawPickList(e.target.value);
  };

  app.querySelector("#add-dev-pre").onclick = () => {
    if (!freeDevices.length) return toast("管理后台还没有注册设备", true);
    modal({
      title: "选择前置设备", okText: "下一步",
      body: `<select id="dev-sel">${freeDevices.map((d) => `<option value="${d.id}">${esc(d.name)}${d.code ? "（" + esc(d.code) + "）" : ""}</option>`).join("")}</select>
        <div class="form-item" style="margin-top:10px"><label>未按要求处理的惩罚/处理办法</label><textarea id="dev-penalty" placeholder="如：按合同第 X 条赔偿…（选填）"></textarea></div>
        <div class="form-item"><label>条例/合同超链接</label><input id="dev-url" placeholder="https://…（选填）"></div>`,
      onOk: (mask, close) => {
        const d = freeDevices.find((x) => x.id === +mask.querySelector("#dev-sel").value);
        prereqs.push({
          type: "device", device_id: d.id, label: d.name,
          sub: d.code || d.description || "",
          penalty_text: mask.querySelector("#dev-penalty").value.trim(),
          penalty_url: mask.querySelector("#dev-url").value.trim(),
        });
        drawPrereqs(); close();
      },
    });
  };

  /* 附件：选择后立即上传 */
  app.querySelector("#f-files").onchange = async (e) => {
    const files = [...e.target.files];
    for (const f of files) {
      try {
        const fd = new FormData();
        fd.append("file", f);
        const r = await api("POST", "/api/files", fd, true);
        attachments.push(r);
        app.querySelector("#f-filelist").innerHTML = attachments
          .map((a, i) => `<div style="position:relative"><span class="chip dark">${esc(a.name)}</span>
            <span data-rmf="${i}" style="color:var(--red);cursor:pointer;font-size:12px">✕</span></div>`).join("");
        app.querySelectorAll("[data-rmf]").forEach((el) => {
          el.onclick = () => { attachments.splice(+el.dataset.rmf, 1); el.parentElement.remove(); };
        });
      } catch (err) { toast(f.name + "：" + err.message, true); }
    }
    e.target.value = "";
  };

  app.querySelector("#f-submit").onclick = async (e) => {
    const btn = e.target;
    const body = {
      title: app.querySelector("#f-title").value,
      content: app.querySelector("#f-content").value,
      criteria: app.querySelector("#f-criteria").value,
      deadline: app.querySelector("#f-deadline").value,
      assignee_id: +app.querySelector("#f-assignee").value || 0,
      attachments: attachments.map((a) => a.id),
      prereqs: prereqs.map((p) => ({ type: p.type, ref_node_id: p.ref_node_id, device_id: p.device_id, penalty_text: p.penalty_text, penalty_url: p.penalty_url })),
    };
    try {
      btn.disabled = true; btn.textContent = "提交中…";
      const r = chainCtx ? await POST(`/api/nodes/${chainCtx.prevNode.id}/next`, body) : await POST("/api/tasks", body);
      toast(chainCtx ? "节点已创建" : "任务已发布");
      location.hash = "#/node/" + r.node_id;
    } catch (err) {
      toast(err.message, true);
      btn.disabled = false; btn.textContent = chainCtx ? "创建节点" : "发布任务";
    }
  };
}

/* ================= 任务详情 ================= */
const EVENT_LABEL = {
  chain_create: "创建任务", node_create: "创建节点", submit: "提交完成",
  review_approve: "审核通过", review_reject: "审核驳回", feedback: "反馈",
  appeal: "申诉", reply: "回复", appeal_resolve: "申诉处理",
  terminate_apply: "申请结束", terminate_approve: "同意结束", terminate_reject: "拒绝结束",
  terminate_direct: "结束任务", device_checkout: "领用设备", device_return: "归还设备", device_release: "强制释放设备",
  task_edit: "修改任务", submission_edit: "修改提交",
};

async function renderNode(app, nodeId) {
  const rsq = renderSeqNow();
  app.innerHTML = `<div class="topbar"><span class="back" data-back>‹</span>任务详情</div><div class="page"><div class="empty">加载中…</div></div>${tabBar("")}`;
  bindTabBar(app);
  app.querySelector("[data-back]").onclick = () => { if (history.length > 1) history.back(); else location.hash = "#/home"; };
  let d;
  try { d = await GET("/api/nodes/" + nodeId); } catch (e) {
    app.querySelector(".page").innerHTML = `<div class="empty">${esc(e.message)}</div>`; return;
  }
  if (renderSeqNow() !== rsq) return;
  const { chain, nodes, node, prereqs, attachments, submissions, messages, events, perms, termination } = d;
  const di = deadlineInfo(node.deadline);
  const page = app.querySelector(".page");

  /* ---- 头部 ---- */
  let html = `<div class="card">
    <div style="display:flex;gap:6px;align-items:center;flex-wrap:wrap">
      <span class="chip dark">节点 ${node.seq}</span>${statusChip(node.status)}
      ${chain.status === "terminated" ? '<span class="chip grey">链已结束</span>' : ""}
    </div>
    <div class="card-title" style="font-size:17px;margin-top:6px">${esc(node.title)}</div>
    <div class="meta">
      <span>受任人：${esc((nodes.find((n) => n.id === node.id) || {}).assignee_name || "?")}</span>
      <span class="${di.cls}">⏰ ${di.text}${node.deadline ? "（" + fmtDT(node.deadline) + "）" : ""}</span>
    </div>
    ${node.criteria ? `<div style="margin-top:8px;font-size:13.5px;background:#f0f6ff;border-radius:8px;padding:8px 10px">✅ 完成条件：${esc(node.criteria)}</div>` : ""}
    ${fileGrid(attachments)}
  </div>`;

  /* ---- 结束状态横幅 ---- */
  if (termination && perms.can_decide_terminate) {
    html += `<div class="banner red"><b>结束申请</b>：${esc(termination.applicant_name)} 申请结束本任务链${termination.reason ? "，理由：" + esc(termination.reason) : ""}
      <div class="btn-row"><button class="btn green small" id="term-ok">同意结束</button><button class="btn plain small" id="term-no">拒绝</button></div></div>`;
  } else if (termination) {
    html += `<div class="banner">⏳ ${esc(termination.applicant_name)} 已发起结束申请，待链发起人审核</div>`;
  }
  if (chain.status === "terminated") {
    html += `<div class="banner">🚫 任务链已于 ${esc(chain.terminated_at)} 结束${chain.terminate_reason ? "，原因：" + esc(chain.terminate_reason) : ""}</div>`;
  }
  if (node.status === "rejected") {
    const lastReject = [...events].reverse().find((e) => e.type === "review_reject");
    html += `<div class="banner red">❌ 上次提交被驳回${lastReject && JSON.parse(lastReject.detail || "{}").comment ? "：" + esc(JSON.parse(lastReject.detail).comment) : ""}，可修改后重新提交</div>`;
  }

  /* ---- 我可以做什么 ---- */
  html += `<div class="card"><div class="section-title" style="margin:0 0 4px">我的操作</div>`;
  let hasAction = false;
  if (perms.can_submit) {
    hasAction = true;
    html += `<button class="btn block" id="btn-submit">提交任务完成</button>
      <div style="font-size:12px;color:var(--muted);text-align:center;margin-top:6px">提交后由发布者审核</div>`;
  } else if (node.assignee_id === ME.user.id && node.status === "in_progress" && chain.status === "active" && !perms.can_submit) {
    hasAction = true;
    const blockers = prereqs.filter((p) => (p.type === "task" && (!p.ref || p.ref.status !== "approved")) || (p.type === "device" && !p.device_mine));
    html += `<button class="btn block" disabled>提交任务完成（前置未满足）</button>
      <div style="font-size:12px;color:var(--red);text-align:center;margin-top:6px">还差 ${blockers.length} 项前置要求未满足</div>`;
  }
  if (perms.can_review) {
    hasAction = true;
    html += `<div class="btn-row"><button class="btn green" id="btn-approve">审核通过</button><button class="btn danger" id="btn-reject">驳回</button></div>`;
  }
  if (perms.can_edit_task) {
    hasAction = true;
    html += `<button class="btn plain block" id="btn-edit-task" style="margin-top:10px">✏️ 修改任务（内容/条件/截止时间/受任人）</button>`;
  }
  if (perms.can_edit_submission) {
    hasAction = true;
    html += `<button class="btn warn block" id="btn-edit-sub" style="margin-top:10px">📝 修改提交内容（审核前可修改）</button>`;
  }
  if (perms.can_next) {
    hasAction = true;
    html += `<div class="btn-row"><button class="btn" id="btn-next">＋ 创建下一节点并指定受任人</button></div>`;
  }
  if (perms.can_feedback || perms.can_appeal) {
    hasAction = true;
    html += `<div class="btn-row"><button class="btn plain" id="btn-feedback">💬 反馈</button><button class="btn warn" id="btn-appeal">⚠️ 申诉不合理处</button></div>`;
  }
  if (perms.can_terminate) {
    hasAction = true;
    html += `<button class="btn danger block" id="btn-terminate" style="margin-top:10px">🛑 结束任务</button>`;
  }
  if (!hasAction) html += `<div style="color:var(--grey);font-size:13px">当前无可用操作</div>`;
  html += `</div>`;

  /* ---- 前置要求 ---- */
  if (prereqs.length) {
    html += `<div class="card"><div class="section-title" style="margin:0 0 2px">前置要求（全部满足才能提交）</div>`;
    html += prereqs.map((p) => {
      if (p.type === "task") {
        const done = p.ref && p.ref.status === "approved";
        return `<div class="prereq-item ${done ? "" : ""}" data-goto="${p.ref ? p.ref.id : ""}" style="cursor:pointer">
          <div class="body">
            <div class="name">📋 ${esc(p.ref ? p.ref.chain_title + " · 节点" + p.ref.seq : "前置任务")}：${esc(p.ref ? p.ref.title : "?")}</div>
            <div class="desc">受任人：${esc(p.ref ? p.ref.assignee_name : "?")} · 点击查看该任务完成情况</div>
          </div>${statusChip(p.ref ? p.ref.status : "gone")}
        </div>`;
      }
      const c = p.custody;
      const mine = p.device_mine;
      const canCheckout = perms.can_checkout.includes(p.device_id);
      let statusHtml, greyed = false, actions = "";
      if (mine) {
        statusHtml = `<span class="chip green">已领用（我手上）</span>`;
        actions = `<button class="btn plain small" data-return="${p.device_id}">归还</button>`;
      } else if (c) {
        greyed = true;
        statusHtml = `<span class="chip grey">占用中</span><span style="font-size:12px;color:var(--grey)">持有人：${esc(c.holder_name)} · 任务：${esc(c.chain_title || "")}节点${c.node_seq || ""}</span>`;
        actions = `<button class="btn plain small" data-where="${p.device_id}">查看去向</button>`;
      } else {
        statusHtml = `<span style="color:var(--text);font-weight:600;font-size:13px">空闲，可领用</span>`;
        actions = (canCheckout ? `<button class="btn small" data-checkout="${p.device_id}">领用</button>` : `<span style="font-size:12px;color:var(--grey)">仅受任人可领用</span>`);
      }
      return `<div class="prereq-item ${greyed ? "greyed" : ""}">
        <div class="body">
          <div class="name">📱 ${esc(p.device ? p.device.name : "设备")}${p.device && p.device.code ? "（" + esc(p.device.code) + "）" : ""}</div>
          <div class="desc" style="display:flex;align-items:center;gap:8px;flex-wrap:wrap">${statusHtml}${actions}</div>
          ${p.penalty_text || p.penalty_url ? `<div class="penalty">⚠ 未完成惩罚/处理办法：${esc(p.penalty_text || "按相关规定处理")}${p.penalty_url ? ` <a href="${esc(p.penalty_url)}" target="_blank" rel="noopener">查看合同/条例↗</a>` : ""}</div>` : ""}
        </div>
      </div>`;
    }).join("");
    html += `</div>`;
  }

  /* ---- 任务内容 ---- */
  if (node.content) {
    html += `<div class="card"><div class="section-title" style="margin:0 0 6px">任务内容</div>
      <div style="white-space:pre-wrap;font-size:14.5px">${esc(node.content)}</div></div>`;
  }

  /* ---- 提交记录 ---- */
  html += `<div class="card"><div class="section-title" style="margin:0 0 2px">提交记录（完成证明）</div>`;
  html += submissions.length ? submissions.map((s) => `
    <div class="msg-item"><div class="head"><b>${esc(s.user)}</b><span style="color:var(--grey);font-size:11.5px">${esc(s.created_at)}</span></div>
      ${s.note ? `<div class="text">${esc(s.note)}</div>` : ""}
      ${fileGrid(s.files)}
    </div>`).join("") : `<div style="color:var(--grey);font-size:13px;padding:6px 0">暂无提交</div>`;
  html += `</div>`;

  /* ---- 沟通记录（反馈/申诉） ---- */
  const topMsgs = messages.filter((m) => !m.reply_to);
  const pendingMsgs = topMsgs.filter((m) => m.status === "open");
  const messagesCard = `<div class="card"${pendingMsgs.length ? ' style="outline:2px solid var(--orange)"' : ''}><div class="section-title" style="margin:0 0 2px">反馈与申诉${pendingMsgs.length ? ` <span class="chip orange">${pendingMsgs.length} 条待处理</span>` : ""}</div>` +
    (topMsgs.length ? topMsgs.map((m) => {
    const kindChip = m.kind === "appeal"
      ? `<span class="chip ${m.status === "open" ? "orange" : m.status === "accepted" ? "green" : "grey"}">申诉·${m.status === "open" ? "待处理" : m.status === "accepted" ? "已受理" : "未受理"}</span>`
      : `<span class="chip blue">反馈</span>`;
    const isAppeal = m.kind === "appeal";
    let ops = "";
    if (perms.can_reply && m.status === "open") {
      ops = `<div class="btn-row" style="margin-top:8px">
        <button class="btn plain small" data-reply="${m.id}">💬 回复</button>
        ${isAppeal ? `<button class="btn green small" data-appeal-edit="${m.id}">✏️ 修改任务并处理</button>` : ""}
        ${isAppeal ? `<button class="btn small" data-appeal-resolve="${m.id}" data-res="accepted">受理</button>
        <button class="btn danger small" data-appeal-resolve="${m.id}" data-res="rejected">不受理</button>` : ""}
      </div>`;
    }
    return `<div class="msg-item">
      <div class="head"><b>${esc(m.uname)}</b>${kindChip}<span style="color:var(--grey);font-size:11.5px">${esc(m.created_at)}</span></div>
      <div class="text">${esc(m.text)}</div>
      ${m.replies.length ? `<div class="replies">${m.replies.map((r) => `<div class="msg-item"><div class="head"><b>${esc(r.uname)}</b><span style="color:var(--grey);font-size:11.5px">${esc(r.created_at)}</span></div><div class="text">${esc(r.text)}</div></div>`).join("")}</div>` : ""}
      ${ops}
    </div>`;
  }).join("") : `<div style="color:var(--grey);font-size:13px;padding:6px 0">暂无反馈/申诉</div>`) +
    `</div>`;

  if (!pendingMsgs.length) {
    html += messagesCard; // 无待处理时保持在提交记录之后
  }

  /* ---- 全流程链 ---- */
  html += `<div class="card"><div class="section-title" style="margin:0 0 2px">任务链全流程（${nodes.length} 个节点）</div>`;
  html += nodes.map((n) => `<div class="chain-node ${n.id === node.id ? "cur" : ""} ${n.status === "approved" ? "done" : ""}" data-goto="${n.id}">
    <div class="seq">${n.seq}</div>
    <div class="info"><div class="t">${esc(n.title)}</div><div class="s">受任人：${esc(n.assignee_name)} · 发布：${esc(n.creator_name)}</div></div>
    ${statusChip(n.status)}<span class="node-arrow">›</span>
  </div>`).join("");
  html += `</div>`;

  /* ---- 全流程时间线 ---- */
  html += `<div class="card"><div class="section-title" style="margin:0 0 8px">全流程记录</div><div class="timeline">`;
  html += events.map((e) => {
    let det = {};
    try { det = JSON.parse(e.detail || "{}"); } catch (err) {}
    let extra = "";
    if (det.comment) extra += det.comment;
    if (det.reason) extra += det.reason;
    if (det.device) extra += (extra ? "：" : "") + det.device;
    if (det.assignee) extra += (extra ? " → 受任人 " : "") + det.assignee;
    if (det.text) extra += (extra ? "：" : "") + det.text;
    if (det.result) extra += (extra ? "：" : "") + (det.result === "accepted" ? "受理" : "不受理");
    const label = EVENT_LABEL[e.type] || e.type;
    return `<div class="tl-item"><div class="tl-head"><b>${esc(e.uname || "?")}</b> ${esc(label)}</div>
      ${extra ? `<div class="tl-detail">${esc(extra)}</div>` : ""}
      <div class="tl-time">${esc(e.created_at)}</div></div>`;
  }).join("");
  html += `</div></div>`;

  if (pendingMsgs.length) {
    html = messagesCard + html; // 有待处理反馈/申诉时置于详情页最上层
  }
  page.innerHTML = html;
  bindViewer(page);
  page.querySelectorAll("[data-goto]").forEach((el) => {
    el.onclick = () => { if (el.dataset.goto) location.hash = "#/node/" + el.dataset.goto; };
  });

  const reload = () => renderNode(app, nodeId);

  /* 提交弹窗 */
  const submitBtn = page.querySelector("#btn-submit");
  if (submitBtn) submitBtn.onclick = () => {
    let files = [];
    modal({
      title: "提交任务完成", okText: "提交", okClass: "green",
      body: `${node.criteria ? `<div style="font-size:13px;background:#f0f6ff;border-radius:8px;padding:8px 10px;margin-bottom:10px">✅ 完成条件：${esc(node.criteria)}</div>` : ""}
        <div class="form-item"><label>完成说明</label><textarea id="s-note" placeholder="说明完成情况（选填）"></textarea></div>
        <div class="form-item"><label>完成证明（图片/视频）</label><input type="file" id="s-files" multiple accept="image/*,video/*">
        <div id="s-filelist" style="margin-top:6px"></div></div>`,
      onOk: async (mask, close) => {
        const ids = [];
        const fs = [...mask.querySelector("#s-files").files];
        mask.querySelector(".m-ok").textContent = "上传中…";
        for (const f of fs) {
          const fd = new FormData(); fd.append("file", f);
          const r = await api("POST", "/api/files", fd, true);
          ids.push(r.id);
        }
        await POST(`/api/nodes/${nodeId}/submit`, { note: mask.querySelector("#s-note").value, files: ids });
        toast("已提交，等待审核");
        close(); await refreshMe(); reload();
      },
    });
  };
  const approveBtn = page.querySelector("#btn-approve");
  if (approveBtn) approveBtn.onclick = () => confirmModal("审核通过", "确认该任务已完成并通过审核？", "通过", "green", async () => {
    await POST(`/api/nodes/${nodeId}/review`, { approve: true, comment: "" });
    toast("已审核通过"); await refreshMe(); reload();
  });
  const rejectBtn = page.querySelector("#btn-reject");
  if (rejectBtn) rejectBtn.onclick = () => modal({
    title: "驳回提交", okText: "驳回", okClass: "danger",
    body: `<div class="form-item"><textarea id="rj-comment" placeholder="驳回原因（受任人可见）"></textarea></div>`,
    onOk: async (mask, close) => {
      await POST(`/api/nodes/${nodeId}/review`, { approve: false, comment: mask.querySelector("#rj-comment").value });
      toast("已驳回"); close(); await refreshMe(); reload();
    },
  });
  const nextBtn = page.querySelector("#btn-next");
  if (nextBtn) nextBtn.onclick = () => { location.hash = `#/create?next=${nodeId}`; };
  const fbBtn = page.querySelector("#btn-feedback");
  if (fbBtn) fbBtn.onclick = () => msgModal(nodeId, "feedback");
  const apBtn = page.querySelector("#btn-appeal");
  if (apBtn) apBtn.onclick = () => msgModal(nodeId, "appeal");
  const termBtn = page.querySelector("#btn-terminate");
  if (termBtn) termBtn.onclick = () => {
    /* 结束：二次确认 */
    confirmModal("结束任务", perms.can_terminate_direct
      ? "你是任务链发起人，结束后整条任务链将立即结束且不可恢复。确定要结束吗？"
      : "结束申请将通过任务链发起人审核，审核同意后任务链结束。确定要申请结束吗？",
      "第一次确认", "danger",
      () => confirmModal("二次确认", "再次确认：结束任务不可撤销，是否继续？", "确定结束", "danger",
        async () => {
          try {
            const r = await POST(`/api/chains/${chain.id}/terminate`, { reason: "" });
            toast(r.direct ? "任务链已结束" : "结束申请已提交，等待发起人审核");
            await refreshMe(); reload();
          } catch (e) { toast(e.message, true); }
        }));
  };
  const termOk = page.querySelector("#term-ok");
  if (termOk) termOk.onclick = () => decideTerminate(chain.id, true, reload);
  const termNo = page.querySelector("#term-no");
  if (termNo) termNo.onclick = () => decideTerminate(chain.id, false, reload);

  /* 设备领用/归还/去向 */
  page.querySelectorAll("[data-checkout]").forEach((el) => {
    el.onclick = async () => {
      try {
        await POST(`/api/devices/${el.dataset.checkout}/checkout`, { node_id: +nodeId });
        toast("领用成功，设备已记录到你名下"); reload();
      } catch (e) { toast(e.message, true); }
    };
  });
  page.querySelectorAll("[data-return]").forEach((el) => {
    el.onclick = async () => {
      try { await POST(`/api/devices/${el.dataset.return}/return`); toast("已归还"); reload(); }
      catch (e) { toast(e.message, true); }
    };
  });
  page.querySelectorAll("[data-where]").forEach((el) => {
    el.onclick = () => { location.hash = "#/device/" + el.dataset.where; };
  });

  const editBtn = page.querySelector("#btn-edit-task");
  if (editBtn) editBtn.onclick = () => openEditTaskModal(node, null, reload);
  const editSubBtn = page.querySelector("#btn-edit-sub");
  if (editSubBtn) editSubBtn.onclick = () => openEditSubModal(node, reload);
  page.querySelectorAll("[data-reply]").forEach((el) => {
    el.onclick = () => {
      const mid = el.dataset.reply;
      modal({
        title: "回复", okText: "发送",
        body: `<div class="form-item"><textarea id="rp-text" placeholder="写下你的回复…"></textarea></div>`,
        onOk: async (mask, close) => {
          await POST(`/api/messages/${mid}/reply`, { text: mask.querySelector("#rp-text").value });
          toast("已回复"); close(); reload();
        },
      });
    };
  });
  page.querySelectorAll("[data-appeal-resolve]").forEach((el) => {
    el.onclick = () => {
      const mid = el.dataset.appealResolve;
      const res = el.dataset.res;
      modal({
        title: res === "accepted" ? "受理申诉" : "不受理申诉", okText: res === "accepted" ? "受理并回复" : "不受理并回复",
        body: `<div class="form-item"><textarea id="rp-text" placeholder="说明处理情况（将推送给申诉人）"></textarea></div>`,
        onOk: async (mask, close) => {
          await POST(`/api/messages/${mid}/reply`, { text: mask.querySelector("#rp-text").value, resolve: res });
          toast("已处理并回复申诉人"); close(); reload();
        },
      });
    };
  });
  page.querySelectorAll("[data-appeal-edit]").forEach((el) => {
    el.onclick = () => openEditTaskModal(node, +el.dataset.appealEdit, reload);
  });
}

/** 修改任务弹窗（创建者）；appealMsgId 传入时，保存后自动回复申诉并受理。 */
async function openEditTaskModal(node, appealMsgId, reload) {
  let users = [];
  try { users = await GET("/api/users"); } catch (e) { toast(e.message, true); return; }
  modal({
    title: appealMsgId ? "修改任务（处理申诉）" : "修改任务",
    okText: appealMsgId ? "保存并回复申诉人" : "保存",
    body: `<div class="form-item"><label>主题</label><input id="et-title" value="${esc(node.title)}"></div>
      <div class="form-item"><label>任务内容</label><textarea id="et-content">${esc(node.content)}</textarea></div>
      <div class="form-item"><label>任务完成条件</label><textarea id="et-criteria">${esc(node.criteria)}</textarea></div>
      <div class="form-item"><label>截止时间</label><input id="et-deadline" type="datetime-local" value="${esc((node.deadline || "").replace(" ", "T"))}"></div>
      <div class="form-item"><label>受任人</label><select id="et-assignee">
        ${users.filter((u) => u.active).map((u) => `<option value="${u.id}" ${u.id === node.assignee_id ? "selected" : ""}>${esc(u.name)}（${esc(u.username)}）</option>`).join("")}
      </select></div>
      ${appealMsgId ? `<div class="form-item"><label>处理说明（将推送给申诉人）</label><textarea id="et-reply" placeholder="说明你针对申诉做了哪些调整…"></textarea></div>` : ""}`,
    onOk: async (mask, close) => {
      const r = await api("PUT", `/api/nodes/${node.id}/edit`, {
        title: mask.querySelector("#et-title").value,
        content: mask.querySelector("#et-content").value,
        criteria: mask.querySelector("#et-criteria").value,
        deadline: mask.querySelector("#et-deadline").value,
        assignee_id: +mask.querySelector("#et-assignee").value || 0,
      });
      const changes = r.changes || [];
      if (appealMsgId) {
        const reply = mask.querySelector("#et-reply").value.trim();
        const text = (reply || "已针对申诉调整任务") + (changes.length ? `
（调整：${changes.join("；")}）` : `
（任务内容已确认，未做调整）`);
        await POST(`/api/messages/${appealMsgId}/reply`, { text, resolve: "accepted" });
      }
      toast(changes.length ? "已修改：" + changes.join("；") : "未做修改");
      close(); reload();
    },
  });
}

/** 修改提交弹窗（受任人，待审核期间）：改说明 + 增删证明文件。 */
function openEditSubModal(node, reload) {
  let files = []; // {id,name}
  let cur = null;
  modal({
    title: "修改提交内容", okText: "保存修改",
    body: `<div class="form-item"><label>完成说明</label><textarea id="es-note"></textarea></div>
      <div class="form-item"><label>当前证明文件（点 ✕ 移除）</label><div id="es-files" style="margin-top:4px"></div>
      <input type="file" id="es-add" multiple accept="image/*,video/*" style="margin-top:8px;padding:8px"></div>`,
    onOk: async (mask, close) => {
      await api("PUT", `/api/nodes/${node.id}/submission`, {
        note: mask.querySelector("#es-note").value,
        files: files.map((f) => f.id),
      });
      toast("提交内容已更新"); close(); reload();
    },
  });
  const mask = document.querySelector(".modal-mask");
  (async () => {
    try {
      const d = await GET("/api/nodes/" + node.id);
      cur = (d.submissions || [])[0];
      if (cur) {
        mask.querySelector("#es-note").value = cur.note || "";
        files = (cur.files || []).map((f) => ({ id: f.file_id, name: f.name }));
        drawFiles();
      }
    } catch (e) { toast(e.message, true); }
  })();
  function drawFiles() {
    mask.querySelector("#es-files").innerHTML = files.length
      ? files.map((f, i) => `<div><span class="chip dark">${esc(f.name || f.id)}</span> <span data-rmf="${i}" style="color:var(--red);cursor:pointer">✕</span></div>`).join("")
      : `<span style="color:var(--grey);font-size:12px">（无）</span>`;
    mask.querySelectorAll("[data-rmf]").forEach((el) => {
      el.onclick = () => { files.splice(+el.dataset.rmf, 1); drawFiles(); };
    });
  }
  mask.querySelector("#es-add").onchange = async (e) => {
    for (const f of [...e.target.files]) {
      try {
        const fd = new FormData(); fd.append("file", f);
        const r = await api("POST", "/api/files", fd, true);
        files.push(r); drawFiles();
      } catch (err) { toast(f.name + "：" + err.message, true); }
    }
    e.target.value = "";
  };
}

function msgModal(nodeId, kind) {
  modal({
    title: kind === "appeal" ? "申诉任务不合理处" : "向发布者反馈", okText: "发送",
    body: `<div class="form-item"><textarea id="m-text" placeholder="${kind === "appeal" ? "说明任务中不合理的地方…" : "写下你要反馈的内容…"}"></textarea></div>`,
    onOk: async (mask, close) => {
      await POST(`/api/nodes/${nodeId}/message`, { text: mask.querySelector("#m-text").value, kind });
      toast(kind === "appeal" ? "申诉已提交" : "反馈已发送");
      close(); location.reload();
    },
  });
}

/* ================= 设备 ================= */
async function renderDevices(app) {
  app.innerHTML = `<div class="topbar">设备<div class="spacer"></div></div>
    <div class="page" id="dev-list"><div class="empty">加载中…</div></div>${tabBar("devices")}`;
  bindTabBar(app);
  await loadDeviceList(app);
}
async function loadDeviceList(app) {
  const rsq = renderSeqNow();
  const box = app.querySelector("#dev-list");
  try {
    const devices = await GET("/api/devices");
    if (renderSeqNow() !== rsq) return;
    box.innerHTML = devices.length ? devices.map((d) => {
      const c = d.custody;
      return `<div class="card task-card" data-dev="${d.id}">
        <div style="display:flex;align-items:center;gap:8px">
          <div class="card-title" style="flex:1;margin:0">${esc(d.name)}${d.code ? ` <span class="chip">${esc(d.code)}</span>` : ""}</div>
          ${c ? `<span class="dev-status"><span class="dot busy"></span><span style="color:var(--grey);font-size:13px">占用中</span></span>`
              : `<span class="dev-status"><span class="dot free"></span><span style="color:var(--green);font-size:13px">空闲</span></span>`}
        </div>
        ${c ? `<div class="meta"><span>在 ${esc(c.holder_name)} 手上 · ${esc(c.chain_title || "")}节点${c.node_seq || ""}</span><span>${esc(c.taken_at || "")}</span></div>`
            : (d.description ? `<div class="meta"><span>${esc(d.description)}</span></div>` : "")}
      </div>`;
    }).join("") : `<div class="empty">暂无注册设备<br><span style="font-size:12px">管理员可在「我的 → 管理后台」注册设备</span></div>`;
    box.querySelectorAll("[data-dev]").forEach((el) => {
      el.onclick = () => { location.hash = "#/device/" + el.dataset.dev; };
    });
  } catch (e) { box.innerHTML = `<div class="empty">${esc(e.message)}</div>`; }
}

async function renderDevice(app, did) {
  const rsq = renderSeqNow();
  app.innerHTML = `<div class="topbar"><span class="back" data-back>‹</span>设备详情</div><div class="page"><div class="empty">加载中…</div></div>${tabBar("devices")}`;
  bindTabBar(app);
  app.querySelector("[data-back]").onclick = () => history.back();
  let d;
  try { d = await GET("/api/devices/" + did); } catch (e) {
    app.querySelector(".page").innerHTML = `<div class="empty">${esc(e.message)}</div>`; return;
  }
  if (renderSeqNow() !== rsq) return;
  const c = d.custody;
  const page = app.querySelector(".page");
  let html = `<div class="card">
    <div class="card-title" style="font-size:17px">${esc(d.device.name)}${d.device.code ? ` <span class="chip">${esc(d.device.code)}</span>` : ""}</div>
    <div style="margin-top:6px">${c ? `<span class="dev-status"><span class="dot busy"></span> <b style="color:var(--grey)">占用中</b></span>`
      : `<span class="dev-status"><span class="dot free"></span> <b style="color:var(--green)">空闲</b></span>`}</div>
    ${c ? `<div class="kv" style="margin-top:8px"><div class="k">当前持有人</div><div class="v">${esc(c.holder_name)}</div></div>
      <div class="kv"><div class="k">所在任务</div><div class="v">${esc(c.chain_title || "")} · 节点${c.node_seq || ""} ${c.node_title ? `「${esc(c.node_title)}」` : ""}</div></div>
      <div class="kv"><div class="k">领用时间</div><div class="v">${esc(c.taken_at || "")}</div></div>
      <div class="btn-row">
        ${ME.user.id === c.holder_id || ME.user.is_admin ? `<button class="btn" id="dev-return">归还设备</button>` : ""}
        <button class="btn plain" id="dev-goto-task">前往任务</button>
      </div>`
      : `<div class="meta" style="margin-top:8px"><span>${esc(d.device.description || "该设备当前空闲，可被任务领用")}</span></div>
        ${d.my_nodes.length ? `<div class="section-title">可用于我的任务</div>` + d.my_nodes.map((n) => `<div class="btn-row"><button class="btn block" data-checkout-node="${n.id}">为「${esc(n.chain_title)}·节点${n.seq}」领用</button></div>`).join("") : ""}
      `}
  </div>`;
  html += `<div class="card"><div class="section-title" style="margin:0 0 4px">流转记录</div>`;
  html += d.history.length ? d.history.map((h) => `
    <div class="tl-item" style="padding-bottom:10px"><div class="tl-head"><b>${esc(h.holder_name)}</b> ${h.returned_at ? "归还" : "领用"}
      ${h.chain_title ? `<span class="chip">${esc(h.chain_title)}·节点${h.node_seq}</span>` : ""}</div>
      <div class="tl-time">${esc(h.taken_at)}${h.returned_at ? " → " + esc(h.returned_at) : " → 在用"}</div></div>`).join("")
    : `<div style="color:var(--grey);font-size:13px">暂无流转记录</div>`;
  html += `</div>`;
  if (pendingMsgs.length) {
    html = messagesCard + html; // 有待处理反馈/申诉时置于详情页最上层
  }
  page.innerHTML = html;
  const retBtn = page.querySelector("#dev-return");
  if (retBtn) retBtn.onclick = async () => {
    try { await POST(`/api/devices/${did}/return`); toast("已归还"); renderDevice(app, did); }
    catch (e) { toast(e.message, true); }
  };
  const gotoBtn = page.querySelector("#dev-goto-task");
  if (gotoBtn) gotoBtn.onclick = () => { if (c.node_id) location.hash = "#/node/" + c.node_id; };
  page.querySelectorAll("[data-checkout-node]").forEach((el) => {
    el.onclick = async () => {
      try {
        await POST(`/api/devices/${did}/checkout`, { node_id: +el.dataset.checkoutNode });
        toast("领用成功"); renderDevice(app, did);
      } catch (e) { toast(e.message, true); }
    };
  });
}

/* ================= 我的 ================= */
async function renderMe(app) {
  const u = ME.user;
  app.innerHTML = `<div class="topbar">我的</div>
  <div class="page">
    <div class="card" style="display:flex;align-items:center;gap:14px">
      <div style="width:56px;height:56px;border-radius:50%;background:var(--primary);color:#fff;display:flex;align-items:center;justify-content:center;font-size:24px">${esc(u.name[0] || "人")}</div>
      <div style="flex:1">
        <div style="font-size:18px;font-weight:700">${esc(u.name)} ${u.is_admin ? '<span class="chip orange">管理员</span>' : ""}</div>
        <div style="color:var(--muted);font-size:13px;margin-top:2px">账号：${esc(u.username)} · 身份：${u.is_admin ? "管理员" : "普通成员"}</div>
      </div>
    </div>
    <div class="menu-list">
      <div class="menu-item" data-go="#/mypub"><div class="ico">📤</div><div class="label">我的发布</div><div class="arrow">›</div></div>
      <div class="menu-item" id="mi-chpwd"><div class="ico">🔑</div><div class="label">修改密码</div><div class="arrow">›</div></div>
      ${u.is_admin ? `<div class="menu-item" data-go="#/admin"><div class="ico">🛠️</div><div class="label">管理后台</div><div class="val">用户 / 设备 / 总览</div><div class="arrow">›</div></div>` : ""}
      <div class="menu-item" id="mi-logout"><div class="ico">🚪</div><div class="label" style="color:var(--red)">退出登录</div></div>
    </div>
    <div style="text-align:center;color:var(--grey);font-size:12px;margin-top:10px">协同任务链 v1.1 · 服务器 ${esc(location.host)}</div>
  </div>${tabBar("me")}`;
  bindTabBar(app);
  app.querySelectorAll("[data-go]").forEach((el) => { el.onclick = () => { location.hash = el.dataset.go; }; });
  app.querySelector("#mi-logout").onclick = async () => {
    await POST("/api/logout"); ME = null; location.hash = "#/login";
  };
  app.querySelector("#mi-chpwd").onclick = () => modal({
    title: "修改密码", okText: "保存",
    body: `<div class="form-item"><label>原密码</label><input type="password" id="pw-old"></div>
      <div class="form-item"><label>新密码（至少 8 位）</label><input type="password" id="pw-new"></div>`,
    onOk: async (mask, close) => {
      await POST("/api/me/password", { old: mask.querySelector("#pw-old").value, new: mask.querySelector("#pw-new").value });
      toast("密码已修改"); close();
    },
  });
}

/* ================= 我的发布 ================= */
async function renderMyPub(app) {
  const rsq = renderSeqNow();
  app.innerHTML = `<div class="topbar"><span class="back" data-back>‹</span>我的发布</div>
    <div class="page" id="pub-list"><div class="empty">加载中…</div></div>${tabBar("me")}`;
  bindTabBar(app);
  app.querySelector("[data-back]").onclick = () => history.back();
  const box = app.querySelector("#pub-list");
  try {
    const d = await GET("/api/mypub");
    if (renderSeqNow() !== rsq) return;
    let html = "";
    if (d.chains.length) {
      html += d.chains.map((item) => {
        const c = item.chain;
        return `<div class="card">
          <div style="display:flex;gap:6px;align-items:center;flex-wrap:wrap">
            ${c.status === "terminated" ? '<span class="chip grey">已结束</span>' : '<span class="chip blue">进行中</span>'}
            <span class="chip">${item.nodes.length} 个节点</span><span>${fmtDT(c.created_at)}</span>
          </div>
          <div class="card-title" style="margin-top:6px">${esc(c.title)}</div>
          ${item.nodes.map((n) => `<div class="chain-node" data-node="${n.id}">
            <div class="seq ${n.status === "approved" ? "" : ""}">${n.seq}</div>
            <div class="info"><div class="t">${esc(n.title)}</div><div class="s">受任人：${esc(n.assignee_name || "?")}${n.creator_id !== c.creator_id ? " · 由上节点完成者指定" : ""}</div></div>
            ${statusChip(n.status)}<span class="node-arrow">›</span></div>`).join("")}
        </div>`;
      }).join("");
    }
    if (d.nodes.length) {
      html += `<div class="section-title">我创建的其他节点</div>` + d.nodes.map((n) => taskCard(n, { alwaysChain: true })).join("");
    }
    box.innerHTML = html || `<div class="empty">还没有发布过任务<br><button class="btn" style="margin-top:14px" onclick="location.hash='#/create'">发布第一个任务</button></div>`;
    box.querySelectorAll("[data-node]").forEach((el) => {
      el.onclick = () => { location.hash = "#/node/" + el.dataset.node; };
    });
  } catch (e) { box.innerHTML = `<div class="empty">${esc(e.message)}</div>`; }
}

/* ================= 管理后台 ================= */
async function renderAdmin(app) {
  let tab = "users";
  app.innerHTML = `<div class="topbar"><span class="back" data-back>‹</span>管理后台</div>
    <div class="page">
      <div class="seg" id="adm-seg"></div>
      <div id="adm-body"></div>
    </div>${tabBar("me")}`;
  bindTabBar(app);
  app.querySelector("[data-back]").onclick = () => history.back();
  const seg = app.querySelector("#adm-seg");
  const body = app.querySelector("#adm-body");
  const drawSeg = () => {
    seg.innerHTML = [["users", "用户"], ["devices", "设备"], ["tasks", "任务总览"], ["appaddr", "APK地址"]].map(([k, t]) =>
      `<div class="seg-item ${tab === k ? "active" : ""}" data-t="${k}">${t}</div>`).join("");
    seg.querySelectorAll(".seg-item").forEach((el) => { el.onclick = () => { tab = el.dataset.t; drawSeg(); load(); }; });
  };
  drawSeg();

  async function load() {
    const rsq = renderSeqNow();
    const stale = () => renderSeqNow() !== rsq;
    if (tab === "users") {
      const users = await GET("/api/admin/users");
      if (stale()) return;
      body.innerHTML = `<button class="btn block" id="add-user" style="margin-bottom:10px">＋ 新增用户</button>` +
        users.map((u) => {
          const isSelf = u.id === ME.user.id;
          const isAdmin = !!u.is_admin;
          let btns = `<button class="btn plain small" data-reset="${u.id}">重置密码</button>`;
          if (!isAdmin) {
            btns += `<button class="btn ${u.active ? "danger" : "green"} small" data-toggle="${u.id}" data-active="${u.active}">${u.active ? "停用" : "启用"}</button>
              <button class="btn plain small" data-promote="${u.id}">设为管理员</button>
              <button class="btn plain small" data-deluser="${u.id}" style="color:var(--red)">删除</button>`;
          } else if (u.username !== "admin" && !isSelf) {
            btns += `<button class="btn plain small" data-demote="${u.id}" style="color:var(--orange)">降权</button>`;
          }
          return `<div class="card" style="display:flex;align-items:center;gap:10px;flex-wrap:wrap">
          <div style="flex:1;min-width:120px"><b>${esc(u.name)}</b> ${isAdmin ? '<span class="chip orange">管理员</span>' : ""}
            <div style="font-size:12px;color:var(--muted)">${esc(u.username)} · ${u.active ? "正常" : "已停用"}${isSelf ? " · 我" : ""}</div></div>
          ${btns}
        </div>`;
        }).join("");
      body.querySelector("#add-user").onclick = () => modal({
        title: "新增用户", okText: "创建",
        body: `<div class="form-item"><label>账号（字母/数字/下划线）</label><input id="nu-username"></div>
          <div class="form-item"><label>姓名</label><input id="nu-name"></div>
          <div class="form-item"><label>初始密码（至少 8 位）</label><input id="nu-pass"></div>
          <div class="form-item"><label><input type="checkbox" id="nu-admin" style="width:auto"> 设为管理员</label></div>`,
        onOk: async (mask, close) => {
          await POST("/api/admin/users", {
            username: mask.querySelector("#nu-username").value.trim(),
            name: mask.querySelector("#nu-name").value.trim(),
            password: mask.querySelector("#nu-pass").value,
            is_admin: mask.querySelector("#nu-admin").checked,
          });
          toast("用户已创建"); close(); load();
        },
      });
      body.querySelectorAll("[data-reset]").forEach((el) => {
        el.onclick = () => modal({
          title: "重置密码", okText: "重置",
          body: `<div class="form-item"><input id="rp-pass" placeholder="新密码（至少 8 位）"></div>`,
          onOk: async (mask, close) => {
            await POST(`/api/admin/users/${el.dataset.reset}/reset`, { password: mask.querySelector("#rp-pass").value });
            toast("已重置"); close();
          },
        });
      });
      body.querySelectorAll("[data-toggle]").forEach((el) => {
        el.onclick = async () => {
          try {
            await POST(`/api/admin/users/${el.dataset.toggle}/active`, { active: el.dataset.active !== "1" });
            load();
          } catch (e) { toast(e.message, true); }
        };
      });
      body.querySelectorAll("[data-demote]").forEach((el) => {
        el.onclick = () => confirmModal("降权",
          "取消该用户的管理员身份，账号保留为普通成员。确定降权？",
          "降权", "warn", async () => {
            try {
              await POST(`/api/admin/users/${el.dataset.demote}/demote`);
              toast("已降权为普通成员"); load();
            } catch (e) { toast(e.message, true); }
          });
      });
      body.querySelectorAll("[data-promote]").forEach((el) => {
        el.onclick = () => confirmModal("设为管理员",
          "该用户将可以进入管理后台管理用户、设备与任务。确定设为管理员？",
          "设为管理员", "green", async () => {
            try {
              await POST(`/api/admin/users/${el.dataset.promote}/promote`);
              toast("已设为管理员"); load();
            } catch (e) { toast(e.message, true); }
          });
      });
      body.querySelectorAll("[data-deluser]").forEach((el) => {
        el.onclick = () => confirmModal("删除用户",
          "仅能删除从未参与过任务的用户；参与过任务的用户为保证流程记录完整只能停用。确定删除该用户？",
          "删除", "danger", async () => {
            try {
              await api("DELETE", `/api/admin/users/${el.dataset.deluser}`);
              toast("用户已删除"); load();
            } catch (e) { toast(e.message, true); }
          });
      });
    } else if (tab === "devices") {
      const devices = await GET("/api/devices");
      if (stale()) return;
      body.innerHTML = `<button class="btn block" id="add-dev" style="margin-bottom:10px">＋ 注册设备</button>` +
        (devices.length ? devices.map((d) => {
          const c = d.custody;
          return `<div class="card">
            <div style="display:flex;align-items:center;gap:8px">
              <div style="flex:1"><b>${esc(d.name)}</b>${d.code ? ` <span class="chip">${esc(d.code)}</span>` : ""}
                <div style="font-size:12px;color:var(--muted);margin-top:2px">${esc(d.description || "")}</div></div>
              ${c ? `<span class="dev-status"><span class="dot busy"></span>占用中</span>` : `<span class="dev-status"><span class="dot free"></span>空闲</span>`}
            </div>
            ${c ? `<div class="meta"><span>在 ${esc(c.holder_name)} 手上 · ${esc(c.chain_title || "")}节点${c.node_seq || ""}</span></div>
              <div class="btn-row"><button class="btn warn small" data-release="${d.id}">强制释放</button>
              <button class="btn plain small" data-deldev="${d.id}" style="color:var(--red)">删除</button></div>`
              : `<div class="btn-row"><button class="btn plain small" data-deldev="${d.id}" style="color:var(--red)">删除</button></div>`}
          </div>`;
        }).join("") : `<div class="empty">暂无设备</div>`);
      body.querySelector("#add-dev").onclick = () => modal({
        title: "注册设备", okText: "注册",
        body: `<div class="form-item"><label>设备名称</label><input id="nd-name" placeholder="如：无人机-01"></div>
          <div class="form-item"><label>设备编号（选填，唯一）</label><input id="nd-code"></div>
          <div class="form-item"><label>描述（选填）</label><input id="nd-desc"></div>`,
        onOk: async (mask, close) => {
          await POST("/api/admin/devices", {
            name: mask.querySelector("#nd-name").value, code: mask.querySelector("#nd-code").value,
            description: mask.querySelector("#nd-desc").value,
          });
          toast("设备已注册"); close(); load();
        },
      });
      body.querySelectorAll("[data-release]").forEach((el) => {
        el.onclick = () => confirmModal("强制释放设备", "释放后设备回到空闲状态，持有人需重新领用。确定强制释放？", "强制释放", "warn", async () => {
          await POST(`/api/admin/devices/${el.dataset.release}/release`);
          toast("已强制释放"); load();
        });
      });
      body.querySelectorAll("[data-deldev]").forEach((el) => {
        el.onclick = () => confirmModal("删除设备",
          "占用中或已被任务用作前置要求的设备无法删除。删除后该设备的流转记录一并清除，确定删除？",
          "删除", "danger", async () => {
            try {
              await api("DELETE", `/api/admin/devices/${el.dataset.deldev}`);
              toast("设备已删除"); load();
            } catch (e) { toast(e.message, true); }
          });
      });
    } else if (tab === "appaddr") {
      const cfg = await GET("/api/admin/appconfig");
      if (stale()) return;
      body.innerHTML = `<div class="card">
        <div class="section-title" style="margin:0 0 6px">APK 端官方访问地址</div>
        <div class="form-item"><input id="ac-url" placeholder="http://192.168.x.x:8000 或穿透地址" value="${esc(cfg.app_server_url)}"></div>
        <div style="font-size:12.5px;color:var(--muted);margin-bottom:10px">
          保存后，已安装的 APK 在下次成功连上服务器时（打开或刷新页面）会自动切换到该地址；
          地址已失效导致完全连不上的 APK，需在其菜单「切换服务器地址」手动输入一次新地址。</div>
        <button class="btn block" id="ac-save">保存地址</button>
        <div class="btn-row">
          <button class="btn plain small" id="ac-lan">填入本机局域网地址（${esc(cfg.lan_url)}）</button>
        </div>
        <div id="ac-qrbox" style="text-align:center;margin-top:12px"></div>
      </div>`;
      body.querySelector("#ac-save").onclick = async () => {
        try {
          const r = await api("PUT", "/api/admin/appconfig", { app_server_url: body.querySelector("#ac-url").value.trim() });
          toast("已保存" + (r.app_server_url ? "：" + r.app_server_url : "（已清空）"));
          load();
        } catch (e) { toast(e.message, true); }
      };
      body.querySelector("#ac-lan").onclick = () => {
        body.querySelector("#ac-url").value = cfg.lan_url;
      };
      if (cfg.app_server_url) {
        body.querySelector("#ac-qrbox").innerHTML =
          `<div style="font-size:12px;color:var(--muted);margin-bottom:6px">手机浏览器扫码可直接打开（网页版）</div>
           <img src="/api/admin/appconfig/qr.svg" alt="二维码" style="width:190px;height:190px;background:#fff;border:1px solid var(--line);border-radius:8px;padding:6px">`;
      }
      /* 固定入口（Gitee raw 指针） */
      const es = await GET("/api/admin/entrysync");
      const distInfo = await GET("/apk/info").catch(() => ({version: "", available: false}));
      if (distInfo.available) {
        card.insertAdjacentHTML("beforeend", `<div style="font-size:12px;color:var(--muted);margin-top:10px">📦 服务器当前分发的 APK：v${esc((distInfo.version || "").replace("v", ""))}（公开下载地址 ${esc(location.origin)}/apk）</div>`);
      }
      /* 救援邮箱（frp 地址变更自动发信，APK 失联时 POP3 自救） */
      const rm = await GET("/api/admin/rescuemail");
      card.insertAdjacentHTML("afterend", `<div class="card">
        <div class="section-title" style="margin:0 0 6px">救援邮箱（frp 地址变更自动发信）</div>
        <div style="font-size:12.5px;color:var(--muted);margin-bottom:10px">
          用一个<b>专用 QQ 邮箱</b>：设置→账户→开启 IMAP/SMTP 服务→生成<b>授权码</b>。
          配置后每次保存官方地址会自动发一封邮件到该邮箱；成员的 APK 首次连上服务器时自动缓存凭据，
          之后即使 frp 地址彻底失联，App 也会登录邮箱读取最新地址自救。发件=收件填同一个邮箱即可。</div>
        <div class="form-item"><input id="rm-sender" placeholder="邮箱账号，如 xxx@qq.com" value="${esc(rm.sender)}"></div>
        <div class="form-item"><input id="rm-code" placeholder="${rm.code ? "授权码（已保存 " + esc(rm.code) + "，留空保持不变）" : "SMTP/POP3 授权码"}"></div>
        <div class="form-item"><input id="rm-to" placeholder="收件邮箱（可与发件相同）" value="${esc(rm.to)}"></div>
        <div style="font-size:12px;color:var(--muted)">POP3：${esc(rm.pop_host)} · SMTP：${esc(rm.smtp_host)}</div>
        <div class="btn-row"><button class="btn small" id="rm-save">保存救援邮箱</button>
          <button class="btn plain small" id="rm-test">发送测试邮件</button></div>
      </div>`);
      body.querySelector("#rm-save").onclick = async () => {
        try {
          await api("PUT", "/api/admin/rescuemail", {
            sender: body.querySelector("#rm-sender").value.trim(),
            code: body.querySelector("#rm-code").value.trim(),
            to: body.querySelector("#rm-to").value.trim(),
          });
          toast("救援邮箱已保存"); load();
        } catch (e) { toast(e.message, true); }
      };
      body.querySelector("#rm-test").onclick = async () => {
        try {
          await api("PUT", "/api/admin/rescuemail", {
            sender: body.querySelector("#rm-sender").value.trim(),
            code: body.querySelector("#rm-code").value.trim(),
            to: body.querySelector("#rm-to").value.trim(),
          });
          const r = await POST("/api/admin/rescuemail/test");
          toast(r.message, !r.ok);
        } catch (e) { toast(e.message, true); }
      };
      card.insertAdjacentHTML("afterend", `<div class="card">
        <div class="section-title" style="margin:0 0 6px">自托管入口服务器（frp 方案首选）</div>
        <div style="font-size:12.5px;color:var(--muted);margin-bottom:10px">
          在跑 frps 的 VPS 上运行项目自带的 <b>tunnel/entry_server.py</b>（纯标准库，秒级部署），
          APK 固定入口填 <b>http://VPS_IP:9300/config.json</b>。此后 frp 地址随便换，
          这里保存地址时自动推送过去；推送密钥用 entry_server 首次启动打印的 token。</div>
        <div class="form-item"><input id="es-pushurl" placeholder="http://VPS_IP:9300/update" value="${esc(es.push_url)}"></div>
        <div class="form-item"><input id="es-pushtoken" placeholder="${es.push_token ? "推送密钥（已保存 " + esc(es.push_token) + "，留空保持不变）" : "推送密钥（entry_server 启动时打印）"}"></div>
        ${es.custom_configured ? `<div style="font-size:12px;color:var(--muted);margin-bottom:8px">✅ 已配置自托管入口</div>` : ""}
        <div class="btn-row"><button class="btn plain small" id="es-save2">保存推送目标</button></div>
      </div>`);
      body.querySelector("#es-save2").onclick = async () => {
        try {
          await api("PUT", "/api/admin/entrysync", {
            push_url: body.querySelector("#es-pushurl").value.trim(),
            push_token: body.querySelector("#es-pushtoken").value.trim(),
          });
          toast("推送目标已保存"); load();
        } catch (e) { toast(e.message, true); }
      };
      card.insertAdjacentHTML("afterend", `<div class="card">
        <div class="section-title" style="margin:0 0 6px">Gitee 入口（备选）</div>
        <div style="font-size:12.5px;color:var(--muted);margin-bottom:10px">
          把官方地址写进 Gitee 仓库的一个 JSON 文件，APK 里配好同一文件地址后，即使保存的地址彻底失联，
          也能在下次启动时自动拿到新地址自救。需要：Gitee 账号 → 新建一个<b>公开</b>仓库 →
          设置里生成<b>私人令牌</b>（勾选 projects 基础权限即可）。</div>
        <div class="form-item"><input id="es-owner" placeholder="Gitee 用户名" value="${esc(es.entry_owner)}"></div>
        <div class="form-item"><input id="es-repo" placeholder="仓库名（公开）" value="${esc(es.entry_repo)}"></div>
        <div class="form-item"><input id="es-path" placeholder="文件路径，如 app/config.json" value="${esc(es.entry_path)}"></div>
        <div class="form-item"><input id="es-branch" placeholder="分支（默认 master）" value="${esc(es.entry_branch)}"></div>
        <div class="form-item"><input id="es-token" placeholder="${es.token ? "私人令牌（已保存 " + esc(es.token) + "，留空保持不变）" : "Gitee 私人令牌"}"></div>
        <div class="btn-row">
          <button class="btn plain" id="es-save">保存同步配置</button>
          <button class="btn" id="es-push">立即推送当前地址</button>
        </div>
        ${es.raw_url ? `<div style="font-size:12px;color:var(--muted);margin-top:8px;word-break:break-all">入口文件地址（填进 APK 的「固定入口」）：<br>${esc(es.raw_url)}</div>` : ""}
      </div>`);
      body.querySelector("#es-save").onclick = async () => {
        try {
          await api("PUT", "/api/admin/entrysync", {
            owner: body.querySelector("#es-owner").value.trim(),
            repo: body.querySelector("#es-repo").value.trim(),
            path: body.querySelector("#es-path").value.trim(),
            branch: body.querySelector("#es-branch").value.trim() || "master",
            token: body.querySelector("#es-token").value.trim(),
          });
          toast("同步配置已保存"); load();
        } catch (e) { toast(e.message, true); }
      };
      body.querySelector("#es-push").onclick = async () => {
        try {
          const r = await POST("/api/admin/entrysync/push");
          toast(r.message || (r.ok ? "已推送" : "推送失败"), !r.ok);
        } catch (e) { toast(e.message, true); }
      };
    } else {
      const ov = await GET("/api/admin/overview");
      if (stale()) return;
      body.innerHTML = `<div class="card"><div style="display:flex;text-align:center">
        ${[["任务链", ov.stats.chains], ["进行中", ov.stats.active], ["设备", ov.stats.devices], ["用户", ov.stats.users]].map(([k, v]) =>
        `<div style="flex:1"><div style="font-size:22px;font-weight:700">${v}</div><div style="font-size:12px;color:var(--muted)">${k}</div></div>`).join("")}
      </div></div>` +
        (ov.chains.map((c) => `<div class="card">
          <div style="display:flex;gap:6px;align-items:center;flex-wrap:wrap">
            ${c.status === "terminated" ? '<span class="chip grey">已结束</span>' : '<span class="chip blue">进行中</span>'}
            <span class="chip">${c.node_count} 节点</span><span>发布：${esc(c.creator_name || "?")}</span>
            <span style="flex:1"></span>
            <button class="btn plain small" data-delchain="${c.id}" style="color:var(--red)">删除</button>
          </div>
          <div class="card-title" style="margin-top:5px">${esc(c.title)}</div>
        </div>`).join("") || `<div class="empty">暂无任务</div>`);
      body.querySelectorAll("[data-delchain]").forEach((el) => {
        el.onclick = () => confirmModal("删除任务链",
          "将永久删除该链全部节点、提交证明、反馈申诉与时间线，不可恢复；设备领用记录一并清除。\n被其他任务引用为前置、或仍有设备未归还时无法删除。确定删除？",
          "删除", "danger", async () => {
            try {
              await api("DELETE", `/api/admin/chains/${el.dataset.delchain}`);
              toast("任务链已删除"); load();
            } catch (e) { toast(e.message, true); }
          });
      });
    }
  }
  await load().catch((e) => toast(e.message, true));
}

/* ================= 路由 ================= */
const routes = [
  [/^#\/login$/, (a) => renderLogin(a)],
  [/^#\/register$/, (a) => renderRegister(a)],
  [/^#\/home$/, (a) => renderHome(a)],
  [/^#\/create(\?[^#]*)?$/, (a) => renderCreate(a)],
  [/^#\/node\/(\d+)$/, (a, m) => renderNode(a, +m[1])],
  [/^#\/devices$/, (a) => renderDevices(a)],
  [/^#\/device\/(\d+)$/, (a, m) => renderDevice(a, +m[1])],
  [/^#\/me$/, (a) => renderMe(a)],
  [/^#\/mypub$/, (a) => renderMyPub(a)],
  [/^#\/admin$/, (a) => renderAdmin(a)],
];

let renderSeq = 0;
function renderSeqNow() { return window.__renderSeq || 0; }

async function render() {
  const seq = ++renderSeq;
  window.__renderSeq = seq;
  const app = document.getElementById("app");
  const hash = location.hash || "#/home";
  if (!ME) {
    try { ME = await GET("/api/me"); } catch (e) { ME = null; }
  }
  if (!ME && hash !== "#/login" && hash !== "#/register") { location.hash = "#/login"; return; }
  if (ME && (hash === "#/login" || hash === "#/register")) { location.hash = "#/home"; return; }
  for (const [re, fn] of routes) {
    const m = hash.match(re);
    if (m) {
      try { await fn(app, m); } catch (e) { toast(e.message, true); }
      if (seq === renderSeq) {
        window.scrollTo(0, 0);
      } else {
        render(); // 渲染期间有更新的请求排队：重渲染以恢复最终一致
      }
      return;
    }
  }
  location.hash = "#/home";
}

window.addEventListener("hashchange", render);
function updateTabBadge() {
  if (!ME) return;
  const bU = ME.badges.unfinished + ME.badges.pending_review + (ME.badges.feedback || 0);
  const badge = document.querySelector(".tabbar .badge");
  if (badge) { badge.style.display = bU ? "inline" : "none"; badge.textContent = bU; }
}
setInterval(async () => {
  if (!ME || document.querySelector(".modal-mask") || /input|textarea|select/i.test(document.activeElement.tagName)) return;
  await refreshMe();
  updateTabBadge();
}, 30000);
let visRenderTimer = null;
document.addEventListener("visibilitychange", () => {
  if (document.hidden) return;
  const hash = location.hash || "#/home";
  if (hash.startsWith("#/create")) { refreshMe(); return; } // 表单页不能重渲染，避免清空输入
  clearTimeout(visRenderTimer);
  visRenderTimer = setTimeout(() => render(), 400);
});

render();
