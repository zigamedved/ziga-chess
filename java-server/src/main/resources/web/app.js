import { Chess } from "./vendor/chess.js";

const SAMPLE = "r4rk1/4Rppp/p1p5/P1p5/8/3P2P1/1PP2P1P/R5K1 b - - 0 25";
const FILES = ["a", "b", "c", "d", "e", "f", "g", "h"];
const USER_KEY = "ziga-chess-user";
const PASS_KEY = "ziga-chess-pass";
const HISTORY_KEY = "ziga-chess-history";
const QUIZ_KEY = "ziga-chess-quiz";
const apiPrefix = (document.querySelector('meta[name="app-prefix"]').content || "").replace(/\/$/, "");

const fenInput = document.querySelector("#fen");
const statusEl = document.querySelector("#status");
const analyseBtn = document.querySelector("#analyse");
const downloadBtn = document.querySelector("#download-pgn");
const userInput = document.querySelector("#user");
const passInput = document.querySelector("#pass");
const movesEl = document.querySelector("#moves");
const gameList = document.querySelector("#game-list");
const reviewEl = document.querySelector("#review");
const reviewName = document.querySelector("#review-name");
const historyList = document.querySelector("#history-list");
const boardEl = document.querySelector("#board");
const paletteEl = document.querySelector("#palette");
const ghost = document.querySelector("#ghost");
const validityEl = document.querySelector("#fen-validity");

const setup = new Chess(SAMPLE);
const main = { fens: [SAMPLE], sans: [], marks: [null], index: 0, label: "Sample position" };
let bestLine = null;
let flipped = false;
let tool = null;
let armed = null;
let drag = null;
let memoryPassword = "";
let quizPositions = [];
let quizIndex = -1;
let quizNote = "";

function scrubStoredPassword() {
  try {
    localStorage.removeItem(PASS_KEY);
  } catch (err) {
    /* private mode */
  }
}

function readPassword() {
  scrubStoredPassword();
  try {
    const stored = sessionStorage.getItem(PASS_KEY);
    if (typeof stored === "string") {
      memoryPassword = stored;
      return stored;
    }
  } catch (err) {
    /* sessionStorage unavailable; the module variable is the copy */
  }
  return memoryPassword;
}

function writePassword(value) {
  memoryPassword = value;
  scrubStoredPassword();
  try {
    sessionStorage.setItem(PASS_KEY, value);
  } catch (err) {
    /* memory only */
  }
}

function readStorage(key) {
  try {
    return localStorage.getItem(key);
  } catch (err) {
    return null;
  }
}

function writeStorage(key, value) {
  try {
    localStorage.setItem(key, value);
  } catch (err) {
    /* ignore quota / private mode */
  }
}

userInput.value = readStorage(USER_KEY) || "";
passInput.value = readPassword();
userInput.addEventListener("input", () => {
  writeStorage(USER_KEY, userInput.value);
  renderProfile();
});
passInput.addEventListener("input", () => writePassword(passInput.value));
renderProfile();

function setStatus(text, isError) {
  statusEl.textContent = text;
  statusEl.classList.toggle("error", Boolean(isError));
}

function setValidity(text, isError) {
  validityEl.textContent = text || "";
  validityEl.classList.toggle("bad", Boolean(isError) && Boolean(text));
  if (isError && text) fenInput.setAttribute("aria-invalid", "true");
  else fenInput.removeAttribute("aria-invalid");
}

function renderProfile() {
  const name = userInput.value.trim();
  document.querySelector("#avatar").textContent = name ? name.slice(0, 1).toUpperCase() : "?";
  document.querySelector("#who").textContent = name ? name : "Not signed in";
  document.querySelector("#who-hint").textContent = name
    ? "Username stays in this browser. The password stays in this tab only."
    : "Add a username. The password stays in this tab only.";
}

function formatLine(sans, fen) {
  if (!sans.length) return "";
  const parts = (fen || "").split(" ");
  let number = Number(parts[5]) || 1;
  let blackToMove = parts[1] === "b";
  const out = [];
  for (const san of sans) {
    if (blackToMove) {
      out.push(number + "... " + san);
      blackToMove = false;
      number += 1;
    } else {
      out.push(number + ". " + san);
      blackToMove = true;
    }
  }
  return out.join(" ");
}

