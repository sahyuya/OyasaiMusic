import test from "node:test";
import assert from "node:assert/strict";
import { PianoRoll } from "../src/piano-roll.js";

function interactionRoll() {
  const roll = Object.create(PianoRoll.prototype);
  roll.canvas = {
    getBoundingClientRect: () => ({ left: 0, top: 0 }),
    setPointerCapture: () => {},
    classList: { add: () => {}, remove: () => {} },
  };
  roll.keyboardWidth = 76;
  roll.rulerHeight = 32;
  roll.plot = { x: 76, y: 32, width: 500, height: 300 };
  roll.visibleNotes = [{ id: 7, x: 100, y: 80, width: 50, height: 12 }];
  roll.drag = null;
  return roll;
}

test("Ctrl＋左ドラッグだけをピアノロールの自由移動として開始する", () => {
  const roll = interactionRoll();
  roll.pointerDown({ button: 0, ctrlKey: true, metaKey: false, clientX: 200, clientY: 150, pointerId: 1, preventDefault() {} });
  assert.equal(roll.drag?.mode, "pan");

  roll.drag = null;
  roll.pointerDown({ button: 1, ctrlKey: false, metaKey: false, clientX: 200, clientY: 150, pointerId: 2, preventDefault() {} });
  assert.equal(roll.drag, null);
});

test("右クリックしたノートを選択してコンテキストメニューへ渡す", () => {
  const roll = interactionRoll();
  let selected = new Set();
  let menu = null;
  roll.getSelected = () => selected;
  roll.onSelectionChange = (next) => { selected = next; };
  roll.onContextMenu = (payload) => { menu = payload; };
  roll.contextMenu({ clientX: 110, clientY: 85, preventDefault() {} });

  assert.deepEqual([...selected], [7]);
  assert.equal(menu.noteId, 7);
  assert.equal(menu.clientX, 110);
});
