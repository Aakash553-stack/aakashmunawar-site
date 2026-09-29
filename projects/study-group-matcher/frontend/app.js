// Study Group Matcher frontend: plain browser JavaScript, no build step.
// Talks to the Spring Boot API with a JWT in the Authorization header.

(() => {
  "use strict";

  const LOCAL = ["localhost", "127.0.0.1"].includes(location.hostname);
  const API = LOCAL ? "http://localhost:8080" : window.STUDY_API_BASE;
  const DAYS = ["Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"];   // ISO 1..7
  const GRID_START = 7 * 60, GRID_END = 23 * 60, SLOT = 30;         // weekly grid: 7am-11pm

  const $ = (sel) => document.querySelector(sel);
  const statusEl = $("#status");
  let token = storage("get", "sgm_token");
  let profile = null;
  let catalog = null;          // all courses, loaded once

  // ------------------------------------------------------------------ helpers

  function storage(op, key, value) {
    try {
      if (op === "get") return localStorage.getItem(key);
      if (op === "set") localStorage.setItem(key, value);
      if (op === "del") localStorage.removeItem(key);
    } catch { /* private mode etc.: stay logged in for this page only */ }
    return null;
  }

  /** Build DOM without innerHTML: el("p", {class: "x"}, "text", childNode, ...). */
  function el(tag, attrs = {}, ...children) {
    const node = document.createElement(tag);
    for (const [k, v] of Object.entries(attrs)) {
      if (k === "class") node.className = v;
      else if (k.startsWith("on")) node.addEventListener(k.slice(2), v);
      else if (v !== false && v !== null && v !== undefined) node.setAttribute(k, v === true ? "" : v);
    }
    for (const c of children.flat()) if (c !== null && c !== undefined && c !== false) node.append(c);
    return node;
  }

  function clock(minutes) {
    const h = Math.floor(minutes / 60) % 24, m = minutes % 60;
    return `${h % 12 || 12}:${String(m).padStart(2, "0")}${h < 12 ? "am" : "pm"}`;
  }
  const hours = (min) => `${(min / 60).toFixed(min % 60 ? 1 : 0)} h`;
  const slotLabel = (b) => `${DAYS[b.day - 1]} ${clock(b.start)}–${clock(b.end)}`;
  const title = (s) => s.toLowerCase().replace(/\b\w/g, (c) => c.toUpperCase());
  const courseLabel = (c) => `${c.code} ${title(c.title)}`;

  function say(message, isError = false) {
    statusEl.textContent = message || "";
    statusEl.classList.toggle("error", isError);
  }

  async function api(path, { method = "GET", body } = {}) {
    // The free backend sleeps when idle; explain a slow first response.
    const slow = setTimeout(() => say("Waking up the server (free hosting sleeps when idle). This can take up to a minute…"), 2500);
    try {
      const res = await fetch(API + path, {
        method,
        headers: { ...(body ? { "Content-Type": "application/json" } : {}),
                   ...(token ? { Authorization: `Bearer ${token}` } : {}) },
        body: body ? JSON.stringify(body) : undefined,
      });
      if (res.status === 401 && token) {
        logout("Your session expired. Please log in again.");
        throw new Error("Not logged in");
      }
      if (res.status === 204) return null;
      const data = await res.json().catch(() => null);
      if (!res.ok) throw new Error(data?.detail || `Request failed (${res.status})`);
      return data;
    } catch (err) {
      if (err instanceof TypeError) throw new Error("Couldn't reach the server. Check your connection and try again.");
      throw err;
    } finally {
      clearTimeout(slow);
    }
  }

  async function run(action, doneMessage) {
    try {
      say("Working…");
      await action();
      say(doneMessage || "");
    } catch (err) {
      say(err.message, true);
    }
  }

  // ------------------------------------------------------------------- router

  const views = ["auth", "matches", "groups", "profile"];

  async function route() {
    let view = location.hash.replace(/^#\//, "") || "matches";
    if (!token) view = "auth";
    else if (view === "auth" || !views.includes(view)) view = "matches";
    for (const v of views) $(`#view-${v}`).hidden = v !== view;
    $("#nav").hidden = !token;
    document.querySelectorAll("#nav a").forEach((a) => a.classList.toggle("active", a.hash === `#/${view}`));
    say("");
    if (view === "auth") return;
    await run(async () => {
      profile = profile || await api("/api/me");
      catalog = catalog || await api("/api/courses");
      if (view === "matches") await showMatches();
      if (view === "groups") await showGroups();
      if (view === "profile") showProfile();
    });
  }

  function logout(message) {
    token = null;
    profile = null;
    storage("del", "sgm_token");
    location.hash = "#/auth";
    route().then(() => message && say(message));
  }

  // --------------------------------------------------------------------- auth

  let authMode = "login";
  document.querySelectorAll(".tabs button").forEach((b) => b.addEventListener("click", () => {
    authMode = b.dataset.mode;
    document.querySelectorAll(".tabs button").forEach((x) => x.setAttribute("aria-selected", x === b));
    document.querySelectorAll("[data-only=signup]").forEach((x) => (x.hidden = authMode !== "signup"));
    $("#auth-form").displayName.required = authMode === "signup";
    $("#auth-form").password.autocomplete = authMode === "signup" ? "new-password" : "current-password";
    $("#auth-submit").textContent = authMode === "signup" ? "Create account" : "Log in";
  }));

  $("#auth-form").addEventListener("submit", (e) => {
    e.preventDefault();
    const f = e.target;
    const body = { email: f.email.value, password: f.password.value };
    if (authMode === "signup") body.displayName = f.displayName.value;
    run(async () => {
      const res = await api(`/api/auth/${authMode}`, { method: "POST", body });
      token = res.token;
      profile = res.student;
      storage("set", "sgm_token", token);
      f.reset();
      location.hash = authMode === "signup" ? "#/profile" : "#/matches";
      await route();
    });
  });

  $("#logout").addEventListener("click", () => logout("Logged out."));

  // ------------------------------------------------------------------ matches

  async function showMatches() {
    const box = $("#matches");
    box.replaceChildren();
    if (!profile.courses.length || !profile.availability.length) {
      box.append(el("div", { class: "empty" }, "Add your courses and weekly free time on the ",
        el("a", { href: "#/profile" }, "Profile"), " page to get matches."));
      return;
    }
    const recs = await api("/api/matches?limit=20");
    if (!recs.length) {
      box.append(el("div", { class: "empty" },
        "No matches yet: nobody who shares a course with you (and isn't already in a group for it) is free when you are. ",
        "Try adding more free time, or ", el("a", { href: "#/groups" }, "start a group"), " so classmates can find you."));
      return;
    }
    recs.forEach((r, i) => box.append(matchCard(r, i + 1)));
  }

  function matchCard(r, rank) {
    const isGroup = r.kind === "GROUP";
    const courses = r.sharedCourses.map(courseLabel).join(", ");
    const head = el("div", { class: "item-head" },
      el("h3", {}, `#${rank} `, isGroup ? r.group.name : r.peer.displayName),
      el("span", { class: `badge ${isGroup ? "" : "peer"}` }, isGroup ? "Group to join" : "Classmate, no group yet"));
    const stats = el("div", { class: "stats" },
      el("span", {}, el("strong", {}, hours(r.usableMinutes)), " free together per week"),
      el("span", {}, "longest block ", el("strong", {}, hours(r.longestBlockMinutes))),
      el("span", {}, el("strong", {}, String(r.daysWithTime)), ` day${r.daysWithTime === 1 ? "" : "s"}`),
      isGroup ? el("span", {}, el("strong", {}, `${r.group.memberCount}/${r.group.capacity}`), " members") : null);
    const slots = el("div", { class: "slots" }, r.overlap.map((b) => el("span", { class: "slot" }, slotLabel(b))));

    let action;
    if (isGroup) {
      action = el("button", { class: "primary", onclick: () => run(async () => {
        await api(`/api/groups/${r.group.id}/join`, { method: "POST" });
        await showMatches();
      }, `Joined "${r.group.name}".`) }, "Join group");
    } else {
      const course = r.sharedCourses[0];
      action = el("button", { onclick: () => {
        location.hash = "#/groups";
        setTimeout(() => { $("#browse-course").value = course.id; $("#browse-course").dispatchEvent(new Event("change")); }, 0);
      } }, `Start a ${course.code} group`);
    }
    return el("article", { class: "item" }, head,
      el("div", { class: "members" }, `Shared course${r.sharedCourses.length > 1 ? "s" : ""}: ${courses}`),
      stats, slots,
      isGroup ? null : el("p", { class: "hint" },
        `${r.peer.displayName} will see your group in their matches if you start one for a course you share.`),
      el("div", {}, action));
  }

  // ------------------------------------------------------------------- groups

  async function showGroups() {
    const mine = await api("/api/groups/mine");
    const box = $("#my-groups");
    box.replaceChildren();
    if (!mine.length) box.append(el("div", { class: "empty" }, "You're not in any groups yet."));
    for (const g of mine) box.append(await groupCard(g, true));

    const select = $("#browse-course");
    select.replaceChildren(...profile.courses.map((c) => el("option", { value: c.id }, courseLabel(c))));
    $("#create-group").hidden = !profile.courses.length;
    if (!profile.courses.length) {
      $("#course-groups").replaceChildren(el("div", { class: "empty" }, "Add courses on your ",
        el("a", { href: "#/profile" }, "Profile"), " first."));
      return;
    }
    await showCourseGroups();
  }

  async function showCourseGroups() {
    const courseId = $("#browse-course").value;
    const groups = await api(`/api/groups?courseId=${encodeURIComponent(courseId)}`);
    const box = $("#course-groups");
    box.replaceChildren();
    if (!groups.length) box.append(el("div", { class: "empty" }, "No groups for this course yet: be the first."));
    for (const g of groups) box.append(await groupCard(g, false));
  }

  async function groupCard(g, detailed) {
    const detail = detailed ? await api(`/api/groups/${g.id}`) : null;
    const buttons = [];
    if (g.member) {
      buttons.push(el("button", { onclick: () => run(async () => {
        await api(`/api/groups/${g.id}/leave`, { method: "POST" });
        await showGroups();
      }, `Left "${g.name}".`) }, "Leave"));
    } else if (g.memberCount < g.capacity) {
      buttons.push(el("button", { class: "primary", onclick: () => run(async () => {
        await api(`/api/groups/${g.id}/join`, { method: "POST" });
        await showGroups();
      }, `Joined "${g.name}".`) }, "Join"));
    }
    return el("article", { class: "item" },
      el("div", { class: "item-head" },
        el("h3", {}, g.name),
        el("span", { class: "badge" }, `${g.course.code} · ${g.memberCount}/${g.capacity}${g.owner ? " · you started it" : ""}`)),
      g.description ? el("p", { class: "hint" }, g.description) : null,
      detail ? el("div", { class: "members" }, "Members: ",
        detail.members.map((m) => m.displayName + (m.owner ? " (organizer)" : "")).join(", ")) : null,
      detail ? el("div", {}, el("div", { class: "members" }, "When everyone is free:"),
        detail.commonFreeTime.length
          ? el("div", { class: "slots" }, detail.commonFreeTime.map((b) => el("span", { class: "slot" }, slotLabel(b))))
          : el("span", { class: "hint" }, "no common free time yet")) : null,
      buttons.length ? el("div", { class: "row" }, buttons) : null);
  }

  $("#browse-course").addEventListener("change", () => run(showCourseGroups));
  $("#create-group").addEventListener("submit", (e) => {
    e.preventDefault();
    const f = e.target;
    run(async () => {
      await api("/api/groups", { method: "POST", body: {
        courseId: Number($("#browse-course").value), name: f.name.value,
        description: f.description.value, capacity: Number(f.capacity.value) } });
      f.reset();
      await showGroups();
    }, "Group created.");
  });

  // ------------------------------------------------------------------ profile

  function showProfile() {
    renderChips();
    buildGrid();
    loadGrid(profile.availability);
  }

  function renderChips() {
    $("#my-courses").replaceChildren(...(profile.courses.length ? profile.courses.map((c) =>
      el("span", { class: "chip" }, courseLabel(c),
        el("button", { "aria-label": `Remove ${c.code}`, onclick: () => saveCourses(profile.courses.filter((x) => x.id !== c.id)) }, "×")))
      : [el("span", { class: "hint" }, "No courses yet.")]));
  }

  function saveCourses(courses) {
    run(async () => {
      profile = await api("/api/me/courses", { method: "PUT", body: { courseIds: courses.map((c) => c.id) } });
      renderChips();
      searchCourses();
    }, "Courses saved.");
  }

  function searchCourses() {
    const q = $("#course-search").value.trim().toLowerCase();
    const box = $("#course-results");
    box.replaceChildren();
    if (!q) return;
    const mine = new Set(profile.courses.map((c) => c.id));
    const found = catalog.filter((c) => !mine.has(c.id) &&
      (c.code.includes(q) || c.title.toLowerCase().includes(q))).slice(0, 12);
    if (!found.length) box.append(el("span", { class: "hint" }, "No matching Fall 2026 CS or Math course."));
    for (const c of found) {
      box.append(el("button", { onclick: () => {
        $("#course-search").value = "";
        saveCourses([...profile.courses, c]);
      } }, el("span", { class: "code" }, c.code), title(c.title)));
    }
  }
  $("#course-search").addEventListener("input", searchCourses);

  // Weekly grid: one cell per half hour, click or drag to paint.
  let painting = null;   // true = marking free, false = clearing

  function buildGrid() {
    const grid = $("#week");
    if (grid.childElementCount) return;
    grid.append(el("div"), ...DAYS.map((d) => el("div", { class: "head" }, d)));
    for (let t = GRID_START; t < GRID_END; t += SLOT) {
      grid.append(el("div", { class: "time" }, t % 60 === 0 ? clock(t) : ""));
      for (let d = 1; d <= 7; d++) {
        grid.append(el("div", { class: `cell${t % 60 === 0 ? " hour" : ""}`, "data-day": d, "data-t": t,
          role: "gridcell", "aria-label": `${DAYS[d - 1]} ${clock(t)}` }));
      }
    }
    grid.addEventListener("pointerdown", (e) => {
      const cell = e.target.closest(".cell");
      if (!cell) return;
      painting = !cell.classList.contains("on");
      cell.classList.toggle("on", painting);
      updateSummary();
      e.preventDefault();
    });
    grid.addEventListener("pointerover", (e) => {
      const cell = e.target.closest(".cell");
      if (cell && painting !== null) { cell.classList.toggle("on", painting); updateSummary(); }
    });
    // Touch: pointerover doesn't fire while a finger drags, so track the point instead.
    grid.addEventListener("pointermove", (e) => {
      if (painting === null || e.pointerType === "mouse") return;
      const cell = document.elementFromPoint(e.clientX, e.clientY)?.closest?.(".cell");
      if (cell) { cell.classList.toggle("on", painting); updateSummary(); }
    });
    window.addEventListener("pointerup", () => { painting = null; });
  }

  function loadGrid(blocks) {
    document.querySelectorAll("#week .cell").forEach((c) => {
      const d = Number(c.dataset.day), t = Number(c.dataset.t);
      c.classList.toggle("on", blocks.some((b) => b.day === d && b.start <= t && b.end >= t + SLOT));
    });
    updateSummary();
  }

  function gridBlocks() {
    const blocks = [];
    for (let d = 1; d <= 7; d++) {
      let start = null;
      for (let t = GRID_START; t <= GRID_END; t += SLOT) {
        const on = t < GRID_END && document.querySelector(`#week .cell[data-day="${d}"][data-t="${t}"]`).classList.contains("on");
        if (on && start === null) start = t;
        if (!on && start !== null) { blocks.push({ day: d, start, end: t }); start = null; }
      }
    }
    return blocks;
  }

  function updateSummary() {
    const total = gridBlocks().reduce((s, b) => s + b.end - b.start, 0);
    $("#free-summary").textContent = `${hours(total)} free per week selected`;
  }

  $("#save-free").addEventListener("click", () => run(async () => {
    profile = await api("/api/me/availability", { method: "PUT", body: { blocks: gridBlocks() } });
    loadGrid(profile.availability);
  }, "Free time saved."));
  $("#clear-free").addEventListener("click", () => loadGrid([]));

  // --------------------------------------------------------------------- init

  window.addEventListener("hashchange", route);
  route();
})();