function describeLine(pgn, fallback) {
  const line = lineFromPgn(pgn || "", fallback);
  return formatLine(line.sans, line.fens[0] || fallback);
}

function showEngineLines(payload) {
  const fen = currentFen();
  const lines = (payload && payload.principalVariations) || [];
  document.querySelector("#pv1-text").textContent = lines[0] ? (describeLine(lines[0].pgn, fen) || "—") : "—";
  document.querySelector("#pv2-text").textContent = lines[1] ? (describeLine(lines[1].pgn, fen) || "—") : "—";
}

function readHistory() {
  try {
    const rows = JSON.parse(readStorage(HISTORY_KEY) || "[]");
    return Array.isArray(rows) ? rows : [];
  } catch (err) {
    return [];
  }
}

function rememberAnalysis(fen) {
  const next = [{ fen, at: Date.now() }, ...readHistory().filter((row) => row.fen !== fen)].slice(0, 6);
  writeStorage(HISTORY_KEY, JSON.stringify(next));
  renderHistory();
}

function renderHistory() {
  historyList.replaceChildren();
  for (const row of readHistory()) {
    const item = document.createElement("li");
    const button = document.createElement("button");
    button.type = "button";
    const copy = document.createElement("span");
    copy.className = "game-copy";
    const when = document.createElement("strong");
    when.textContent = new Date(row.at).toLocaleString();
    const fen = document.createElement("span");
    fen.textContent = row.fen;
    copy.append(when, fen);
    button.append(copy);
    button.addEventListener("click", () => {
      if (placeFen(row.fen, "Recent position")) setStatus("", false);
    });
    item.append(button);
    historyList.append(item);
  }
}

function squareNames(flippedBoard) {
  const files = flippedBoard ? [...FILES].reverse() : FILES;
  const ranks = flippedBoard ? [1, 2, 3, 4, 5, 6, 7, 8] : [8, 7, 6, 5, 4, 3, 2, 1];
  const names = [];
  for (const rank of ranks) {
    for (const file of files) names.push(file + rank);
  }
  return names;
}

function symbolOf(piece) {
  return piece.color === "w" ? piece.type.toUpperCase() : piece.type;
}

function pieceSrc(symbol) {
  const color = symbol === symbol.toUpperCase() ? "w" : "b";
  return "pieces/" + color + symbol.toUpperCase() + ".svg";
}

function pieceLabel(symbol) {
  const names = {
    K: "White king", Q: "White queen", R: "White rook", B: "White bishop", N: "White knight", P: "White pawn",
    k: "Black king", q: "Black queen", r: "Black rook", b: "Black bishop", n: "Black knight", p: "Black pawn",
  };
  return names[symbol] || "Piece";
}

function paint(el, fen, mark, options) {
  const flippedBoard = Boolean(options && options.flipped);
  const chess = new Chess();
  const ok = chess.load(fen);
  el.replaceChildren();
  for (const name of squareNames(flippedBoard)) {
    const file = FILES.indexOf(name[0]);
    const rank = Number(name[1]);
    let symbol = "";
    if (ok) {
      const piece = chess.get(name);
      if (piece) symbol = symbolOf(piece);
    }
    const node = document.createElement("div");
    const light = (file + rank) % 2 === 0;
    node.className = "sq " + (light ? "light" : "dark");
    node.dataset.square = name;
    if (symbol) {
      const img = document.createElement("img");
      img.src = pieceSrc(symbol);
      img.alt = "";
      img.draggable = false;
      node.append(img);
      node.setAttribute("aria-label", pieceLabel(symbol) + " on " + name);
    } else {
      node.setAttribute("aria-label", "Empty " + name);
    }
    if (mark && (mark.from === name || mark.to === name)) node.classList.add("mark");
    if (el === boardEl && armed === name) node.classList.add("armed");
    el.append(node);
  }
  if (el !== boardEl) el.setAttribute("aria-label", fen);
}

function renderCoords() {
  const files = flipped ? [...FILES].reverse() : FILES;
  const ranks = flipped ? [1, 2, 3, 4, 5, 6, 7, 8] : [8, 7, 6, 5, 4, 3, 2, 1];
  const fileEl = document.querySelector("#file-coords");
  const rankEl = document.querySelector("#rank-coords");
  fileEl.replaceChildren();
  rankEl.replaceChildren();
  for (const file of files) {
    const span = document.createElement("span");
    span.textContent = file;
    fileEl.append(span);
  }
  for (const rank of ranks) {
    const span = document.createElement("span");
    span.textContent = String(rank);
    rankEl.append(span);
  }
}

