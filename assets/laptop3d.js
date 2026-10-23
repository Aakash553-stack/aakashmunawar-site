// The 3D laptop in the hero.
//
// Everything is modelled in code (no downloaded model): an aluminium base and
// a lid that turns on a real hinge axis, individually placed keys with printed
// legends, a glass trackpad, and a black glass bezel around the display. The
// display's contents are live HTML, so its controls are real,
// focusable buttons, mapped onto the panel with a perspective matrix3d. Lighting is three's RoomEnvironment for reflections plus
// a shadow-casting key light and a light from the display itself.
//
// Rendering is on demand: frames are drawn only while something moves (the lid,
// the display powering on, the pointer tilt), so an idle hero costs nothing.
import * as THREE from "three";
import { RoundedBoxGeometry } from "./three/addons/RoundedBoxGeometry.js";
import { RoomEnvironment } from "./three/addons/RoomEnvironment.js";

const DEG = Math.PI / 180;

// Proportions of a 13–14" laptop; 1 unit ≈ 10 cm.
const W = 3.04, D = 2.14;            // footprint
const BASE_H = 0.085, LID_T = 0.05;  // thicknesses
const GAP = 0.012;                   // seam between the closed lid and the base
const CORNER = 0.14;                 // corner radius seen from above
const OPEN = 108 * DEG;              // fully open angle
const DISP_W = 2.84, DISP_H = DISP_W / 1.6, TOP_BEZEL = 0.11;  // 16:10 panel
const SCREEN_PX = 960;               // CSS width of the HTML on the display
const U = 0.18, KEY_GAP = 0.028;     // key pitch and the gap between caps
const KEY_TOP = BASE_H + 0.006;      // keys stand just proud of the deck
const YAW = -0.3;                    // turned slightly toward the hero text
const ELEVATION = 20 * DEG;          // camera height above the desk

const easeInOut = (t) => (t < 0.5 ? 4 * t * t * t : 1 - Math.pow(-2 * t + 2, 3) / 2);

function roundedRect(w, h, r) {
  const s = new THREE.Shape(), x = -w / 2, y = -h / 2;
  s.moveTo(x + r, y);
  s.lineTo(x + w - r, y); s.absarc(x + w - r, y + r, r, -Math.PI / 2, 0, false);
  s.lineTo(x + w, y + h - r); s.absarc(x + w - r, y + h - r, r, 0, Math.PI / 2, false);
  s.lineTo(x + r, y + h); s.absarc(x + r, y + h - r, r, Math.PI / 2, Math.PI, false);
  s.lineTo(x, y + r); s.absarc(x + r, y + r, r, Math.PI, Math.PI * 1.5, false);
  return s;
}

// ExtrudeGeometry is flat-shaded; weld duplicate vertices so the bevels shade smoothly.
function smooth(geo) {
  const pos = geo.attributes.position, uv = geo.attributes.uv;
  const seen = new Map(), index = [], P = [], UV = [];
  for (let i = 0; i < pos.count; i++) {
    const key = `${pos.getX(i).toFixed(4)},${pos.getY(i).toFixed(4)},${pos.getZ(i).toFixed(4)}`;
    let j = seen.get(key);
    if (j === undefined) {
      j = P.length / 3; seen.set(key, j);
      P.push(pos.getX(i), pos.getY(i), pos.getZ(i)); UV.push(uv.getX(i), uv.getY(i));
    }
    index.push(j);
  }
  const out = new THREE.BufferGeometry();
  out.setAttribute("position", new THREE.Float32BufferAttribute(P, 3));
  out.setAttribute("uv", new THREE.Float32BufferAttribute(UV, 2));
  out.setIndex(index);
  out.computeVertexNormals();
  geo.dispose();
  return out;
}

// A slab with rounded corners and softened edges, footprint w×d centred on the
// origin, from y = 0 up to y = h.
function slab(w, d, h, r, bevel) {
  const g = new THREE.ExtrudeGeometry(roundedRect(w - 2 * bevel, d - 2 * bevel, r - bevel), {
    depth: h - 2 * bevel, bevelEnabled: true, bevelThickness: bevel, bevelSize: bevel,
    bevelSegments: 6, curveSegments: 18,
  });
  g.rotateX(-Math.PI / 2);
  g.translate(0, bevel, 0);
  return smooth(g);
}

