// Desku UI kit: the live parts of pages Desku makes (injected with kit.css by Pages.ts withKit).
// Everything is driven by data- attributes, so Desku only writes HTML. Pages are sandboxed and
// can't fetch, so all of this works from the data baked into the page.
(() => {
  const $$ = (sel) => [...document.querySelectorAll(sel)];
  const pad = (n) => String(n).padStart(2, "0");

  // Cards rise in one after another.
  $$(".hero, .card, .pass, .stat, .grid > *, .note").forEach((el, i) => (el.style.animationDelay = `${Math.min(i, 10) * 60}ms`));

  // <time data-at="ISO" data-format="time|date|datetime|weekday|relative">
  const rel = (ms) => {
    const fmt = new Intl.RelativeTimeFormat(undefined, { numeric: "auto" });
    const m = Math.round(ms / 60000), h = Math.round(ms / 3600000), d = Math.round(ms / 86400000);
    if (Math.abs(m) < 60) return fmt.format(m, "minute");
    if (Math.abs(h) < 36) return fmt.format(h, "hour");
    return fmt.format(d, "day");
  };
  const FORMATS = {
    time: { hour: "numeric", minute: "2-digit" },
    date: { weekday: "short", month: "short", day: "numeric" },
    weekday: { weekday: "long" },
    datetime: { weekday: "short", month: "short", day: "numeric", hour: "numeric", minute: "2-digit" },
  };
  function times() {
    for (const el of $$("time[data-at]")) {
      const at = Date.parse(el.dataset.at);
      if (Number.isNaN(at)) continue;
      const f = el.dataset.format || "datetime";
      const tz = el.dataset.tz; // e.g. "America/New_York" to show the destination's local time
      el.textContent = f === "relative" ? rel(at - Date.now()) : new Intl.DateTimeFormat(undefined, { ...FORMATS[f], ...(tz && { timeZone: tz }) }).format(at);
    }
  }

  // <div class="countdown" data-to="ISO" data-label="Boarding in" data-done="Boarding now">
  function countdowns() {
    for (const el of $$(".countdown[data-to]")) {
      const to = Date.parse(el.dataset.to);
      if (Number.isNaN(to)) continue;
      let left = Math.max(0, Math.floor((to - Date.now()) / 1000));
      const parts = [["days", Math.floor(left / 86400)], ["hrs", Math.floor((left % 86400) / 3600)], ["min", Math.floor((left % 3600) / 60)], ["sec", left % 60]];
      const shown = parts[0][1] > 0 ? parts.slice(0, 3) : parts.slice(1);
      const label = left === 0 ? el.dataset.done || "Now" : el.dataset.label || "Starts in";
      el.classList.toggle("dk-past", left === 0);
      el.innerHTML = `<div class="dk-label"></div><div class="dk-units">${shown.map(([u, v]) => `<div class="dk-unit"><b>${pad(v)}</b><small>${u}</small></div>`).join("")}</div>`;
      el.firstChild.textContent = label;
    }
  }

  // <div class="route" data-depart="ISO" data-arrive="ISO"> with two <div> ends: an arc between
  // them, and a plane that sits at the start, flies along it in real time, and lands at the end.
  function routes() {
    for (const el of $$(".route")) {
      const ends = [...el.children].filter((c) => c.tagName === "DIV");
      if (ends.length < 2) continue;
      let svg = el.querySelector("svg.dk-arc");
      if (!svg) {
        svg = document.createElementNS("http://www.w3.org/2000/svg", "svg");
        svg.setAttribute("class", "dk-arc");
        svg.setAttribute("viewBox", "0 0 200 70");
        svg.setAttribute("preserveAspectRatio", "xMidYMid meet");
        svg.setAttribute("aria-hidden", "true");
        svg.innerHTML = `<path d="M6 62 Q100 -10 194 62" fill="none" stroke="rgba(156,203,255,.45)" stroke-width="2" stroke-dasharray="4 6" vector-effect="non-scaling-stroke"/>
          <path class="dk-flown" d="M6 62 Q100 -10 194 62" fill="none" stroke="#9ccbff" stroke-width="2.5" vector-effect="non-scaling-stroke" pathLength="1" stroke-dasharray="1 1" stroke-dashoffset="1"/>
          <circle cx="6" cy="62" r="4" fill="#9ccbff"/><circle cx="194" cy="62" r="4" fill="#9ccbff"/>
          <g class="dk-plane"><path d="M-9 0 L9 0 M2 -7 L6 0 L2 7 M-6 -3 L-8 0 L-6 3" stroke="#eef1f5" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round" fill="none" vector-effect="non-scaling-stroke"/></g>`;
        ends[0].after(svg);
      }
      const dep = Date.parse(el.dataset.depart), arr = Date.parse(el.dataset.arrive);
      const t = Number.isNaN(dep) || Number.isNaN(arr) || arr <= dep ? 0 : Math.min(1, Math.max(0, (Date.now() - dep) / (arr - dep)));
      // Point and heading on the quadratic curve P0(6,62) C(100,-10) P2(194,62).
      const x = (1 - t) ** 2 * 6 + 2 * (1 - t) * t * 100 + t * t * 194;
      const y = (1 - t) ** 2 * 62 + 2 * (1 - t) * t * -10 + t * t * 62;
      const dx = 2 * (1 - t) * 94 + 2 * t * 94, dy = 2 * (1 - t) * -72 + 2 * t * 72;
      svg.querySelector(".dk-plane").setAttribute("transform", `translate(${x} ${y}) rotate(${(Math.atan2(dy, dx) * 180) / Math.PI})`);
      svg.querySelector(".dk-flown").setAttribute("stroke-dashoffset", String(1 - t));
    }
  }

  // <div class="progress" data-value="0.4"> (0 to 1)
  for (const el of $$(".progress[data-value]")) {
    const bar = el.querySelector("i") || el.appendChild(document.createElement("i"));
    requestAnimationFrame(() => (bar.style.width = `${Math.min(1, Math.max(0, Number(el.dataset.value) || 0)) * 100}%`));
  }

  const tick = () => { times(); countdowns(); routes(); };
  tick();
  setInterval(tick, 1000);
})();