function showArrow(mark) {
  const svg = document.querySelector("#arrow");
  const line = document.querySelector("#arrow-line");
  if (!mark) {
    svg.classList.add("hidden");
    return;
  }
  const point = (squareName) => {
    const file = FILES.indexOf(squareName[0]);
    const rank = Number(squareName[1]);
    return [
      (flipped ? 7 - file : file) + 0.5,
      (flipped ? rank - 1 : 8 - rank) + 0.5,
    ];
  };
  const [x1, y1] = point(mark.from);
  const [x2, y2] = point(mark.to);
  line.setAttribute("x1", x1);
  line.setAttribute("y1", y1);
  line.setAttribute("x2", x2);
  line.setAttribute("y2", y2);
  svg.classList.remove("hidden");
}

function updateTurn(fen) {
  const color = (fen || "").split(" ")[1] === "b" ? "b" : "w";
  document.querySelector("#turn-white").setAttribute("aria-pressed", color === "w" ? "true" : "false");
  document.querySelector("#turn-black").setAttribute("aria-pressed", color === "b" ? "true" : "false");
}

function showMain() {
  const fen = main.fens[main.index] || SAMPLE;
  const mark = main.marks[main.index];
  paint(boardEl, fen, mark, { flipped });
  showArrow(mark);
  renderCoords();
  updateTurn(fen);
  const move = main.sans[main.index - 1];
  document.querySelector("#board-caption").textContent = move ? main.label + " · " + move : main.label;
}

function displayedFen() {
  return main.fens[main.index] || setup.fen();
}

function symbolOn(fen, square) {
  const chess = new Chess();
  if (!chess.load(fen)) return "";
  const piece = chess.get(square);
  return piece ? symbolOf(piece) : "";
}

function adopt(fen, label, writeField) {
  if (!setup.load(fen)) return false;
  const placed = setup.fen();
  if (writeField) fenInput.value = placed;
  setValidity("", false);
  main.fens = [placed];
  main.sans = [];
  main.marks = [null];
  main.index = 0;
  main.label = label;
  armed = null;
  bestLine = null;
  reviewEl.classList.add("hidden");
  document.querySelector("#show-pv1").classList.remove("active");
  document.querySelector("#show-pv2").classList.remove("active");
  showMain();
  renderMoves();
  return true;
}

function placeFen(fen, label) {
  return adopt(fen, label, true);
}

function withDisplayed(mutator, label) {
  if (!setup.load(displayedFen())) return;
  if (mutator() === false) return;
  const parts = setup.fen().split(" ");
  parts[2] = "-";
  parts[3] = "-";
  parts[4] = "0";
  if (!setup.load(parts.join(" "))) return;
  adopt(setup.fen(), label, true);
}

function placeSymbol(square, symbol) {
  withDisplayed(() => {
    const type = symbol.toLowerCase();
    const color = symbol === symbol.toUpperCase() ? "w" : "b";
    if (type === "k") {
      for (const name of squareNames(false)) {
        const piece = setup.get(name);
        if (piece && piece.type === "k" && piece.color === color) setup.remove(name);
      }
    }
    setup.remove(square);
    return setup.put({ type, color }, square);
  }, "Position");
}

function moveSymbol(from, to) {
  if (from === to) return;
  withDisplayed(() => {
    const piece = setup.get(from);
    if (!piece) return false;
    setup.remove(from);
    setup.remove(to);
    return setup.put(piece, to);
  }, "Position");
}

function eraseSquare(square) {
  withDisplayed(() => {
    if (!setup.get(square)) return false;
    setup.remove(square);
    return true;
  }, "Position");
}

function setSide(color) {
  if (!setup.load(displayedFen())) return;
  const parts = setup.fen().split(" ");
  parts[1] = color;
  if (!new Chess().validate_fen(parts.join(" ")).valid) parts[3] = "-";
  if (!setup.load(parts.join(" "))) return;
  adopt(setup.fen(), color === "w" ? "White to move" : "Black to move", true);
}