// A flat rounded rectangle lying on the XZ plane, facing up (+y) or down.
function plate(w, d, r, faceDown = false) {
  const g = new THREE.ShapeGeometry(roundedRect(w, d, r), 12);
  g.rotateX(faceDown ? Math.PI / 2 : -Math.PI / 2);
  return g;
}

// Fine horizontal brushing, used as a roughness map on the aluminium.
function brushedTexture() {
  const size = 256, data = new Uint8Array(size * size * 4);
  for (let y = 0; y < size; y++) {
    const row = Math.random();
    for (let x = 0; x < size; x++) {
      const v = Math.min(255, 214 + row * 18 + Math.random() * 8), i = (y * size + x) * 4;
      data[i] = data[i + 1] = data[i + 2] = v; data[i + 3] = 255;
    }
  }
  const t = new THREE.DataTexture(data, size, size);
  t.wrapS = t.wrapT = THREE.RepeatWrapping; t.repeat.set(3, 3); t.needsUpdate = true;
  return t;
}

// A soft blurred rectangle, for the contact shadow directly under the base.
function contactShadowTexture() {
  const c = document.createElement("canvas"); c.width = c.height = 256;
  const g = c.getContext("2d");
  g.filter = "blur(16px)"; g.fillStyle = "#000";
  g.beginPath(); g.roundRect(34, 40, 188, 176, 22); g.fill();
  const t = new THREE.CanvasTexture(c); t.colorSpace = THREE.SRGBColorSpace;
  return t;
}

// Keyboard rows, back to front. [width in key units, legend]; "ud" is the
// stacked up/down arrow pair.
const ROWS = [
  { h: 0.55, keys: [[1.5, "esc"], ...Array.from({ length: 12 }, (_, i) => [1, `F${i + 1}`]), [1, ""]] },
  { h: 1, keys: [..."`1234567890-=".split("").map((k) => [1, k]), [1.5, "delete"]] },
  { h: 1, keys: [[1.5, "tab"], ..."QWERTYUIOP[]\\".split("").map((k) => [1, k])] },
  { h: 1, keys: [[1.8, "caps"], ..."ASDFGHJKL;'".split("").map((k) => [1, k]), [1.7, "return"]] },
  { h: 1, keys: [[2.3, "shift"], ..."ZXCVBNM,./".split("").map((k) => [1, k]), [2.2, "shift"]] },
  { h: 1, keys: [[1, "fn"], [1, "ctrl"], [1, "alt"], [1.25, ""], [5, ""], [1.25, ""], [1, "alt"], [1, "◀"], "ud", [1, "▶"]] },
];

