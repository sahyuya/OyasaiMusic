const DATABASE_NAME = "ommt-local-sessions";
const DATABASE_VERSION = 1;
const STORE_NAME = "sessions";
const AUTOSAVE_KEY = "latest";
export const SESSION_FORMAT_VERSION = 2;

export async function loadLatestSession() {
  const database = await openDatabase();
  try {
    const record = await requestResult(database.transaction(STORE_NAME, "readonly").objectStore(STORE_NAME).get(AUTOSAVE_KEY));
    if (!record || record.formatVersion !== SESSION_FORMAT_VERSION) return null;
    return record;
  } finally {
    database.close();
  }
}

export async function saveLatestSession(snapshot) {
  if (!snapshot?.midi?.notes || !Array.isArray(snapshot.parts) || !Array.isArray(snapshot.assignments)) {
    throw new Error("保存する編集状態が不完全です。");
  }
  const database = await openDatabase();
  try {
    const transaction = database.transaction(STORE_NAME, "readwrite");
    transaction.objectStore(STORE_NAME).put({
      ...snapshot,
      key: AUTOSAVE_KEY,
      formatVersion: SESSION_FORMAT_VERSION,
      savedAt: Date.now(),
    });
    await transactionComplete(transaction);
  } finally {
    database.close();
  }
}

export async function clearLatestSession() {
  const database = await openDatabase();
  try {
    const transaction = database.transaction(STORE_NAME, "readwrite");
    transaction.objectStore(STORE_NAME).delete(AUTOSAVE_KEY);
    await transactionComplete(transaction);
  } finally {
    database.close();
  }
}

function openDatabase() {
  if (!("indexedDB" in globalThis)) return Promise.reject(new Error("このブラウザは編集状態の保存に対応していません。"));
  return new Promise((resolve, reject) => {
    const request = indexedDB.open(DATABASE_NAME, DATABASE_VERSION);
    request.addEventListener("upgradeneeded", () => {
      const database = request.result;
      if (!database.objectStoreNames.contains(STORE_NAME)) database.createObjectStore(STORE_NAME, { keyPath: "key" });
    });
    request.addEventListener("success", () => resolve(request.result), { once: true });
    request.addEventListener("error", () => reject(request.error || new Error("編集状態の保存領域を開けませんでした。")), { once: true });
    request.addEventListener("blocked", () => reject(new Error("別のOMMTタブを閉じてからもう一度お試しください。")), { once: true });
  });
}

function requestResult(request) {
  return new Promise((resolve, reject) => {
    request.addEventListener("success", () => resolve(request.result), { once: true });
    request.addEventListener("error", () => reject(request.error), { once: true });
  });
}

function transactionComplete(transaction) {
  return new Promise((resolve, reject) => {
    transaction.addEventListener("complete", () => resolve(), { once: true });
    transaction.addEventListener("abort", () => reject(transaction.error || new Error("編集状態を保存できませんでした。")), { once: true });
    transaction.addEventListener("error", () => reject(transaction.error || new Error("編集状態を保存できませんでした。")), { once: true });
  });
}