function syncTools() {
  for (const button of paletteEl.querySelectorAll("[data-tool]")) {
    const on = button.dataset.tool === tool;
    button.setAttribute("aria-pressed", on ? "true" : "false");
    button.classList.toggle("active", on);
  }
}

function showGhost(symbol, event) {
  ghost.replaceChildren();
  const img = document.createElement("img");
  img.src = pieceSrc(symbol);
  img.alt = "";
  ghost.append(img);
  ghost.classList.remove("hidden");
  ghost.style.left = event.clientX + "px";
  ghost.style.top = event.clientY + "px";
}

function endDrag() {
  drag = null;
  ghost.classList.add("hidden");
  ghost.replaceChildren();
  boardEl.querySelectorAll(".hot, .lift").forEach((node) => node.classList.remove("hot", "lift"));
}

paletteEl.addEventListener("pointerdown", (event) => {
  const button = event.target.closest("[data-tool]");
  if (!button || event.button !== 0) return;
  event.preventDefault();
  const previous = tool;
  tool = button.dataset.tool;
  syncTools();
  drag = {
    pointerId: event.pointerId,
    from: null,
    symbol: tool === "erase" ? "" : tool,
    toolSet: tool,
    previous,
    x: event.clientX,
    y: event.clientY,
    moved: false,
    palette: true,
  };
  button.setPointerCapture(event.pointerId);
});

boardEl.addEventListener("pointerdown", (event) => {
  const cell = event.target.closest(".sq");
  if (!cell || !boardEl.contains(cell) || event.button !== 0) return;
  event.preventDefault();
  const square = cell.dataset.square;
  drag = {
    pointerId: event.pointerId,
    from: square,
    symbol: symbolOn(displayedFen(), square),
    x: event.clientX,
    y: event.clientY,
    moved: false,
    palette: false,
  };
  cell.setPointerCapture(event.pointerId);
});

document.addEventListener("pointermove", (event) => {
  if (!drag || event.pointerId !== drag.pointerId) return;
  if (Math.hypot(event.clientX - drag.x, event.clientY - drag.y) > 5) drag.moved = true;
  if (drag.moved && drag.symbol) {
    showGhost(drag.symbol, event);
    if (drag.from) {
      const origin = boardEl.querySelector('[data-square="' + drag.from + '"]');
      if (origin) origin.classList.add("lift");
    }
  }
  boardEl.querySelectorAll(".hot").forEach((node) => node.classList.remove("hot"));
  if (!drag.moved) return;
  const target = document.elementFromPoint(event.clientX, event.clientY);
  const cell = target && target.closest ? target.closest("#board .sq") : null;
  if (cell) cell.classList.add("hot");
});

document.addEventListener("pointerup", (event) => {
  if (!drag || event.pointerId !== drag.pointerId) return;
  const info = drag;
  const target = document.elementFromPoint(event.clientX, event.clientY);
  const cell = target && target.closest ? target.closest("#board .sq") : null;
  const square = cell ? cell.dataset.square : null;
  const onEraser = target && target.closest ? target.closest("[data-tool='erase']") : null;
  endDrag();
  if (info.moved) {
    if (info.palette && info.symbol && square) {
      placeSymbol(square, info.symbol);
      return;
    }
    if (!info.palette && info.symbol && square && square !== info.from) {
      moveSymbol(info.from, square);
      armed = null;
      return;
    }
    if (!info.palette && info.symbol && onEraser) {
      eraseSquare(info.from);
      armed = null;
    }
    return;
  }
  if (info.palette) {
    if (info.previous === info.toolSet) {
      tool = null;
      syncTools();
    }
    return;
  }
  if (tool === "erase") {
    eraseSquare(info.from);
    armed = null;
    return;
  }
  if (tool && tool !== "erase") {
    placeSymbol(info.from, tool);
    return;
  }
  if (armed && armed !== info.from) {
    moveSymbol(armed, info.from);
    armed = null;
    return;
  }
  if (info.symbol) {
    armed = armed === info.from ? null : info.from;
    showMain();
  }
});

document.addEventListener("pointercancel", (event) => {
  if (!drag || event.pointerId !== drag.pointerId) return;
  endDrag();
});