function buildKeyboard(group, keyMat, kbBackZ) {
  const keys = [];
  let z = kbBackZ;
  for (const row of ROWS) {
    const rowD = row.h * U;
    let x = -14.5 * U / 2;
    for (const k of row.keys) {
      if (k === "ud") {
        const half = (rowD - KEY_GAP) / 2;
        keys.push({ x: x + U / 2, z: z + half / 2, w: U - KEY_GAP, d: half - KEY_GAP / 4, label: "▲" });
        keys.push({ x: x + U / 2, z: z + half * 1.5 + KEY_GAP / 4, w: U - KEY_GAP, d: half - KEY_GAP / 4, label: "▼" });
        x += U; continue;
      }
      const [w, label] = k;
      keys.push({ x: x + (w * U) / 2, z: z + rowD / 2, w: w * U - KEY_GAP, d: rowD - KEY_GAP, label });
      x += w * U;
    }
    z += rowD;
  }

  // One instanced mesh per cap size, so every cap keeps its true corner radius.
  const bySize = new Map();
  for (const k of keys) {
    const id = `${k.w.toFixed(3)}x${k.d.toFixed(3)}`;
    if (!bySize.has(id)) bySize.set(id, []);
    bySize.get(id).push(k);
  }
  const m = new THREE.Matrix4();
  for (const list of bySize.values()) {
    const geo = new RoundedBoxGeometry(list[0].w, 0.022, list[0].d, 2, 0.009);
    const mesh = new THREE.InstancedMesh(geo, keyMat, list.length);
    list.forEach((k, i) => mesh.setMatrixAt(i, m.makeTranslation(k.x, KEY_TOP - 0.011, k.z)));
    mesh.castShadow = false; mesh.receiveShadow = true;
    group.add(mesh);
  }

  // Printed legends: one small quad per labelled key, all in a single draw call.
  const labels = [...new Set(keys.map((k) => k.label).filter(Boolean))];
  const cell = 64, cols = 16, rows = Math.ceil(labels.length / cols);
  const canvas = document.createElement("canvas");
  canvas.width = cols * cell; canvas.height = rows * cell;
  const g = canvas.getContext("2d");
  g.fillStyle = "#fff"; g.textAlign = "center"; g.textBaseline = "middle";
  labels.forEach((s, i) => {
    g.font = `500 ${s.length > 2 ? 15 : 24}px system-ui, -apple-system, "Segoe UI", sans-serif`;
    g.fillText(s, (i % cols) * cell + cell / 2, Math.floor(i / cols) * cell + cell / 2 + 1);
  });
  const tex = new THREE.CanvasTexture(canvas);
  tex.colorSpace = THREE.SRGBColorSpace; tex.anisotropy = 8;

  const P = [], UV = [], I = [];
  for (const k of keys) {
    if (!k.label) continue;
    const n = labels.indexOf(k.label), col = n % cols, row = Math.floor(n / cols);
    const s = Math.min(k.w, k.d) * 0.86, y = KEY_TOP + 0.0006;
    const u0 = col / cols, u1 = (col + 1) / cols, v1 = 1 - row / rows, v0 = 1 - (row + 1) / rows;
    const b = P.length / 3;
    P.push(k.x - s / 2, y, k.z - s / 2, k.x + s / 2, y, k.z - s / 2, k.x - s / 2, y, k.z + s / 2, k.x + s / 2, y, k.z + s / 2);
    UV.push(u0, v1, u1, v1, u0, v0, u1, v0);
    I.push(b, b + 2, b + 1, b + 1, b + 2, b + 3);
  }
  const lg = new THREE.BufferGeometry();
  lg.setAttribute("position", new THREE.Float32BufferAttribute(P, 3));
  lg.setAttribute("uv", new THREE.Float32BufferAttribute(UV, 2));
  lg.setIndex(I);
  const legendMat = new THREE.MeshBasicMaterial({ map: tex, transparent: true, opacity: 0.42, color: 0xc9d0dc, depthWrite: false, toneMapped: false });
  group.add(new THREE.Mesh(lg, legendMat));
  return legendMat;
}

