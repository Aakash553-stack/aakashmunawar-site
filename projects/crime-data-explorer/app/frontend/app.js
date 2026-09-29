// Chicago Crime Explorer: filters -> API -> Chart.js charts + incident table.
// Plain browser JavaScript, no build step. All requests are relative to the
// page (api/...), so the dashboard works at a domain root or under a path.

(() => {
  "use strict";

  const COLORS = {
    text: "#EEF2FC",
    muted: "#95A2C6",
    accent: "#4C8DFF",
    accentLight: "rgba(76,141,255,0.35)",
    orange: "#E8923A",
    grid: "rgba(76,141,255,0.12)",
  };
  // Distinct, dark-background-friendly colors for crime categories.
  const CATEGORY_COLORS = ["#4C8DFF", "#E8923A", "#3FB8A5", "#C678DD", "#E5C07B",
                           "#E06C75", "#7FB2FF", "#98C379", "#B0B7C9", "#56B6C2"];
  const ARREST_MIN_INCIDENTS = 100;
  const PAGE_SIZE = 25;

  const form = document.getElementById("filters");
  const statusEl = document.getElementById("status");
  const charts = {};
  let meta = null;
  let page = 1;
  let tableController = null;   // table and charts cancel stale requests separately,
  let chartController = null;   // so paging never interrupts a chart refresh

  // ------------------------------------------------------------------ setup

  Chart.defaults.color = COLORS.muted;
  Chart.defaults.font.family = '"IBM Plex Mono", ui-monospace, monospace';
  Chart.defaults.font.size = 11;
  Chart.defaults.borderColor = COLORS.grid;
  Chart.defaults.maintainAspectRatio = false;
  Chart.defaults.animation.duration = 300;
  Chart.defaults.plugins.legend.labels.boxWidth = 12;
  Chart.defaults.plugins.tooltip.backgroundColor = "#0E1424";
  Chart.defaults.plugins.tooltip.borderColor = COLORS.accentLight;
  Chart.defaults.plugins.tooltip.borderWidth = 1;

  const fmt = (n) => (n ?? 0).toLocaleString("en-US");
  const pctFmt = (n) => (n === null || n === undefined ? "–" : `${n > 0 ? "+" : ""}${n}%`);

  function api(path, params, signal) {
    const url = new URL(`api/${path}`, document.baseURI);
    for (const [k, v] of Object.entries(params)) {
      if (v !== "" && v !== null && v !== undefined) url.searchParams.set(k, v);
    }
    return fetch(url, { signal }).then(async (r) => {
      if (!r.ok) {
        const body = await r.json().catch(() => ({}));
        const detail = typeof body.detail === "string" ? body.detail : `HTTP ${r.status}`;
        throw new Error(detail);
      }
      return r.json();
    });
  }

  function currentFilters() {
    const data = new FormData(form);
    const f = {};
    for (const key of ["start", "end", "category", "district", "community_area", "arrest", "domestic"]) {
      f[key] = data.get(key) || "";
    }
    return f;
  }

  function applyFiltersToForm(params) {
    for (const [key, value] of params.entries()) {
      const el = form.elements[key];
      if (!el) continue;
      if (el instanceof RadioNodeList) {
        for (const radio of el) radio.checked = radio.value === value;
      } else {
        el.value = value;
      }
    }
  }

  function syncUrl(filters) {
    const params = new URLSearchParams();
    for (const [k, v] of Object.entries(filters)) if (v) params.set(k, v);
    if (page > 1) params.set("page", page);
    const qs = params.toString();
    history.replaceState(null, "", qs ? `?${qs}` : location.pathname);
  }

  function fillSelect(select, items, label) {
    for (const item of items) {
      const opt = document.createElement("option");
      opt.value = item.id;
      opt.textContent = label(item);
      select.appendChild(opt);
    }
  }

  function title(s) {
    return (s || "").toLowerCase().replace(/\b\w/g, (c) => c.toUpperCase());
  }

  function districtLabel(id, name) {
    return name ? `${id} (${name})` : `${id}`;
  }

  // ----------------------------------------------------------------- charts

  function renderChart(key, config, isEmpty) {
    const canvas = document.getElementById(`chart-${key}`);
    const box = canvas.parentElement;
    box.querySelector(".empty-note")?.remove();
    if (charts[key]) {
      charts[key].data = config.data;
      charts[key].options = config.options;
      charts[key].update();
    } else {
      charts[key] = new Chart(canvas, config);
    }
    if (isEmpty) {
      const note = document.createElement("div");
      note.className = "empty-note";
      note.textContent = "No incidents match these filters.";
      box.appendChild(note);
    }
  }

  const axisNumber = { ticks: { callback: (v) => fmt(v) } };
  const axisPercent = { ticks: { callback: (v) => `${v}%` } };

  function drawHour(rows) {
    renderChart("hour", {
      type: "bar",
      data: {
        labels: rows.map((r) => String(r.hour).padStart(2, "0")),
        datasets: [
          { label: "Recorded time", data: rows.map((r) => r.exact_time), backgroundColor: COLORS.accent },
          { label: "Exactly 00:00 / 12:00 (likely placeholder)", data: rows.map((r) => r.placeholder_time),
            backgroundColor: COLORS.accentLight },
        ],
      },
      options: {
        scales: { x: { stacked: true, title: { display: true, text: "Hour of day" }, grid: { display: false } },
                  y: { stacked: true, beginAtZero: true, ...axisNumber } },
        plugins: { tooltip: { mode: "index", intersect: false } },
      },
    }, rows.every((r) => r.incidents === 0));
  }

  function drawMonthly(rows) {
    renderChart("month", {
      type: "line",
      data: {
        labels: rows.map((r) => r.month),
        datasets: [{
          label: "Incidents",
          data: rows.map((r) => r.incidents),
          borderColor: COLORS.accent,
          backgroundColor: "rgba(76,141,255,0.12)",
          fill: true,
          tension: 0.25,
          pointRadius: 4,
          pointBackgroundColor: rows.map((r) => (r.partial ? "transparent" : COLORS.accent)),
          pointBorderColor: COLORS.accent,
        }],
      },
      options: {
        scales: { y: { beginAtZero: true, ...axisNumber }, x: { grid: { display: false } } },
        plugins: {
          legend: { display: false },
          tooltip: {
            callbacks: {
              title: (items) => {
                const r = rows[items[0].dataIndex];
                return r.partial ? `${r.month} (partial month)` : r.month;
              },
              label: (item) => `Incidents: ${fmt(item.raw)}`,
              afterLabel: (item) => {
                const r = rows[item.dataIndex];
                return [`vs. previous month: ${pctFmt(r.mom_pct_change)}`,
                        `vs. same month last year: ${pctFmt(r.yoy_pct_change)}`];
              },
            },
          },
        },
      },
    }, rows.every((r) => r.incidents === 0));
  }

  function drawDayOfWeek(rows) {
    const max = Math.max(...rows.map((r) => r.avg_incidents));
    renderChart("dow", {
      type: "bar",
      data: {
        labels: rows.map((r) => r.day_of_week),
        datasets: [{
          label: "Average incidents per day",
          data: rows.map((r) => r.avg_incidents),
          backgroundColor: rows.map((r) => (r.avg_incidents === max && max > 0 ? COLORS.orange : COLORS.accent)),
        }],
      },
      options: {
        scales: { y: { beginAtZero: true }, x: { grid: { display: false } } },
        plugins: {
          legend: { display: false },
          tooltip: {
            callbacks: {
              afterLabel: (item) => {
                const r = rows[item.dataIndex];
                return `Range: ${fmt(r.min_incidents)}–${fmt(r.max_incidents)} over ${r.days} days`;
              },
            },
          },
        },
      },
    }, max === 0);
  }

  function drawArrest(rows) {
    document.getElementById("arrest-note").textContent =
      `Share of incidents with an arrest recorded, for categories with at least ${ARREST_MIN_INCIDENTS} incidents in the current selection.`;
    renderChart("arrest", {
      type: "bar",
      data: {
        labels: rows.map((r) => title(r.category)),
        datasets: [{ label: "Arrest rate", data: rows.map((r) => r.arrest_rate_pct), backgroundColor: COLORS.accent }],
      },
      options: {
        indexAxis: "y",
        scales: { x: { beginAtZero: true, max: 100, ...axisPercent }, y: { grid: { display: false }, ticks: { autoSkip: false } } },
        plugins: {
          legend: { display: false },
          tooltip: {
            callbacks: {
              label: (item) => {
                const r = rows[item.dataIndex];
                return `${r.arrest_rate_pct}% (${fmt(r.arrests)} of ${fmt(r.incidents)})`;
              },
            },
          },
        },
      },
    }, rows.length === 0);
  }

  function drawDistricts(rows) {
    const districts = [...new Map(rows.map((r) => [r.district_id, r.district])).entries()];
    const categories = [...new Set(rows.map((r) => r.category))];
    const lookup = new Map(rows.map((r) => [`${r.district_id}|${r.category}`, r]));
    renderChart("district", {
      type: "bar",
      data: {
        labels: districts.map(([id, name]) => districtLabel(id, name)),
        datasets: categories.map((cat, i) => ({
          label: title(cat),
          data: districts.map(([id]) => lookup.get(`${id}|${cat}`)?.pct_of_district ?? null),
          backgroundColor: CATEGORY_COLORS[i % CATEGORY_COLORS.length],
        })),
      },
      options: {
        indexAxis: "y",
        scales: {
          x: { stacked: true, beginAtZero: true, ...axisPercent,
               title: { display: true, text: "% of the district's incidents (top 3 categories)" } },
          y: { stacked: true, grid: { display: false }, ticks: { autoSkip: false }, title: { display: true, text: "Police district" } },
        },
        plugins: {
          legend: { position: "bottom" },
          tooltip: {
            filter: (item) => item.raw !== null,
            callbacks: {
              label: (item) => {
                const id = districts[item.dataIndex][0];
                const r = lookup.get(`${id}|${categories[item.datasetIndex]}`);
                return `#${r.rank} ${title(r.category)}: ${r.pct_of_district}% (${fmt(r.incidents)})`;
              },
            },
          },
        },
      },
    }, rows.length === 0);
  }

  function drawAreas(rows) {
    renderChart("area", {
      type: "bar",
      data: {
        labels: rows.map((r) => title(r.community_area)),
        datasets: [
          { label: "Not domestic", data: rows.map((r) => r.incidents - r.domestic), backgroundColor: COLORS.accentLight },
          { label: "Domestic-related", data: rows.map((r) => r.domestic), backgroundColor: COLORS.orange },
        ],
      },
      options: {
        indexAxis: "y",
        scales: { x: { stacked: true, beginAtZero: true, ...axisNumber }, y: { stacked: true, grid: { display: false }, ticks: { autoSkip: false } } },
        plugins: {
          legend: { position: "bottom" },
          tooltip: {
            mode: "index",
            intersect: false,
            callbacks: {
              footer: (items) => {
                const r = rows[items[0].dataIndex];
                return `${fmt(r.incidents)} total, ${r.domestic_pct}% domestic`;
              },
            },
          },
        },
      },
    }, rows.length === 0);
  }

  // ------------------------------------------------------------------ table

  function cell(row, text, cls) {
    const td = document.createElement("td");
    td.textContent = text;
    if (cls) td.className = cls;
    row.appendChild(td);
  }

  function drawTable(data) {
    const tbody = document.getElementById("rows");
    tbody.replaceChildren();
    for (const r of data.items) {
      const tr = document.createElement("tr");
      cell(tr, r.occurred_at.slice(0, 16));
      cell(tr, title(r.category));
      cell(tr, title(r.description), "desc");
      cell(tr, title(r.location_type) || "–");
      cell(tr, r.block);
      cell(tr, r.district);
      cell(tr, title(r.community_area) || "–");
      cell(tr, r.arrest ? "Yes" : "No", r.arrest ? "yes" : "no");
      cell(tr, r.domestic ? "Yes" : "No", r.domestic ? "yes" : "no");
      tbody.appendChild(tr);
    }
    if (!data.items.length) {
      const tr = document.createElement("tr");
      const td = document.createElement("td");
      td.colSpan = 9;
      td.textContent = "No incidents match these filters.";
      tr.appendChild(td);
      tbody.appendChild(tr);
    }
    document.getElementById("page-info").textContent =
      `Page ${fmt(data.page)} of ${fmt(data.pages)}`;
    document.getElementById("table-note").textContent =
      `${fmt(data.total)} matching incidents, newest first.`;
    document.getElementById("prev").disabled = data.page <= 1;
    document.getElementById("next").disabled = data.page >= data.pages;
  }

  // ---------------------------------------------------------------- refresh

  async function refresh({ tableOnly = false } = {}) {
    const filters = currentFilters();
    if (filters.start && filters.end && filters.start > filters.end) {
      statusEl.textContent = "The start date is after the end date.";
      statusEl.classList.add("error");
      return;
    }
    syncUrl(filters);
    tableController?.abort();
    tableController = new AbortController();
    if (!tableOnly) {
      chartController?.abort();
      chartController = new AbortController();
    }
    const tableSignal = tableController.signal;
    const signal = chartController?.signal;

    document.body.classList.add("loading");
    statusEl.classList.remove("error");
    statusEl.textContent = "Loading…";

    const table = api("incidents", { ...filters, page, page_size: PAGE_SIZE }, tableSignal).then(drawTable);
    const jobs = [table];
    if (!tableOnly) {
      jobs.push(
        api("stats/summary", filters, signal).then((s) => {
          document.getElementById("kpi-incidents").textContent = fmt(s.incidents);
          document.getElementById("kpi-arrest").textContent = s.incidents ? `${s.arrest_rate_pct}%` : "–";
          document.getElementById("kpi-domestic").textContent = s.incidents ? `${s.domestic_pct}%` : "–";
        }),
        api("stats/incidents-by-hour", filters, signal).then(drawHour),
        api("stats/monthly-trend", filters, signal).then(drawMonthly),
        api("stats/day-of-week", filters, signal).then(drawDayOfWeek),
        api("stats/arrest-rate-by-category", { ...filters, min_incidents: ARREST_MIN_INCIDENTS }, signal).then(drawArrest),
        api("stats/top-categories-by-district", filters, signal).then(drawDistricts),
        api("stats/community-area-domestic-share", filters, signal).then(drawAreas),
      );
    }

    try {
      await Promise.all(jobs);
      statusEl.textContent = "";
    } catch (err) {
      if (err.name === "AbortError") return;   // superseded by a newer request
      statusEl.textContent = `Couldn't load data: ${err.message}`;
      statusEl.classList.add("error");
    } finally {
      if (!tableSignal.aborted && !(signal?.aborted && !tableOnly)) document.body.classList.remove("loading");
    }
  }

  let debounce;
  function onFiltersChanged() {
    clearTimeout(debounce);
    debounce = setTimeout(() => { page = 1; refresh(); }, 200);
  }

  // ------------------------------------------------------------------- init

  async function init() {
    try {
      meta = await api("meta", {});
    } catch (err) {
      statusEl.textContent = `Couldn't reach the API: ${err.message}`;
      statusEl.classList.add("error");
      return;
    }

    document.getElementById("total-note").textContent =
      `${fmt(meta.total_incidents)} Chicago Police Department incident reports from ${meta.first_date} to ${meta.last_date}`;
    for (const name of ["start", "end"]) {
      form.elements[name].min = meta.first_date;
      form.elements[name].max = meta.last_date;
    }
    form.elements.start.placeholder = meta.first_date;
    form.elements.end.placeholder = meta.last_date;
    fillSelect(form.elements.category, meta.categories, (c) => `${title(c.name)} (${fmt(c.incidents)})`);
    fillSelect(form.elements.district, meta.districts, (d) => `District ${districtLabel(d.id, d.name)}`);
    fillSelect(form.elements.community_area, meta.community_areas, (a) => title(a.name));

    const params = new URLSearchParams(location.search);
    applyFiltersToForm(params);
    page = Math.max(1, parseInt(params.get("page"), 10) || 1);

    form.addEventListener("input", onFiltersChanged);
    form.addEventListener("change", onFiltersChanged);
    form.addEventListener("reset", () => setTimeout(onFiltersChanged, 0));
    form.addEventListener("submit", (e) => e.preventDefault());
    document.getElementById("prev").addEventListener("click", () => { page -= 1; refresh({ tableOnly: true }); });
    document.getElementById("next").addEventListener("click", () => { page += 1; refresh({ tableOnly: true }); });

    refresh();
  }

  init();
})();