document.querySelector("#clear-board").addEventListener("click", () => {
  setup.clear();
  adopt(setup.fen(), "Empty board", true);
});
document.querySelector("#start-pos").addEventListener("click", () => {
  setup.reset();
  adopt(setup.fen(), "Starting position", true);
});
document.querySelector("#flip-board").addEventListener("click", () => {
  flipped = !flipped;
  document.querySelector("#flip-board").setAttribute("aria-pressed", flipped ? "true" : "false");
  showMain();
});
document.querySelector("#turn-white").addEventListener("click", () => setSide("w"));
document.querySelector("#turn-black").addEventListener("click", () => setSide("b"));

function fenFromPgn(text) {
  try {
    const probe = new Chess();
    if (!probe.load_pgn(text, { sloppy: true })) return null;
    const fen = probe.fen();
    return new Chess().validate_fen(fen).valid ? fen : null;
  } catch (err) {
    return null;
  }
}

function syncFromField(canonicalize) {
  const text = fenInput.value.trim();
  if (!text) {
    setValidity("Enter a FEN.", true);
    return;
  }
  const verdict = new Chess().validate_fen(text);
  if (verdict.valid) {
    adopt(text, "Position", canonicalize);
    return;
  }
  const fromPgn = fenFromPgn(text);
  if (fromPgn) {
    adopt(fromPgn, "PGN position", false);
    setValidity("PGN parsed. Analyse uses the first rook endgame on the main line.", false);
    return;
  }
  setValidity(verdict.error || "That FEN is not valid.", true);
}

fenInput.addEventListener("input", () => syncFromField(false));
fenInput.addEventListener("change", () => syncFromField(true));

function lineFromPgn(pgn, fallbackFen) {
  const empty = { fens: [fallbackFen], sans: [], marks: [null] };
  try {
    const probe = new Chess();
    if (!probe.load_pgn(pgn, { sloppy: true })) return empty;
    const verbose = probe.history({ verbose: true });
    const start = probe.header().FEN || fallbackFen;
    const board = new Chess(start);
    const fens = [board.fen()];
    const sans = [];
    const marks = [null];
    for (const move of verbose) {
      const played = board.move(move.san, { sloppy: true });
      if (!played) break;
      fens.push(board.fen());
      sans.push(played.san);
      marks.push({ from: played.from, to: played.to });
    }
    return { fens, sans, marks };
  } catch (err) {
    return empty;
  }
}

function renderMoves() {
  movesEl.replaceChildren();
  main.sans.forEach((san, i) => {
    const item = document.createElement("li");
    const button = document.createElement("button");
    button.type = "button";
    button.textContent = san;
    if (main.index === i + 1) button.classList.add("active");
    button.addEventListener("click", () => {
      main.index = i + 1;
      showMain();
      renderMoves();
    });
    item.append(button);
    movesEl.append(item);
  });
}

function step(line, delta) {
  line.index = Math.min(Math.max(line.index + delta, 0), line.fens.length - 1);
  showMain();
  renderMoves();
}

function selectLine(which, pgn) {
  bestLine = null;
  reviewEl.classList.add("hidden");
  const line = lineFromPgn(pgn, currentFen());
  main.fens = line.fens;
  main.sans = line.sans;
  main.marks = line.marks;
  main.index = line.fens.length > 1 ? 1 : 0;
  main.label = which;
  armed = null;
  showMain();
  renderMoves();
  document.querySelector("#show-pv1").classList.toggle("active", which === "PV1");
  document.querySelector("#show-pv2").classList.toggle("active", which === "PV2");
}

function rememberBest() {
  if (bestLine) return;
  bestLine = {
    fens: main.fens.slice(),
    sans: main.sans.slice(),
    marks: main.marks.slice(),
    index: main.index,
    label: main.label,
  };
}

function selectGame(game, button) {
  rememberBest();
  const fen = game.endgameFen || currentFen();
  const line = lineFromPgn(game.pv1 || "", fen);
  main.fens = line.fens.length ? line.fens : [fen];
  main.sans = line.sans;
  main.marks = line.marks.length ? line.marks : [null];
  main.index = line.sans.length ? 1 : 0;
  main.label = game.white + " – " + game.black;
  armed = null;
  reviewName.textContent = main.label;
  reviewEl.classList.remove("hidden");
  showMain();
  renderMoves();
  for (const node of gameList.querySelectorAll("button")) {
    node.classList.toggle("active", node === button);
  }
}