export async function init(hub, { reduceMotion = false } = {}) {
  const stage = hub.querySelector("[data-stage]");
  const screenEl = hub.querySelector("#laptop-screen");
  const toggle = hub.querySelector(".lid-toggle");
  const toggleLabel = toggle.querySelector(".lid-toggle-label");
  const panel = hub.querySelector("#hub-panel");
  const status = hub.querySelector("[data-hub-status]");
  const small = matchMedia("(max-width: 599px)");

  // ---------- Renderers ----------
  const renderer = new THREE.WebGLRenderer({ antialias: true, alpha: true, powerPreference: "high-performance" });
  renderer.setPixelRatio(Math.min(devicePixelRatio || 1, 2));
  renderer.outputColorSpace = THREE.SRGBColorSpace;
  renderer.toneMapping = THREE.ACESFilmicToneMapping;
  renderer.toneMappingExposure = 1.05;
  renderer.shadowMap.enabled = true;
  renderer.shadowMap.type = THREE.PCFSoftShadowMap;
  renderer.domElement.className = "stage-gl";
  renderer.domElement.setAttribute("aria-hidden", "true");

  const overlay = document.createElement("div");
  overlay.className = "stage-css";
  stage.append(renderer.domElement, overlay);

  const scene = new THREE.Scene();
  const pmrem = new THREE.PMREMGenerator(renderer);
  scene.environment = pmrem.fromScene(new RoomEnvironment(), 0.04).texture;
  scene.environmentIntensity = 0.85;
  pmrem.dispose();

  const camera = new THREE.PerspectiveCamera(22, 1, 0.1, 60);

  // ---------- Materials ----------
  const alu = new THREE.MeshPhysicalMaterial({
    color: 0x80848c, metalness: 1, roughness: 0.42, roughnessMap: brushedTexture(),
  });
  const darkAlu = new THREE.MeshPhysicalMaterial({ color: 0x3c4047, metalness: 1, roughness: 0.5 });
  const keyMat = new THREE.MeshPhysicalMaterial({ color: 0x131417, roughness: 0.6, metalness: 0, clearcoat: 0.15, clearcoatRoughness: 0.6 });
  const wellMat = new THREE.MeshStandardMaterial({ color: 0x0b0c0f, roughness: 0.85 });
  const glass = new THREE.MeshPhysicalMaterial({ color: 0x050608, roughness: 0.06, metalness: 0, clearcoat: 1, clearcoatRoughness: 0.04 });
  const display = new THREE.MeshStandardMaterial({ color: 0x020304, roughness: 0.2, emissive: 0x2a5cad, emissiveIntensity: 0 });
  const padMat = new THREE.MeshPhysicalMaterial({ color: 0x8a8e96, metalness: 0.7, roughness: 0.24, clearcoat: 0.6, clearcoatRoughness: 0.25 });
  const seamMat = new THREE.MeshStandardMaterial({ color: 0x24272c, roughness: 0.7 });

  // ---------- Model ----------
  const tilt = new THREE.Group();          // pointer tilt
  const laptop = new THREE.Group();        // the laptop itself, yawed toward the text
  laptop.rotation.y = YAW;
  tilt.add(laptop);
  scene.add(tilt);

  const base = new THREE.Mesh(slab(W, D, BASE_H, CORNER, 0.024), alu);
  base.castShadow = base.receiveShadow = true;
  laptop.add(base);

  const kbBack = -D / 2 + 0.17, kbDepth = 5.55 * U + 0.06, kbWidth = 14.5 * U + 0.06;
  const well = new THREE.Mesh(plate(kbWidth, kbDepth, 0.05), wellMat);
  well.position.set(0, BASE_H + 0.0006, kbBack + kbDepth / 2);
  well.receiveShadow = true;
  laptop.add(well);
  const legendMat = buildKeyboard(laptop, keyMat, kbBack + 0.03);

  const padZ = kbBack + kbDepth + 0.08 + 0.35;
  const padEdge = new THREE.Mesh(plate(1.39, 0.72, 0.07), seamMat);
  padEdge.position.set(0, BASE_H + 0.0005, padZ);
  const pad = new THREE.Mesh(plate(1.37, 0.70, 0.064), padMat);
  pad.position.set(0, BASE_H + 0.001, padZ);
  pad.receiveShadow = true;
  laptop.add(padEdge, pad);

  // The lid pivots on the hinge line along the back top edge of the base.
  const lid = new THREE.Group();
  lid.position.set(0, BASE_H + GAP, -D / 2);
  laptop.add(lid);

  const lidGeo = slab(W, D, LID_T, CORNER, 0.017);
  lidGeo.translate(0, 0, D / 2);           // runs from the hinge (z = 0) to the top edge (z = D)
  const shell = new THREE.Mesh(lidGeo, alu);
  shell.castShadow = shell.receiveShadow = true;
  lid.add(shell);

  const bezel = new THREE.Mesh(plate(W - 0.05, D - 0.05, CORNER - 0.025, true), glass);
  bezel.position.set(0, -0.0008, D / 2);
  const panelMesh = new THREE.Mesh(new THREE.PlaneGeometry(DISP_W, DISP_H).rotateX(Math.PI / 2), display);
  const displayZ = D - TOP_BEZEL - DISP_H / 2;
  panelMesh.position.set(0, -0.0016, displayZ);
  const camDot = new THREE.Mesh(new THREE.CircleGeometry(0.013, 24).rotateX(Math.PI / 2), new THREE.MeshStandardMaterial({ color: 0x1b1f27, roughness: 0.3 }));
  camDot.position.set(0, -0.0016, D - TOP_BEZEL / 2);
  lid.add(bezel, panelMesh, camDot);

  const hinge = new THREE.Mesh(new THREE.CylinderGeometry(0.034, 0.034, W - 0.7, 32).rotateZ(Math.PI / 2), darkAlu);
  hinge.position.set(0, BASE_H + 0.004, -D / 2 + 0.03);
  hinge.castShadow = true;
  laptop.add(hinge);

  // The display's light, which brightens the keyboard as the screen powers on.
  const screenLight = new THREE.PointLight(0x7fb2ff, 0, 5, 2);
  screenLight.position.set(0, -0.9, displayZ);
  lid.add(screenLight);

  // Desk: shadows only, so the page background shows through.
  const ground = new THREE.Mesh(new THREE.PlaneGeometry(16, 16).rotateX(-Math.PI / 2), new THREE.ShadowMaterial({ color: 0x050a14, opacity: 0.42 }));
  ground.receiveShadow = true;
  tilt.add(ground);
  const contact = new THREE.Mesh(new THREE.PlaneGeometry(W * 1.32, D * 1.32).rotateX(-Math.PI / 2),
    new THREE.MeshBasicMaterial({ map: contactShadowTexture(), transparent: true, opacity: 0.75, depthWrite: false, toneMapped: false }));
  contact.position.y = 0.002;
  laptop.add(contact);

  // ---------- Lights ----------
  scene.add(new THREE.HemisphereLight(0xbcd4ff, 0x0b1220, 0.35));
  const key = new THREE.DirectionalLight(0xffffff, 2.3);
  key.position.set(-3.5, 6.5, 4.5);
  key.castShadow = true;
  key.shadow.mapSize.set(small.matches ? 1024 : 2048, small.matches ? 1024 : 2048);
  Object.assign(key.shadow.camera, { left: -3.4, right: 3.4, top: 3.4, bottom: -3.4, near: 1, far: 20 });
  key.shadow.bias = -0.0004; key.shadow.normalBias = 0.02;
  scene.add(key);
  const rim = new THREE.DirectionalLight(0x69a7ff, 1.8);
  rim.position.set(3.5, 3, -5);
  scene.add(rim);

  // ---------- The HTML display ----------
  // The screen element (960×600 CSS px) is mapped onto the projected corners
  // of the display with a single perspective-correct matrix3d (a homography).
  screenEl.hidden = false;
  screenEl.inert = true;
  overlay.append(screenEl);
  const SCREEN_H = SCREEN_PX / 1.6;
  const corner = new THREE.Vector3();
  function project(sx, sz) {
    corner.set(sx * DISP_W / 2, -0.003, displayZ + sz * DISP_H / 2).applyMatrix4(lid.matrixWorld).project(camera);
    return [((corner.x + 1) / 2) * stage.clientWidth, ((1 - corner.y) / 2) * stage.clientHeight];
  }
  function placeScreen() {
    lid.updateWorldMatrix(true, false);
    // Unit square (0,0) (1,0) (1,1) (0,1) → top-left, top-right, bottom-right, bottom-left.
    const [x0, y0] = project(-1, 1), [x1, y1] = project(1, 1), [x2, y2] = project(1, -1), [x3, y3] = project(-1, -1);
    const dx1 = x1 - x2, dx2 = x3 - x2, dx3 = x0 - x1 + x2 - x3;
    const dy1 = y1 - y2, dy2 = y3 - y2, dy3 = y0 - y1 + y2 - y3;
    const den = dx1 * dy2 - dx2 * dy1;
    const g = (dx3 * dy2 - dx2 * dy3) / den, h = (dx1 * dy3 - dx3 * dy1) / den;
    const a = (x1 - x0 + g * x1) / SCREEN_PX, b = (x3 - x0 + h * x3) / SCREEN_H;
    const d = (y1 - y0 + g * y1) / SCREEN_PX, e = (y3 - y0 + h * y3) / SCREEN_H;
    const m = [a, d, 0, g / SCREEN_PX, b, e, 0, h / SCREEN_H, 0, 0, 1, 0, x0, y0, 0, 1];
    screenEl.style.transform = `matrix3d(${m.map((n) => +n.toFixed(8)).join(",")})`;
  }

  // ---------- State ----------
  const state = { lid: 0, power: 0 };
  const tw = {};
  const pointer = { x: 0, y: 0, tx: 0, ty: 0 };
  let raf = 0, target = "closed", frames = 0;

  function apply() {
    lid.rotation.x = -state.lid;
    display.emissiveIntensity = state.power * 0.9;
    screenLight.intensity = state.power * 2.2;
    legendMat.opacity = 0.42 + state.power * 0.3;
    tilt.rotation.y = pointer.x * 0.09;
    tilt.rotation.x = pointer.y * 0.035;
  }
  function render() { frames++; renderer.render(scene, camera); if (state.lid > OPEN * 0.6) placeScreen(); }
  function kick() { if (!raf) raf = requestAnimationFrame(tick); }
  function tick(now) {
    raf = 0;
    for (const k of Object.keys(tw)) {
      const t = tw[k], p = Math.min(1, (now - t.t0) / t.dur);
      state[k] = t.from + (t.to - t.from) * easeInOut(p);
      if (p === 1) { delete tw[k]; t.done && t.done(); }
    }
    pointer.x += (pointer.tx - pointer.x) * 0.09;
    pointer.y += (pointer.ty - pointer.y) * 0.09;
    const settling = Math.abs(pointer.tx - pointer.x) + Math.abs(pointer.ty - pointer.y) > 1e-4;
    apply(); render();
    if (settling || Object.keys(tw).length) kick();
  }
  function tween(k, to, dur, done) {
    if (reduceMotion || dur === 0) { delete tw[k]; state[k] = to; apply(); render(); done && done(); return; }
    tw[k] = { from: state[k], to, dur: Math.max(1, dur * Math.abs(to - state[k]) / (k === "lid" ? OPEN : 1)), t0: performance.now(), done };
    kick();
  }

  // ---------- Framing ----------
  // Fit the *open* laptop into a fixed share of the stage, so the cards and
  // lines around it line up the same way at every size.
  const box = new THREE.Box3(), v = new THREE.Vector3();
  function ndcBounds(objs) {
    box.makeEmpty();
    for (const o of objs) box.union(new THREE.Box3().setFromObject(o, true));
    let x0 = Infinity, x1 = -Infinity, y0 = Infinity, y1 = -Infinity;
    for (let i = 0; i < 8; i++) {
      v.set(i & 1 ? box.max.x : box.min.x, i & 2 ? box.max.y : box.min.y, i & 4 ? box.max.z : box.min.z).project(camera);
      x0 = Math.min(x0, v.x); x1 = Math.max(x1, v.x); y0 = Math.min(y0, v.y); y1 = Math.max(y1, v.y);
    }
    return { x0, x1, y0, y1 };
  }
  function frame() {
    const w = stage.clientWidth, h = stage.clientHeight;
    if (!w || !h) return;
    renderer.setSize(w, h, false);
    camera.aspect = w / h;
    camera.zoom = 1;
    camera.clearViewOffset();
    const dist = 12, aim = new THREE.Vector3(0, 0.9, -0.3);
    camera.position.set(0, Math.sin(ELEVATION) * dist, Math.cos(ELEVATION) * dist).add(aim);
    camera.lookAt(aim);
    camera.updateProjectionMatrix();

    const saved = { lid: state.lid, px: pointer.x, py: pointer.y };
    state.lid = OPEN; pointer.x = pointer.y = 0; apply();
    scene.updateMatrixWorld(true);
    camera.updateMatrixWorld(true);
    const b = ndcBounds([base, shell]);

    // Share of the stage height the open laptop should fill, and where its centre sits.
    const fill = small.matches ? 0.86 : 0.55, centreY = small.matches ? 0.5 : 0.495;
    camera.zoom = (fill * 2) / (b.y1 - b.y0);
    const cx = ((b.x0 + b.x1) / 2) * camera.zoom, cy = ((b.y0 + b.y1) / 2) * camera.zoom;
    const wantY = 1 - 2 * centreY;
    camera.setViewOffset(w, h, (cx * w) / 2, (-(cy - wantY) * h) / 2, w, h);
    camera.updateProjectionMatrix();

    state.lid = saved.lid; pointer.x = saved.px; pointer.y = saved.py; apply();
    render();
    placeLines();
  }

  // Point the connector lines at the edges of the open display.
  const lines = hub.querySelector(".hub-lines");
  function placeLines() {
    if (!lines || small.matches) return;
    const hubBox = hub.getBoundingClientRect(), stageBox = stage.getBoundingClientRect();
    const saved = state.lid; state.lid = OPEN; apply(); scene.updateMatrixWorld(true);
    const edge = (sx, sz) => {
      v.set(sx * DISP_W / 2, -0.003, displayZ + sz * DISP_H / 2).applyMatrix4(lid.matrixWorld).project(camera);
      const px = stageBox.left - hubBox.left + ((v.x + 1) / 2) * stageBox.width;
      const py = stageBox.top - hubBox.top + ((1 - v.y) / 2) * stageBox.height;
      return [(px / hubBox.width) * 600, (py / hubBox.height) * 500];
    };
    state.lid = saved; apply();
    const anchor = (sel, top) => {
      const r = hub.querySelector(sel).getBoundingClientRect();
      return [((r.left + r.width / 2 - hubBox.left) / hubBox.width) * 600, (((top ? r.top : r.bottom) - hubBox.top) / hubBox.height) * 500];
    };
    // [card, which card edge, display edge (x side, z position: +1 top … -1 bottom)]
    const spec = { tl: [".hc-tl", false, [-1, 0.45]], tr: [".hc-tr", false, [1, 0.35]], bl: [".hc-bl", true, [-1, -0.7]], br: [".hc-br", true, [1, -0.8]] };
    for (const [id, [sel, top, [sx, sz]]] of Object.entries(spec)) {
      const [ax, ay] = anchor(sel, top), [ex, ey] = edge(sx, sz);
      const d = `M${ax.toFixed(1)} ${ay.toFixed(1)} C${ax.toFixed(1)} ${ey.toFixed(1)} ${((ax + ex) / 2).toFixed(1)} ${ey.toFixed(1)} ${ex.toFixed(1)} ${ey.toFixed(1)}`;
      lines.querySelectorAll(`.ln-${id}`).forEach((p) => p.setAttribute("d", d));
      const dot = lines.querySelector(`.ln-dot[data-for="${id}"]`);
      if (dot) { dot.setAttribute("cx", ex.toFixed(1)); dot.setAttribute("cy", ey.toFixed(1)); }
    }
  }

  // ---------- Open / close ----------
  function setToggle(open) {
    toggleLabel.textContent = open ? "Close laptop" : "Open laptop";
    toggle.setAttribute("aria-expanded", String(open));
  }
  function open() {
    if (target === "open") return;
    target = "open";
    setToggle(true);
    hub.classList.add("is-open");
    tween("lid", OPEN, 1500, () => {
      if (target !== "open") return;
      tween("power", 1, 500, () => {
        if (target !== "open") return;
        hub.classList.add("is-on");
        screenEl.inert = false;
        status.textContent = "Laptop open. The screen has Résumé, Skills, GitHub and LinkedIn.";
      });
    });
  }
  function close() {
    if (target === "closed") return;
    target = "closed";
    closePanel({ restoreFocus: false });
    setToggle(false);
    hub.classList.remove("is-on");
    screenEl.inert = true;
    status.textContent = "Laptop closed.";
    tween("power", 0, 280, () => {
      if (target !== "closed") return;
      hub.classList.remove("is-open");
      tween("lid", 0, 1300);
    });
  }
  toggle.addEventListener("click", () => (target === "open" ? close() : open()));
  stage.addEventListener("click", (e) => { if (target === "closed" && !e.target.closest("button, a")) open(); });

  // ---------- Pop-out panels ----------
  const tiles = [...screenEl.querySelectorAll("[data-open]")];
  const title = panel.querySelector("[data-hp-title]"), file = panel.querySelector("[data-hp-file]"), icon = panel.querySelector("[data-hp-icon]");
  const inner = panel.querySelector(".hp-inner");
  let trigger = null;

  function openPanel(name, btn) {
    const body = panel.querySelector(`[data-panel="${name}"]`);
    panel.querySelectorAll("[data-panel]").forEach((b) => (b.hidden = b !== body));
    title.textContent = body.dataset.title;
    file.textContent = body.dataset.file;
    icon.setAttribute("href", body.dataset.icon);
    tiles.forEach((t) => t.setAttribute("aria-expanded", String(t === btn)));
    const wasOpen = !panel.hidden;
    panel.hidden = false;
    hub.classList.add("has-panel");
    trigger = btn;
    if (!wasOpen && !reduceMotion) {
      // Grow out of the display: start at the screen's on-page box, end at the panel's.
      const from = screenEl.getBoundingClientRect(), to = panel.getBoundingClientRect();
      panel.animate([
        { transform: `translate(${from.left - to.left}px, ${from.top - to.top}px) scale(${from.width / to.width}, ${from.height / to.height})`, opacity: 0.3 },
        { transform: "none", opacity: 1 },
      ], { duration: 560, easing: "cubic-bezier(.2,.8,.2,1)" });
      inner.animate([{ opacity: 0, transform: "translateY(8px)" }, { opacity: 0, offset: 0.35 }, { opacity: 1, transform: "none" }], { duration: 600, easing: "ease-out" });
    } else if (wasOpen && !reduceMotion) {
      inner.animate([{ opacity: 0.2 }, { opacity: 1 }], { duration: 220, easing: "ease-out" });
    }
    title.focus({ preventScroll: true });
  }
  function closePanel({ restoreFocus = true } = {}) {
    if (panel.hidden) return;
    tiles.forEach((t) => t.setAttribute("aria-expanded", "false"));
    const back = trigger;
    const finish = () => { panel.hidden = true; hub.classList.remove("has-panel"); };
    if (reduceMotion || !hub.classList.contains("is-on")) finish();
    else {
      const from = panel.getBoundingClientRect(), to = screenEl.getBoundingClientRect();
      panel.animate([
        { transform: "none", opacity: 1 },
        { transform: `translate(${to.left - from.left}px, ${to.top - from.top}px) scale(${to.width / from.width}, ${to.height / from.height})`, opacity: 0 },
      ], { duration: 360, easing: "cubic-bezier(.4,0,.6,1)" }).finished.then(finish, finish);
    }
    if (restoreFocus && back) back.focus({ preventScroll: true });
  }
  tiles.forEach((t) => t.addEventListener("click", () => openPanel(t.dataset.open, t)));
  panel.querySelector(".hp-close").addEventListener("click", () => closePanel());
  panel.addEventListener("click", (e) => { if (e.target.closest('a[href^="#"]')) closePanel({ restoreFocus: false }); });
  hub.addEventListener("keydown", (e) => {
    if (e.key !== "Escape") return;
    if (!panel.hidden) { e.preventDefault(); closePanel(); }
  });

  // ---------- Pointer tilt (mouse only) ----------
  if (!reduceMotion && matchMedia("(hover: hover) and (pointer: fine)").matches) {
    hub.addEventListener("pointermove", (e) => {
      if (e.pointerType !== "mouse" || hub.classList.contains("has-panel")) return;
      const r = stage.getBoundingClientRect();
      pointer.tx = Math.max(-1, Math.min(1, ((e.clientX - r.left) / r.width) * 2 - 1));
      pointer.ty = Math.max(-1, Math.min(1, ((e.clientY - r.top) / r.height) * 2 - 1));
      kick();
    });
    hub.addEventListener("pointerleave", () => { pointer.tx = pointer.ty = 0; kick(); });
  }

  // ---------- Size ----------
  new ResizeObserver(() => frame()).observe(stage);
  small.addEventListener("change", frame);
  frame();

  hub.classList.add("is-ready");
  toggle.disabled = false;
  setToggle(false);

  const api = {
    open, close, openPanel: (name) => openPanel(name, tiles.find((t) => t.dataset.open === name)), closePanel,
    get state() { return { target, lid: state.lid / DEG, power: state.power }; },
    // For screenshots and tests: hold the lid at an angle in degrees.
    setLid(deg) { delete tw.lid; state.lid = deg * DEG; apply(); render(); },
    // For tests: frames drawn so far, and (in the laptop's own frame) where the
    // base, the hinge and the lid's top edge are.
    inspect() {
      scene.updateMatrixWorld(true);
      const top = new THREE.Vector3(0, 0, D).applyMatrix4(lid.matrix);
      return { frames, base: base.matrix.toArray().map((n) => +n.toFixed(5)), pivot: lid.position.toArray().map((n) => +n.toFixed(5)), lidTop: top.toArray().map((n) => +n.toFixed(4)) };
    },
  };
  hub.laptop3d = api;
  return api;
}