function restoreBest() {
  if (!bestLine) return;
  main.fens = bestLine.fens;
  main.sans = bestLine.sans;
  main.marks = bestLine.marks;
  main.index = bestLine.index;
  main.label = bestLine.label;
  bestLine = null;
  reviewEl.classList.add("hidden");
  showMain();
  renderMoves();
  for (const node of gameList.querySelectorAll("button")) node.classList.remove("active");
}

function whyText(game) {
  const tokens = game.explanation && game.explanation.tokens;
  if (!tokens || !tokens.length) return "";
  return tokens.map((token) => token.field + ":" + token.token).join("  ");
}

function matchPercent(game, games) {
  const top = Math.max(...games.map((row) => Number(row.score) || 0), 0);
  if (top <= 0) return 0;
  return Math.round(((Number(game.score) || 0) / top) * 100);
}

function renderMeter(games) {
  let white = 0;
  let draw = 0;
  let black = 0;
  for (const game of games) {
    if (game.result === "1-0") white += 1;
    else if (game.result === "0-1") black += 1;
    else if (game.result === "1/2-1/2") draw += 1;
  }
  const total = white + draw + black;
  const set = (id, count) => {
    document.querySelector(id).style.flex = String(total ? count : 0);
  };
  set("#meter-white", white);
  set("#meter-draw", draw);
  set("#meter-black", black);
  document.querySelector("#meter-counts").textContent = "W " + white + " · D " + draw + " · L " + black;
  document.querySelector("#meter-caption").textContent = "Outcomes in similar games";
  document.querySelector("#meter").setAttribute(
    "aria-label",
    total
      ? "Outcomes in similar games. " + white + " white wins, " + draw + " draws, " + black + " black wins."
      : "Outcomes in similar games. No similar games yet."
  );
}

function renderGames(games) {
  gameList.replaceChildren();
  reviewEl.classList.add("hidden");
  bestLine = null;
  renderMeter(games);
  if (!games.length) {
    const empty = document.createElement("li");
    empty.textContent = "No similar games in this index.";
    gameList.append(empty);
    return;
  }
  games.forEach((game) => {
    const item = document.createElement("li");
    const button = document.createElement("button");
    button.type = "button";
    const thumb = document.createElement("div");
    thumb.className = "board thumb";
    thumb.setAttribute("aria-hidden", "true");
    const copy = document.createElement("span");
    copy.className = "game-copy";
    const title = document.createElement("strong");
    title.textContent = game.white + " – " + game.black;
    const meta = document.createElement("span");
    const pct = matchPercent(game, games);
    const ratings = [game.whiteElo, game.blackElo].filter(Boolean).join("/");
    meta.textContent = (game.result || "—") + (ratings ? " · " + ratings : "") + " · " + pct + "% match";
    const bar = document.createElement("span");
    bar.className = "match";
    const fill = document.createElement("span");
    fill.style.width = pct + "%";
    bar.append(fill);
    copy.append(title, meta, bar);
    const why = whyText(game);
    if (why) {
      const note = document.createElement("span");
      note.className = "why";
      note.textContent = why;
      copy.append(note);
    }
    button.append(thumb, copy);
    button.addEventListener("click", () => selectGame(game, button));
    item.append(button);
    gameList.append(item);
    paint(thumb, game.endgameFen || currentFen(), null);
  });
}

document.querySelector("#show-pv1").addEventListener("click", () => {
  if (window.__lastAnalyse && window.__lastAnalyse.principalVariations[0]) {
    selectLine("PV1", window.__lastAnalyse.principalVariations[0].pgn);
  }
});
document.querySelector("#show-pv2").addEventListener("click", () => {
  if (window.__lastAnalyse && window.__lastAnalyse.principalVariations[1]) {
    selectLine("PV2", window.__lastAnalyse.principalVariations[1].pgn);
  }
});

for (const [id, delta] of [["main-start", -999], ["main-prev", -1], ["main-next", 1], ["main-end", 999]]) {
  document.querySelector("#" + id).addEventListener("click", () => step(main, delta));
}
document.querySelector("#back-line").addEventListener("click", restoreBest);

function currentFen() {
  return positionFromInput(fenInput.value) || SAMPLE;
}

function positionFromInput(raw) {
  const text = (raw || "").trim();
  if (!text) return null;
  const chess = new Chess();
  if (chess.validate_fen(text).valid) {
    chess.load(text);
    return chess.fen();
  }
  return fenFromPgn(text);
}

document.querySelector("#copy-link").addEventListener("click", async () => {
  const fen = currentFen();
  if (!new Chess().validate_fen(fen).valid) {
    setStatus("That FEN could not be placed on the board.", true);
    return;
  }
  const url = new URL(window.location.href);
  url.searchParams.set("fen", fen);
  history.replaceState(null, "", url);
  try {
    await navigator.clipboard.writeText(url.toString());
    setStatus("Link copied.", false);
  } catch (err) {
    setStatus(url.toString(), false);
  }
});

downloadBtn.addEventListener("click", () => {
  const text = (window.__lastAnalyse && window.__lastAnalyse.similarPgn) || "";
  if (!text.trim()) {
    setStatus("No similar games to download.", true);
    return;
  }
  const file = new Blob([text], { type: "application/x-chess-pgn" });
  const link = document.createElement("a");
  link.href = URL.createObjectURL(file);
  link.download = "similar-games.pgn";
  link.click();
  URL.revokeObjectURL(link.href);
});

analyseBtn.addEventListener("click", async () => {
  const raw = fenInput.value.trim();
  if (!raw) {
    quizNote = "";
    setStatus("Enter a FEN or a PGN first.", true);
    return;
  }
  const probe = new Chess();
  let body;
  if (probe.validate_fen(raw).valid) {
    probe.load(raw);
    fenInput.value = probe.fen();
    body = { FEN: probe.fen() };
  } else {
    body = { PGN: raw };
  }
  const filter = analyseFilter(body);
  if (filter.error) {
    quizNote = "";
    setStatus(filter.error, true);
    return;
  }
  const user = userInput.value;
  const pass = passInput.value;
  writeStorage(USER_KEY, user);
  writePassword(pass);
  analyseBtn.disabled = true;
  downloadBtn.disabled = true;
  setStatus("Analysing…", false);
  try {
    const response = await fetch(apiPrefix + "/analyse", {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        Authorization: basicAuthHeader(user, pass),
      },
      body: JSON.stringify(filter.body),
    });
    const payload = await response.json();
    if (!response.ok) {
      quizNote = "";
      setStatus(payload.error || "Analyse failed.", true);
      return;
    }
    window.__lastAnalyse = payload;
    if (payload.fen && probe.validate_fen(payload.fen).valid) {
      const label = payload.endgameFamily === "bishop"
        ? "Bishop endgame"
        : payload.endgamePly == null ? "Position" : "Rook endgame";
      placeFen(payload.fen, label);
    }
    showEngineLines(payload);
    if (payload.principalVariations && payload.principalVariations[0]) {
      selectLine("PV1", payload.principalVariations[0].pgn);
    }
    rememberAnalysis(payload.fen || fenInput.value);
    renderGames(payload.similarGames || []);
    downloadBtn.disabled = !(payload.similarPgn && payload.similarPgn.trim());
    const ms = payload.meta && payload.meta.tookMs;
    const plyNote = typeof payload.endgamePly === "number"
      ? "Rook endgame at ply " + payload.endgamePly + ". "
      : "";
    const note = quizNote;
    quizNote = "";
    const familyNote = payload.endgameFamily === "bishop" ? "Bishop endgame. " : "";
    const warn = payload.warning ? payload.warning + " " : "";
    setStatus((note ? note + " " : "") + warn + familyNote + plyNote + "Done in " + ms + " ms.", false);
  } catch (err) {
    quizNote = "";
    setStatus("The analyse request failed.", true);
  } finally {
    analyseBtn.disabled = false;
  }
});

function analyseFilter(body) {
  const result = document.querySelector("#result").value;
  if (result) body.result = result;
  const minElo = eloBound("#min-elo");
  const maxElo = eloBound("#max-elo");
  if (minElo === false || maxElo === false) return { error: "Elo bounds must be non-negative integers." };
  if (minElo != null && maxElo != null && minElo > maxElo) return { error: "Min Elo is greater than max Elo." };
  if (minElo != null) body.minElo = minElo;
  if (maxElo != null) body.maxElo = maxElo;
  return { body };
}

function eloBound(id) {
  const raw = document.querySelector(id).value.trim();
  if (!raw) return null;
  if (!/^[0-9]+$/.test(raw)) return false;
  return Number(raw);
}

function basicAuthHeader(user, pass) {
  const bytes = new TextEncoder().encode(user + ":" + pass);
  let binary = "";
  for (const byte of bytes) binary += String.fromCharCode(byte);
  return "Basic " + btoa(binary);
}

function readSolved() {
  try {
    const raw = JSON.parse(readStorage(QUIZ_KEY) || "[]");
    return Array.isArray(raw) ? raw.filter((id) => typeof id === "string") : [];
  } catch (err) {
    return [];
  }
}

function renderQuizProgress() {
  const progress = document.querySelector("#quiz-progress");
  if (!progress || !quizPositions.length) return;
  const solved = new Set(readSolved());
  const done = quizPositions.filter((row) => solved.has(row.id)).length;
  progress.textContent = done + " of " + quizPositions.length + " solved on this browser.";
}

function markSolved(id) {
  const ids = readSolved();
  if (id && !ids.includes(id)) {
    ids.push(id);
    writeStorage(QUIZ_KEY, JSON.stringify(ids));
  }
  renderQuizProgress();
}

document.querySelector("#quiz-next").addEventListener("click", async () => {
  if (!quizPositions.length) {
    try {
      const response = await fetch(apiPrefix + "/quiz", {
        headers: { Authorization: basicAuthHeader(userInput.value, passInput.value) },
      });
      const payload = await response.json();
      if (!response.ok) {
        setStatus(payload.error || "Quiz failed.", true);
        return;
      }
      quizPositions = payload;
      renderQuizProgress();
    } catch (err) {
      setStatus("The quiz request failed.", true);
      return;
    }
  }
  const solved = new Set(readSolved());
  let next = -1;
  for (let i = quizIndex + 1; i < quizPositions.length; i += 1) {
    if (!solved.has(quizPositions[i].id)) {
      next = i;
      break;
    }
  }
  if (next < 0) next = quizPositions.findIndex((row) => !solved.has(row.id));
  if (next < 0) next = (quizIndex + 1) % quizPositions.length;
  quizIndex = next;
  const row = quizPositions[next];
  document.querySelector("#quiz-title").textContent = row.title;
  document.querySelector("#quiz-move").value = "";
  quizNote = "";
  placeFen(row.fen, row.title);
  setStatus(row.title + ". Find the move.", false);
});

document.querySelector("#quiz-check").addEventListener("click", () => submitQuiz(false));
document.querySelector("#quiz-solution").addEventListener("click", () => submitQuiz(true));

async function submitQuiz(showSolution) {
  if (quizIndex < 0 || !quizPositions[quizIndex]) {
    setStatus("Load a quiz position first.", true);
    return;
  }
  const row = quizPositions[quizIndex];
  const san = document.querySelector("#quiz-move").value.trim();
  if (!showSolution && !san) {
    setStatus("Enter a move.", true);
    return;
  }
  const body = { id: row.id };
  if (showSolution) body.showSolution = true;
  if (san) body.san = san;
  writeStorage(USER_KEY, userInput.value);
  writePassword(passInput.value);
  try {
    const response = await fetch(apiPrefix + "/quiz", {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        Authorization: basicAuthHeader(userInput.value, passInput.value),
      },
      body: JSON.stringify(body),
    });
    const payload = await response.json();
    if (!response.ok) {
      setStatus(payload.error || "Quiz failed.", true);
      return;
    }
    if (payload.revealed && payload.solution) document.querySelector("#quiz-move").value = payload.solution;
    if (payload.correct) {
      markSolved(row.id);
      quizNote = "Correct, " + payload.solution + ".";
    } else if (payload.revealed) {
      quizNote = "Solution " + payload.solution + ".";
    } else {
      setStatus("Not that move.", true);
      return;
    }
    if (payload.fen) placeFen(payload.fen, row.title);
    analyseBtn.click();
  } catch (err) {
    setStatus("The quiz request failed.", true);
  }
}

const linked = new URLSearchParams(window.location.search).get("fen");
if (linked && !placeFen(linked, "Shared position")) {
  setStatus("The fen link is not a valid position.", true);
}
renderHistory();
renderMeter([]);
showMain();
