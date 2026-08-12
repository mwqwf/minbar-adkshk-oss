/**
 * Backend المرجعي المشترك لتطبيقي منبر ادكصهك (mxqp-8d1e8).
 *
 * مبادئ الحماية:
 * - العمليات الحساسة onCall تتطلب Firebase App Check.
 * - المستمع يسجل دخولاً مجهولاً قبل إنشاء مساهمة/ملاحظة/مشاهدة.
 * - صلاحية الإدارة = بريد المالك أو role=supervisor و blocked!=true.
 * - البيانات الخاصة بالمالك لا تُكتب من أي عميل، بل عبر Admin SDK فقط.
 */
const functions = require("firebase-functions/v1");
const admin = require("firebase-admin");
const crypto = require("crypto");

admin.initializeApp();

const db = admin.firestore();
// اسم الحاوية صراحةً: admin.storage().bucket() بلا اسم يرمي خطأً عند تحميل
// الوحدة إذا خلا FIREBASE_CONFIG من storageBucket (كما في فحص النشر المحلي)،
// فيفشل اكتشاف الدوال كلها بمهلة. المشاريع الجديدة حاويتها *.firebasestorage.app.
const bucket = admin.storage().bucket(
  JSON.parse(process.env.FIREBASE_CONFIG || "{}").storageBucket
    || "mxqp-8d1e8.firebasestorage.app",
);
const TOPIC = "content";
const OWNER_EMAIL = "bdalmjydtbwn812@gmail.com";
const ADMINS_COLLECTION = "dashboard_admins";
const CODE_TTL_MS = 10 * 60 * 1000;
const CODE_REQUEST_INTERVAL_MS = 60 * 1000;
const MAX_CODE_ATTEMPTS = 5;
const MAX_SUBMISSION_BYTES = 100 * 1024 * 1024;
const VIEW_MILESTONES = [100, 500, 1000, 5000, 10000];

function normalizeEmail(value) {
  return String(value || "").trim().toLowerCase();
}

function cleanString(value, maxLength) {
  return String(value || "").trim().slice(0, maxLength);
}

function requireString(value, field, minLength, maxLength) {
  const result = cleanString(value, maxLength);
  if (result.length < minLength) {
    throw new functions.https.HttpsError(
      "invalid-argument",
      `الحقل ${field} غير صالح.`,
    );
  }
  return result;
}

function contextEmail(context) {
  return normalizeEmail(context.auth && context.auth.token
    ? context.auth.token.email
    : "");
}

// وضع المراقبة أولاً (الخطوة 7 من تسلسل النشر الآمن): لا يُرفض الطلب بلا
// رمز App Check قبل تسجيل تواقيع Play وتفعيل Play Integrity — يُقلَب إلى
// true بعد التأكد من أن كل الطلبات الشرعية تحمل الرمز.
const APP_CHECK_ENFORCED = false;

function assertAppCheck(context) {
  if (!context.app) {
    if (APP_CHECK_ENFORCED) {
      throw new functions.https.HttpsError(
        "failed-precondition",
        "تعذر التحقق من سلامة التطبيق (App Check).",
      );
    }
    console.warn("App Check token missing — monitoring mode, request allowed.");
  }
}

function assertSignedIn(context) {
  if (!context.auth || !context.auth.uid) {
    throw new functions.https.HttpsError("unauthenticated", "يجب تسجيل الدخول.");
  }
  return context.auth.uid;
}

async function assertAuthorized(context) {
  assertAppCheck(context);
  assertSignedIn(context);
  const email = contextEmail(context);
  if (!email) {
    throw new functions.https.HttpsError(
      "permission-denied",
      "الحساب لا يملك بريداً موثقاً.",
    );
  }
  if (email === OWNER_EMAIL) return { email, owner: true };
  const snap = await db.collection(ADMINS_COLLECTION).doc(email).get();
  const data = snap.data() || {};
  if (snap.exists && data.role === "supervisor" && data.blocked !== true) {
    return { email, owner: false };
  }
  throw new functions.https.HttpsError("permission-denied", "الحساب غير مخول.");
}

async function assertOwner(context) {
  assertAppCheck(context);
  assertSignedIn(context);
  if (contextEmail(context) !== OWNER_EMAIL) {
    throw new functions.https.HttpsError(
      "permission-denied",
      "هذه العملية خاصة بمالك التطبيق.",
    );
  }
  return OWNER_EMAIL;
}

function hashId(value) {
  return crypto.createHash("sha256").update(String(value)).digest("hex");
}

function safeData(data) {
  const out = {};
  Object.entries(data || {}).forEach(([key, value]) => {
    if (value !== undefined && value !== null) out[key] = String(value);
  });
  return out;
}

async function consumeRateLimit({ uid, action, limit, windowMs, minIntervalMs }) {
  const ref = db.collection("private_rate_limits")
    .doc(hashId(`${action}:${uid}`));
  const now = Date.now();
  await db.runTransaction(async (tx) => {
    const snap = await tx.get(ref);
    const current = snap.data() || {};
    let windowStart = Number(current.windowStart || 0);
    let count = Number(current.count || 0);
    const lastAt = Number(current.lastAt || 0);
    if (!windowStart || now - windowStart >= windowMs) {
      windowStart = now;
      count = 0;
    }
    if (lastAt && now - lastAt < minIntervalMs) {
      throw new functions.https.HttpsError(
        "resource-exhausted",
        "طلبات متتابعة بسرعة كبيرة. حاول لاحقاً.",
      );
    }
    if (count >= limit) {
      throw new functions.https.HttpsError(
        "resource-exhausted",
        "تم بلوغ الحد المسموح مؤقتاً.",
      );
    }
    tx.set(ref, {
      uid,
      action,
      windowStart,
      count: count + 1,
      lastAt: now,
      expiresAt: now + windowMs * 2,
      updatedAt: admin.firestore.FieldValue.serverTimestamp(),
    });
  });
}

async function auditOwnerAction(actorEmail, action, targetId, details) {
  await db.collection("owner_audit_logs").add({
    actorEmail,
    action,
    targetId: targetId || "",
    details: details || {},
    createdAt: admin.firestore.FieldValue.serverTimestamp(),
    createdAtMs: Date.now(),
  });
}

async function writeAdminAlert(email, title, body, data) {
  const metadata = safeData(data);
  const type = cleanString(metadata.type, 40);
  const refId = cleanString(
    metadata.refId
      || metadata.submissionId
      || metadata.lessonId
      || metadata.candidateEmail
      || metadata.id,
    180,
  );
  await db.collection("admin_alerts").add({
    email: normalizeEmail(email),
    excludeEmail: normalizeEmail(metadata.excludeEmail),
    title: cleanString(title, 120),
    body: cleanString(body, 700),
    type,
    refId,
    data: metadata,
    readBy: [],
    createdAt: admin.firestore.FieldValue.serverTimestamp(),
    createdAtMs: Date.now(),
  });
}

async function clearAdminAlerts(type, refId) {
  const normalizedType = cleanString(type, 40);
  const normalizedRef = cleanString(refId, 180);
  if (!normalizedType || !normalizedRef) return 0;
  const snap = await db.collection("admin_alerts").get();
  const refs = snap.docs.filter((doc) => {
    const value = doc.data() || {};
    const metadata = value.data || {};
    const itemType = cleanString(value.type || metadata.type, 40);
    const itemRef = cleanString(
      value.refId
        || metadata.refId
        || metadata.submissionId
        || metadata.lessonId
        || metadata.candidateEmail
        || metadata.id,
      180,
    );
    return itemType === normalizedType && itemRef === normalizedRef;
  }).map((doc) => doc.ref);
  for (let offset = 0; offset < refs.length; offset += 400) {
    const batch = db.batch();
    refs.slice(offset, offset + 400).forEach((ref) => batch.delete(ref));
    await batch.commit();
  }
  return refs.length;
}

async function writeUserNotification(uid, title, body, data) {
  const userId = cleanString(uid, 180);
  if (!userId) return null;
  const metadata = safeData(data);
  return db.collection("user_notifications").doc(userId).collection("items").add({
    title: cleanString(title, 120),
    body: cleanString(body, 700),
    type: cleanString(metadata.type, 40),
    refId: cleanString(metadata.refId || metadata.id, 180),
    data: metadata,
    read: false,
    createdAt: admin.firestore.FieldValue.serverTimestamp(),
    createdAtMs: Date.now(),
  });
}

async function logPublicNotification(title, body, data) {
  await db.collection("notifications").add({
    title: cleanString(title || "منبر ادكصهك", 100),
    body: cleanString(body, 500),
    type: cleanString(data && data.type || "manual", 40),
    // معرّف الهدف: المفاتيح الصريحة أولاً ثم `id` العام (توافق خلفي).
    refId: cleanString(
      data && (data.refId || data.id || data.lessonId
        || data.subcategoryId || data.categoryId || data.bookId),
      160,
    ) || null,
    createdAt: admin.firestore.FieldValue.serverTimestamp(),
    createdAtMs: Date.now(),
  });
}

// ⚠️ لا مفتاح `click_action` في خريطة `data`: هو مخلَّف من نسخة الفلاتر
// (كانت مكتبة flutter_local_notifications تقرؤه) وخامل تماماً على أندرويد
// الأصلي — التوجيه هناك يقرأ `type` ومعرّف الهدف من الحمولة/الـextras.
async function pushToTopic(title, body, data) {
  const t = cleanString(title || "منبر ادكصهك", 100);
  const b = cleanString(body, 500);
  await logPublicNotification(t, b, data);
  return admin.messaging().send({
    topic: TOPIC,
    notification: { title: t, body: b },
    data: safeData(data),
    android: {
      priority: "high",
      notification: { channelId: "minbar_content", sound: "default" },
    },
  });
}

async function pushToCondition(title, body, data, condition) {
  const t = cleanString(title || "منبر ادكصهك", 100);
  const b = cleanString(body, 500);
  await logPublicNotification(t, b, data);
  return admin.messaging().send({
    condition,
    notification: { title: t, body: b },
    data: safeData(data),
    android: {
      priority: "high",
      notification: { channelId: "minbar_content", sound: "default" },
    },
  });
}

async function pushToToken(token, title, body, data) {
  if (!token) return null;
  try {
    return await admin.messaging().send({
      token,
      notification: {
        title: cleanString(title, 100),
        body: cleanString(body, 500),
      },
      data: safeData(data),
      android: {
        priority: "high",
        notification: { channelId: "minbar_content", sound: "default" },
      },
    });
  } catch (error) {
    console.error("pushToToken failed", error);
    return null;
  }
}

async function activeAdminTokens(ownerOnly) {
  const snap = await db.collection("admin_device_tokens").get();
  if (snap.empty) return [];
  const adminCache = new Map();
  const accepted = [];
  for (const doc of snap.docs) {
    const value = doc.data() || {};
    const email = normalizeEmail(value.email);
    const token = cleanString(value.token, 4096);
    if (!email || !token) continue;
    if (email === OWNER_EMAIL) {
      accepted.push({
        ref: doc.ref,
        token,
        email,
        uid: cleanString(value.uid || doc.id, 180),
        chatMuted: value.chatMuted === true,
      });
      continue;
    }
    if (ownerOnly) continue;
    let authorized = adminCache.get(email);
    if (authorized === undefined) {
      const adminSnap = await db.collection(ADMINS_COLLECTION).doc(email).get();
      const data = adminSnap.data() || {};
      authorized = adminSnap.exists
        && data.role === "supervisor"
        && data.blocked !== true;
      adminCache.set(email, authorized);
    }
    if (authorized) {
      accepted.push({
        ref: doc.ref,
        token,
        email,
        uid: cleanString(value.uid || doc.id, 180),
        chatMuted: value.chatMuted === true,
      });
    }
  }
  return accepted;
}

async function pushToAdmins(title, body, data, ownerOnly = false) {
  const targets = await activeAdminTokens(ownerOnly);
  return sendToAdminTargets(targets, title, body, data);
}

async function pushToAdminsFiltered(title, body, data, options = {}) {
  const targets = (await activeAdminTokens(options.ownerOnly === true)).filter((item) => {
    if (options.targetEmail && item.email !== normalizeEmail(options.targetEmail)) return false;
    if (options.excludeEmail && item.email === normalizeEmail(options.excludeEmail)) return false;
    if (options.excludeUid && item.uid === cleanString(options.excludeUid, 180)) return false;
    if (options.respectChatMute && item.chatMuted) return false;
    return true;
  });
  return sendToAdminTargets(targets, title, body, data);
}

async function sendToAdminTargets(targets, title, body, data) {
  if (!targets.length) return { successCount: 0, failureCount: 0 };
  let successCount = 0;
  let failureCount = 0;
  for (let offset = 0; offset < targets.length; offset += 500) {
    const chunk = targets.slice(offset, offset + 500);
    const response = await admin.messaging().sendEachForMulticast({
      tokens: chunk.map((item) => item.token),
      notification: {
        title: cleanString(title, 100),
        body: cleanString(body, 500),
      },
      data: safeData(data),
      android: {
        priority: "high",
        notification: { channelId: "admin_alerts", sound: "default" },
      },
    });
    successCount += response.successCount;
    failureCount += response.failureCount;
    const removals = [];
    response.responses.forEach((item, index) => {
      const code = item.error && item.error.code || "";
      if (code.includes("registration-token-not-registered")
          || code.includes("invalid-registration-token")) {
        removals.push(chunk[index].ref.delete());
      }
    });
    await Promise.all(removals);
  }
  return { successCount, failureCount };
}

function unwrapLegacy(raw) {
  if (raw && raw.data && typeof raw.data === "object") {
    return Object.assign({}, raw.data, raw);
  }
  return raw || {};
}

// ─── المحتوى المنشور وإشعاراته ─────────────────────────────────────
exports.onLessonCreated = functions.firestore
  .document("lessons/{id}")
  .onCreate(async (snap) => {
    const d = unwrapLegacy(snap.data());
    // ♻️ الاستعادة من السلة تُعيد كتابة وثيقة الدرس كما كانت فيُطلق هذا
    // المُشغِّل ثانيةً. الوثيقة المستعادة تحمل publishNotified=true إن سبق
    // إشعار نشرها، فلا يُعاد الإشعار — تماماً كما يفحصها
    // dispatchScheduledLesson قبل الإرسال. بلا هذا الفحص كان درس قديم
    // يصل لكل المستمعين بوصفه «درساً جديداً» بمجرّد التراجع عن حذفه.
    if (d.publishNotified === true) return null;
    // ♻️ ووسم الاستعادة نفسه حارسٌ ثانٍ لا غنى عنه: الحارس أعلاه يعتمد على
    // حقل `publishNotified`، وهو **غائب تماماً** من كل درس أُنشئ قبل وجود
    // هذا الحقل (وهي أغلب المكتبة). فاستعادة درس قديم من السلة كانت تصل
    // كإشعار «درس جديد» لكل المستمعين — نفس الفاجعة التي يمنعها السطر
    // أعلاه للدروس الحديثة وحدها. `restoredAtMs` تكتبه restoreDeletedLesson
    // لحظة الاستعادة، والنافذة قصيرة (١٠ دقائق) كي لا يُسكِت إضافةً لاحقة
    // بالمعرّف نفسه — مطابقة لنافذة onLessonSuspicionCreated حرفياً.
    const restoredAtMs = Number(d.restoredAtMs || 0);
    if (restoredAtMs > 0 && Date.now() - restoredAtMs < 10 * 60 * 1000) {
      await snap.ref.set({ publishNotified: true }, { merge: true })
        .catch((error) => console.error("mark restored publishNotified failed", error));
      return null;
    }
    if (d.publishAt) {
      const at = Date.parse(d.publishAt);
      if (!Number.isNaN(at) && at > Date.now()) return null;
    }
    const title = cleanString(d.title || d.name, 180);
    const subId = cleanString(d.subcategoryId, 160);
    if (subId) {
      await pushToCondition(
        "درس جديد",
        title || "أُضيف درس صوتي جديد",
        {
          type: "lesson",
          id: snap.id,
          lessonId: snap.id,
          subId,
          subcategoryId: subId,
        },
        `'${TOPIC}' in topics || 'sec_${subId}' in topics`,
      );
    } else {
      await pushToTopic(
        "درس جديد",
        title || "أُضيف درس صوتي جديد",
        { type: "lesson", id: snap.id, lessonId: snap.id },
      );
    }
    // يمنع ازدواج الإشعار مع publishScheduledLessons عندما يكون publishAt
    // وقتاً ماضياً لحظة الإنشاء (رفع طويل تجاوز موعد الجدولة).
    await snap.ref.set({ publishNotified: true }, { merge: true })
      .catch((error) => console.error("mark publishNotified failed", error));
    return null;
  });

// 🔔 إعلان الإصدار الجديد لكل المستخدمين عبر FCM لحظة رفع رقمه في
// `app_config/android`. ضروريّ خصوصاً للنسخ المثبَّتة القديمة التي كان
// فحص التحديث فيها يقرأ من الكاش إلى الأبد فلا يرى الرقم الجديد أبداً —
// دفعة FCM تصلها مهما كان حال فحصها الداخلي.
exports.onAppUpdatePublished = functions.firestore
  .document("app_config/android")
  .onWrite(async (change) => {
    const before = change.before.exists ? change.before.data() : {};
    const after = change.after.exists ? change.after.data() : null;
    if (!after) return null;
    const latest = Number(after.latestVersionCode || 0);
    const previous = Number((before && before.latestVersionCode) || 0);
    // إشعار عند ارتفاع الرقم فقط — تعديل الرسالة أو الرابط لا يُزعج أحداً.
    if (!(latest > previous)) return null;
    const body = cleanString(after.message, 500)
      || "حدِّث التطبيق من المتجر لتصلك المزايا والإصلاحات الجديدة.";
    await pushToTopic("تتوفّر نسخة أحدث من منبر ادكصهك", body, { type: "manual" });
    return null;
  });

exports.onSubcategoryCreated = functions.firestore
  .document("subcategories/{id}")
  .onCreate((snap) => {
    const d = unwrapLegacy(snap.data());
    return pushToTopic(
      "قسم فرعي جديد",
      cleanString(d.name, 180) || "أُضيف قسم فرعي جديد",
      { type: "subcategory", id: snap.id, subcategoryId: snap.id },
    );
  });

exports.onCategoryCreated = functions.firestore
  .document("categories/{id}")
  .onCreate((snap) => {
    const d = unwrapLegacy(snap.data());
    return pushToTopic(
      "قسم جديد",
      cleanString(d.name, 180) || "أُضيف قسم رئيسي جديد",
      { type: "category", id: snap.id, categoryId: snap.id },
    );
  });

exports.onBookCreated = functions.firestore
  .document("books/{id}")
  .onCreate((snap) => {
    const d = unwrapLegacy(snap.data());
    return pushToTopic(
      "كتاب جديد",
      cleanString(d.name, 180) || "أُضيف كتاب جديد",
      { type: "book", id: snap.id, bookId: snap.id },
    );
  });

exports.onLessonMilestone = functions.firestore
  .document("lessons/{id}")
  .onUpdate(async (change) => {
    const before = unwrapLegacy(change.before.data());
    const after = unwrapLegacy(change.after.data());
    const previousViews = Number(before.views || 0);
    const nextViews = Number(after.views || 0);
    if (nextViews <= previousViews) return null;
    const crossed = VIEW_MILESTONES.find(
      (milestone) => previousViews < milestone && nextViews >= milestone,
    );
    if (!crossed) return null;
    const lessonTitle = cleanString(after.title || "درس", 180);
    const authorEmail = normalizeEmail(after.createdByEmail || after.addedBy);
    const body = `الدرس «${lessonTitle}» وصل إلى ${crossed} استماع.`;
    // صاحب الدرس يُخاطَب باسم «درسك» (تنبيهاً ودفعاً)، والبقية بالنص العام.
    const tasks = [
      writeAdminAlert("", "🎉 إنجاز استماع جديد", body, {
        type: "engagement",
        lessonId: change.after.id,
        refId: change.after.id,
        excludeEmail: authorEmail,
      }),
      pushToAdminsFiltered("🎉 إنجاز استماع جديد", body, {
        type: "engagement",
        lessonId: change.after.id,
        refId: change.after.id,
      }, { excludeEmail: authorEmail }),
    ];
    if (authorEmail) {
      tasks.push(writeAdminAlert(authorEmail, "🎉 إنجاز جديد لدرسك", body, {
        type: "engagement",
        lessonId: change.after.id,
        refId: change.after.id,
      }));
      tasks.push(pushToAdminsFiltered("🎉 إنجاز جديد لدرسك", body, {
        type: "engagement",
        lessonId: change.after.id,
        refId: change.after.id,
      }, { targetEmail: authorEmail }));
    }
    await Promise.all(tasks);
    return null;
  });

async function dispatchScheduledLesson(doc, origin) {
  const dispatchRef = db.collection("notification_dispatches")
    .doc(`scheduled_lesson_${doc.id}`);
  const now = Date.now();
  const claimed = await db.runTransaction(async (tx) => {
    const [dispatchSnap, lessonSnap] = await Promise.all([
      tx.get(dispatchRef),
      tx.get(doc.ref),
    ]);
    if (!lessonSnap.exists) return false;
    const lesson = unwrapLegacy(lessonSnap.data());
    const dispatch = dispatchSnap.data() || {};
    if (lesson.publishNotified === true || dispatch.status === "sent") return false;
    if (dispatch.status === "claimed" && Number(dispatch.leaseUntil || 0) > now) {
      return false;
    }
    tx.set(dispatchRef, {
      lessonId: doc.id,
      status: "claimed",
      origin,
      claimedAt: admin.firestore.FieldValue.serverTimestamp(),
      leaseUntil: now + 5 * 60 * 1000,
      attempts: Number(dispatch.attempts || 0) + 1,
    }, { merge: true });
    return true;
  });
  if (!claimed) return false;
  const fresh = await doc.ref.get();
  if (!fresh.exists) return false;
  const value = unwrapLegacy(fresh.data());
  const title = cleanString(value.title || value.name, 180);
  const subId = cleanString(value.subcategoryId, 160);
  try {
    if (subId) {
      await pushToCondition(
        "درس جديد",
        title || "أُضيف درس صوتي جديد",
        {
          type: "lesson",
          id: doc.id,
          lessonId: doc.id,
          subId,
          subcategoryId: subId,
        },
        `'${TOPIC}' in topics || 'sec_${subId}' in topics`,
      );
    } else {
      await pushToTopic(
        "درس جديد",
        title || "أُضيف درس صوتي جديد",
        { type: "lesson", id: doc.id, lessonId: doc.id },
      );
    }
    const batch = db.batch();
    batch.set(dispatchRef, {
      status: "sent",
      sentAt: admin.firestore.FieldValue.serverTimestamp(),
      leaseUntil: 0,
    }, { merge: true });
    batch.update(doc.ref, {
      publishNotified: true,
      publishedAt: admin.firestore.FieldValue.serverTimestamp(),
    });
    await batch.commit();
    return true;
  } catch (error) {
    await dispatchRef.set({
      status: "failed",
      leaseUntil: 0,
      lastError: cleanString(error && error.message, 500),
      failedAt: admin.firestore.FieldValue.serverTimestamp(),
    }, { merge: true }).catch(() => {});
    throw error;
  }
}

exports.publishScheduledLessons = functions.pubsub
  .schedule("*/15 * * * *")
  .timeZone("Asia/Riyadh")
  .onRun(async () => {
    const nowIso = new Date().toISOString();
    const snap = await db.collection("lessons")
      .where("publishAt", "<=", nowIso)
      .get();
    // ⚠️ `publishAt` لا يُمسح بعد النشر إطلاقاً، فهذا الاستعلام يعيد **كل**
    // درس سبقت جدولته منذ بدء المشروع. بلا هذا الترشيح كانت الدالة تفتح
    // معاملةً (بقراءتين) لكل واحد منها كل ربع ساعة لتخلص إلى «مُشعَر أصلاً»
    // — نموّ بلا سقف في القراءات، وطريقٌ مؤكَّد إلى نفاد مهلة المُجدوِل حين
    // تكبر المكتبة. الترشيح محليّ فلا يحتاج فهرساً ولا يغيّر أي سلوك.
    const due = snap.docs.filter(
      (doc) => unwrapLegacy(doc.data()).publishNotified !== true,
    );
    await Promise.all(due.map(
      (doc) => dispatchScheduledLesson(doc, "scheduler")
        .catch((error) => console.error("scheduled publish failed", doc.id, error)),
    ));
    return null;
  });

exports.publishScheduledLesson = functions.https.onCall(async (data, context) => {
  const actor = await assertAuthorized(context);
  const lessonId = requireString(data && data.lessonId, "lessonId", 1, 180);
  const ref = db.collection("lessons").doc(lessonId);
  const snap = await ref.get();
  if (!snap.exists) {
    throw new functions.https.HttpsError("not-found", "الدرس غير موجود.");
  }
  if (unwrapLegacy(snap.data()).publishNotified !== true) {
    await ref.update({ publishAt: new Date().toISOString() });
  }
  const sent = await dispatchScheduledLesson(await ref.get(), `manual:${actor.email}`);
  await auditOwnerAction(actor.email, "publish_scheduled_lesson", lessonId, { sent });
  return { ok: true, id: lessonId, sent };
});

exports.weeklyDigest = functions.pubsub
  .schedule("0 9 * * 1")
  .timeZone("Asia/Riyadh")
  .onRun(async () => {
    const weekAgo = Date.now() - 7 * 24 * 60 * 60 * 1000;
    const snap = await db.collection("lessons").get();
    let newCount = 0;
    let totalViews = 0;
    snap.forEach((doc) => {
      const d = unwrapLegacy(doc.data());
      totalViews += Number(d.views || 0);
      let createdAt = 0;
      if (d.createdAtTs && typeof d.createdAtTs.toMillis === "function") {
        createdAt = d.createdAtTs.toMillis();
      } else {
        createdAt = Date.parse(d.createdAt || "") || 0;
      }
      if (createdAt >= weekAgo) newCount += 1;
    });
    const title = "📊 تقرير الأسبوع";
    const body = `دروس جديدة هذا الأسبوع: ${newCount} · إجمالي الاستماع: ${totalViews}.`;
    await writeAdminAlert("", title, body, { type: "weekly_digest" });
    await pushToAdmins(title, body, { type: "weekly_digest" });
    return null;
  });

// ─── الاستدعاءات العامة المحمية بـ App Check وحدود المعدل ───────────
exports.incrementLessonView = functions.https.onCall(async (data, context) => {
  assertAppCheck(context);
  const uid = assertSignedIn(context);
  const lessonId = requireString(data && data.lessonId, "lessonId", 1, 180);
  await consumeRateLimit({
    uid,
    action: "lesson-views-day",
    limit: 500,
    windowMs: 24 * 60 * 60 * 1000,
    minIntervalMs: 1200,
  });
  const perLessonRef = db.collection("private_rate_limits")
    .doc(hashId(`lesson-view:${uid}:${lessonId}`));
  const lessonRef = db.collection("lessons").doc(lessonId);
  const now = Date.now();
  const counted = await db.runTransaction(async (tx) => {
    const [rateSnap, lessonSnap] = await Promise.all([
      tx.get(perLessonRef),
      tx.get(lessonRef),
    ]);
    if (!lessonSnap.exists) {
      throw new functions.https.HttpsError("not-found", "الدرس غير موجود.");
    }
    const lastAt = Number((rateSnap.data() || {}).lastAt || 0);
    if (lastAt && now - lastAt < 30 * 1000) return false;
    const current = unwrapLegacy(lessonSnap.data());
    tx.set(perLessonRef, {
      uid,
      action: "lesson-view",
      lessonId,
      lastAt: now,
      expiresAt: now + 7 * 24 * 60 * 60 * 1000,
    });
    tx.update(lessonRef, { views: Number(current.views || 0) + 1 });
    return true;
  });
  return { ok: true, counted };
});

exports.sendFeedback = functions.https.onCall(async (data, context) => {
  assertAppCheck(context);
  const uid = assertSignedIn(context);
  await consumeRateLimit({
    uid,
    action: "feedback",
    limit: 12,
    windowMs: 24 * 60 * 60 * 1000,
    minIntervalMs: 10 * 1000,
  });
  const lessonId = requireString(data && data.lessonId, "lessonId", 1, 180);
  const type = requireString(data && data.type, "type", 1, 40);
  const allowedTypes = ["benefited", "audio_issue", "other", "copyright", "abuse"];
  if (!allowedTypes.includes(type)) {
    throw new functions.https.HttpsError("invalid-argument", "نوع الملاحظة غير صالح.");
  }
  const note = cleanString(data && data.note, 500);
  const lessonSnap = await db.collection("lessons").doc(lessonId).get();
  if (!lessonSnap.exists) {
    throw new functions.https.HttpsError("not-found", "الدرس غير موجود.");
  }
  // ⚠️ لا يُخزَّن المعرّف الخام: سياسة الخصوصية المنشورة تنصّ صراحةً على أن
  // الملاحظات والبلاغات لا تُربط بهويّة المرسِل. نخزّن بصمة أحاديّة الاتجاه
  // تكفي وحدها لحذف بيانات المستخدم عند طلبه (deleteMyData يجزّئ المعرّف
  // نفسه فيطابقها) ولا تصلح للتعرّف عليه ولا للربط بين بلاغاته وحسابه.
  const ref = await db.collection("feedback").add({
    uidHash: hashId(uid),
    lessonId,
    type,
    note,
    createdAt: admin.firestore.FieldValue.serverTimestamp(),
    createdAtMs: Date.now(),
  });
  const lesson = unwrapLegacy(lessonSnap.data());
  const lessonTitle = cleanString(lesson.title || "درس", 180);
  const authorEmail = normalizeEmail(lesson.createdByEmail || lesson.addedBy);
  const labels = {
    benefited: "أفاد مستمع بأنه انتفع بالدرس",
    audio_issue: "أبلغ مستمع عن مشكلة في الصوت",
    other: "أرسل مستمع ملاحظة على الدرس",
    copyright: "ورد بلاغ حقوق نشر على الدرس",
    abuse: "ورد بلاغ إساءة على الدرس",
  };
  const alertTitle = labels[type] || "تفاعل جديد مع درس";
  const alertBody = `${alertTitle}: «${lessonTitle}»${note ? ` — ${note}` : "."}`;
  // صاحب الدرس يُخاطَب باسم «درسك» (تنبيهاً ودفعاً)، والبقية بالنص العام —
  // بلا ازدواج إشعارات لأي أحد.
  const tasks = [
    writeAdminAlert("", alertTitle, alertBody, {
      type: "engagement",
      lessonId,
      refId: lessonId,
      feedbackId: ref.id,
      excludeEmail: authorEmail,
    }),
    pushToAdminsFiltered(alertTitle, alertBody, {
      type: "engagement",
      lessonId,
      refId: lessonId,
      feedbackId: ref.id,
    }, { excludeEmail: authorEmail }),
  ];
  if (authorEmail) {
    tasks.push(writeAdminAlert(authorEmail, "تفاعل جديد مع درسك", alertBody, {
      type: "engagement",
      lessonId,
      refId: lessonId,
      feedbackId: ref.id,
    }));
    tasks.push(pushToAdminsFiltered("تفاعل جديد مع درسك", alertBody, {
      type: "engagement",
      lessonId,
      refId: lessonId,
      feedbackId: ref.id,
    }, { targetEmail: authorEmail }));
  }
  await Promise.all(tasks);
  return { ok: true, feedbackId: ref.id };
});

exports.createSubmission = functions.https.onCall(async (data, context) => {
  assertAppCheck(context);
  const uid = assertSignedIn(context);

  const title = requireString(data && data.title, "title", 3, 120);
  const submitterName = cleanString(data && data.submitterName, 60);
  const note = cleanString(data && data.note, 500);
  const categoryId = cleanString(data && data.categoryId, 180);
  const categoryName = cleanString(data && data.categoryName, 180);
  const subcategoryId = cleanString(data && data.subcategoryId, 180);
  const subcategoryName = cleanString(data && data.subcategoryName, 180);
  const storagePath = requireString(data && data.storagePath, "storagePath", 1, 700);
  const fileName = cleanString(data && data.fileName, 255);
  const audioUrl = cleanString(data && data.audioUrl, 2500);
  const fcmToken = cleanString(data && data.fcmToken, 4096);
  const termsAcceptedAt = cleanString(data && data.termsAcceptedAt, 80);
  const parsedTermsAcceptedAt = Date.parse(termsAcceptedAt);
  const accepted = Boolean(termsAcceptedAt)
    && !Number.isNaN(parsedTermsAcceptedAt)
    && parsedTermsAcceptedAt <= Date.now() + 5 * 60 * 1000;
  // ⚠️ الإقرار **ليس شرطاً** لقبول المساهمة: المشرفون يتحقّقون من كل درس
  // بأنفسهم قبل النشر ولا يبنون قرارهم على ادّعاء المرسِل. كان هنا رفضٌ يمنع
  // الإرسال بلا إقرار فيبدو زرّ «إرسال للمراجعة» صامتاً بلا تفسير.
  // نسجّل ما أقرّ به المستخدم فعلاً ليظهر للمشرف في شاشة المراجعة.
  const rightsConfirmed = data && data.rightsConfirmed === true;
  const policyAccepted = data && data.contentPolicyAccepted === true;
  const requiredPrefix = `submissions/${uid}/`;
  if (!storagePath.startsWith(requiredPrefix) || storagePath.includes("..")) {
    throw new functions.https.HttpsError("permission-denied", "مسار الملف غير صالح.");
  }
  let metadata;
  try {
    [metadata] = await bucket.file(storagePath).getMetadata();
  } catch (error) {
    console.error("submission metadata failed", error);
    throw new functions.https.HttpsError("not-found", "ملف المساهمة غير موجود.");
  }
  const size = Number(metadata.size || 0);
  const contentType = String(metadata.contentType || "");
  if (size <= 0 || size > MAX_SUBMISSION_BYTES || !contentType.startsWith("audio/")) {
    throw new functions.https.HttpsError("invalid-argument", "ملف الصوت غير صالح.");
  }

  const pathParts = storagePath.split("/");
  const pathSubmissionId = pathParts.length >= 4 ? pathParts[2] : "";
  const requestedId = cleanString(
    data && data.submissionId || pathSubmissionId,
    180,
  );
  const ref = requestedId && /^[A-Za-z0-9_-]+$/.test(requestedId)
    ? db.collection("lesson_submissions").doc(requestedId)
    : db.collection("lesson_submissions").doc();
  if (!storagePath.includes(`/${ref.id}/`) && requestedId) {
    throw new functions.https.HttpsError(
      "invalid-argument",
      "معرف المساهمة لا يطابق مسار الملف.",
    );
  }
  const existing = await ref.get();
  if (existing.exists) {
    const existingData = existing.data() || {};
    if (existingData.uid === uid && existingData.storagePath === storagePath) {
      return { ok: true, id: ref.id, submissionId: ref.id, existing: true };
    }
    throw new functions.https.HttpsError("already-exists", "المساهمة موجودة مسبقاً.");
  }
  // «النص المشروح» الاختياري المرافق للمساهمة: نص/صور صفحات تُنشر مع
  // الدرس تلقائياً عند اعتماده. صوره تُرفع لمساحة اقتراحات النصوص بنفس
  // معرّف المساهمة، ويتحقق منها هنا كما في createTranscriptSubmission.
  const transcriptText = cleanString(data && data.transcriptText, 20000);
  const transcriptBookTitle = cleanString(data && data.transcriptBookTitle, 200);
  const transcriptSourceRef = cleanString(data && data.transcriptSourceRef, 300);
  const transcriptImagePaths = await validateTranscriptImages(
    data && data.transcriptImagePaths,
    `transcript_submissions/${uid}/${ref.id}/`,
  );
  await consumeRateLimit({
    uid,
    action: "submission",
    limit: 5,
    windowMs: 24 * 60 * 60 * 1000,
    minIntervalMs: 60 * 1000,
  });
  await ref.set({
    uid,
    submitterName,
    title,
    categoryId,
    categoryName,
    subcategoryId,
    subcategoryName,
    note,
    audioUrl,
    storagePath,
    fileName: fileName || storagePath.split("/").pop(),
    fileSize: size,
    contentType,
    fcmToken,
    status: "pending",
    rejectReason: "",
    // ما أقرّ به المرسِل فعلاً (لا قيمة ثابتة) — يظهر للمشرف عند المراجعة.
    rightsConfirmed,
    termsAccepted: policyAccepted,
    termsAcceptedAt,
    termsAcceptedAtTs: admin.firestore.FieldValue.serverTimestamp(),
    contentPolicyVersion: cleanString(data && data.contentPolicyVersion, 40) || "2026-07",
    transcriptText,
    transcriptBookTitle,
    transcriptSourceRef,
    transcriptImagePaths,
    createdAt: new Date().toISOString(),
    createdAtTs: admin.firestore.FieldValue.serverTimestamp(),
    createdAtMs: Date.now(),
  });
  return { ok: true, id: ref.id, submissionId: ref.id };
});

// ينشئ الدرس من هوية Firebase الموثقة، ولا يثق بأي createdBy يرسله العميل.
exports.createLesson = functions.https.onCall(async (data, context) => {
  const actor = await assertAuthorized(context);
  const input = data && data.lesson && typeof data.lesson === "object"
    ? data.lesson
    : data || {};
  const title = requireString(input.title || input.name, "title", 2, 180);
  const audioUrl = requireString(input.audioUrl || input.url, "audioUrl", 8, 2500);
  try {
    const parsed = new URL(audioUrl);
    if (!["https:", "http:"].includes(parsed.protocol)) throw new Error("protocol");
  } catch (_) {
    throw new functions.https.HttpsError("invalid-argument", "رابط الصوت غير صالح.");
  }
  const publishAt = cleanString(input.publishAt, 80);
  if (publishAt && Number.isNaN(Date.parse(publishAt))) {
    throw new functions.https.HttpsError("invalid-argument", "موعد النشر غير صالح.");
  }
  const storagePath = cleanString(
    input.storagePath || input.audioStoragePath,
    700,
  );
  if (storagePath.startsWith("submissions/")) {
    throw new functions.https.HttpsError(
      "invalid-argument",
      "لا يجوز نشر درس مباشر من مجلد المساهمات الخاص.",
    );
  }
  const serverNowIso = new Date().toISOString();
  // زمن الإضافة: طابور الرفع دون اتصال يختم لحظة ضغط المشرف «رفع» ويرسلها
  // هنا، فيبقى ترتيب الدروس في التطبيق العام مطابقاً لترتيب إضافتها لا
  // لترتيب اكتمال رفعها. يُقبل فقط من حساب مخوَّل، وبتاريخ صالح غير
  // مستقبليّ ولا أقدم من 30 يوماً؛ وإلّا فزمن الخادم.
  const requestedCreatedAt = cleanString(input.createdAt, 40);
  const requestedMs = requestedCreatedAt ? Date.parse(requestedCreatedAt) : NaN;
  const MAX_BACKDATE_MS = 30 * 24 * 60 * 60 * 1000;
  const acceptableCreatedAt = !Number.isNaN(requestedMs) &&
    requestedMs <= Date.now() + 60 * 1000 &&
    requestedMs >= Date.now() - MAX_BACKDATE_MS;
  const nowIso = acceptableCreatedAt
    ? new Date(requestedMs).toISOString()
    : serverNowIso;
  const lessonData = {
    title,
    normalizedTitle: title.toLocaleLowerCase("ar").replace(/\s+/g, " ").trim(),
    audioUrl,
    categoryId: cleanString(input.categoryId, 180),
    categoryName: cleanString(input.categoryName, 180),
    subcategoryId: cleanString(input.subcategoryId, 180),
    subcategoryName: cleanString(input.subcategoryName, 180),
    description: cleanString(input.description, 3000),
    sheikhName: cleanString(input.sheikhName, 180),
    // مدّة التمييز: بانقضائها يسقط الدرس من «مختارات المنبر». غياب المدّة
    // مع featured=true = تمييز دائم.
    // ⚠️ درس بقي في طابور الرفع حتى انقضت مدّة تمييزه يجب أن يصل **غير
    // مميّز**؛ إسقاط المدّة وحدها كان يحوّله إلى مميّز إلى الأبد، وهو عكس
    // المقصود تماماً (وexpireFeaturedLessons لا تلمس ما لا مدّة له).
    ...(function () {
      if (input.featured !== true) return { featured: false };
      const until = cleanString(input.featuredUntil, 40);
      if (!until) return { featured: true }; // تمييز دائم مقصود.
      const ms = Date.parse(until);
      if (Number.isNaN(ms)) return { featured: true };
      if (ms <= Date.now()) return { featured: false }; // انقضت قبل الوصول.
      return { featured: true, featuredUntil: new Date(ms).toISOString() };
    })(),
    views: 0,
    createdAt: nowIso,
    createdAtTs: admin.firestore.FieldValue.serverTimestamp(),
    createdByUid: context.auth.uid,
    createdByEmail: actor.email,
    addedBy: actor.email,
    updatedByUid: context.auth.uid,
    updatedByEmail: actor.email,
    updatedAt: admin.firestore.FieldValue.serverTimestamp(),
    publishNotified: false,
  };
  if (storagePath) {
    lessonData.storagePath = storagePath;
    lessonData.audioStoragePath = storagePath;
  }
  if (publishAt) lessonData.publishAt = new Date(publishAt).toISOString();
  if (Number.isFinite(Number(input.duration))) {
    lessonData.duration = Number(input.duration);
  }
  if (Number.isFinite(Number(input.durationSeconds))) {
    lessonData.durationSeconds = Number(input.durationSeconds);
  }
  if (Number.isFinite(Number(input.order))) lessonData.order = Number(input.order);
  const optionalStrings = [
    "source", "sourceUrl", "bookId", "imageUrl", "transcript",
  ];
  optionalStrings.forEach((key) => {
    const value = cleanString(input[key], key === "transcript" ? 10000 : 2500);
    if (value) lessonData[key] = value;
  });
  if (Array.isArray(input.tags)) {
    lessonData.tags = input.tags
      .slice(0, 20)
      .map((item) => cleanString(item, 60))
      .filter(Boolean);
  }
  // منع التكرار: طابور الرفع يعيد المحاولة إن ضاع الردّ بعد نجاح الكتابة
  // (حالة معتادة على شبكة ضعيفة)، فبلا مفتاح ثابت يُنشأ درسان متطابقان.
  // المفتاح معرّف العنصر في الطابور، ومعرّف الوثيقة يُشتقّ منه حتميّاً.
  const clientKey = cleanString(input.clientKey, 120);
  const lessonRef = clientKey
    ? db.collection("lessons").doc(
      crypto.createHash("sha1")
        .update(`${context.auth.uid}:${clientKey}`)
        .digest("hex")
        .slice(0, 20),
    )
    : db.collection("lessons").doc();
  if (clientKey) {
    const existing = await lessonRef.get();
    if (existing.exists) return { ok: true, id: lessonRef.id, duplicate: true };
  }
  await lessonRef.set(lessonData);
  await auditOwnerAction(actor.email, "create_lesson", lessonRef.id, {
    title,
    scheduled: Boolean(publishAt),
  });
  return { ok: true, id: lessonRef.id };
});

async function anonymizePublishedLessons(uid) {
  const snap = await db.collection("lessons")
    .where("submittedByUid", "==", uid)
    .get();
  for (let offset = 0; offset < snap.docs.length; offset += 400) {
    const batch = db.batch();
    snap.docs.slice(offset, offset + 400).forEach((doc) => {
      batch.update(doc.ref, {
        submittedByUid: admin.firestore.FieldValue.delete(),
        submittedBy: admin.firestore.FieldValue.delete(),
        contributorDeletedAt: admin.firestore.FieldValue.serverTimestamp(),
      });
    });
    await batch.commit();
  }
  return snap.size;
}

async function deleteQuery(query, beforeDelete) {
  const snap = await query.get();
  for (const doc of snap.docs) {
    if (beforeDelete) await beforeDelete(doc.data() || {});
  }
  for (let offset = 0; offset < snap.docs.length; offset += 400) {
    const batch = db.batch();
    snap.docs.slice(offset, offset + 400).forEach((doc) => batch.delete(doc.ref));
    await batch.commit();
  }
  return snap.size;
}

exports.deleteMyData = functions.runWith({ timeoutSeconds: 120, memory: "512MB" })
  .https.onCall(async (_data, context) => {
    assertAppCheck(context);
    const uid = assertSignedIn(context);
    await consumeRateLimit({
      uid,
      action: "delete-my-data",
      limit: 2,
      windowMs: 24 * 60 * 60 * 1000,
      minIntervalMs: 60 * 1000,
    });
    const submissions = await deleteQuery(
      db.collection("lesson_submissions").where("uid", "==", uid),
      async (value) => {
        if (value.storagePath) await deleteFileIfExists(value.storagePath);
        const transcriptImages = Array.isArray(value.transcriptImagePaths)
          ? value.transcriptImagePaths
          : [];
        for (const path of transcriptImages) {
          await deleteFileIfExists(path).catch(() => {});
        }
      },
    );
    const transcriptSubmissions = await deleteQuery(
      db.collection("transcript_submissions").where("uid", "==", uid),
      async (value) => {
        const paths = Array.isArray(value.imagePaths) ? value.imagePaths : [];
        for (const path of paths) await deleteFileIfExists(path).catch(() => {});
      },
    );
    // إخفاء هوية المساهم في النصوص المعتمدة المنشورة (كما في الدروس).
    const transcriptsSnap = await db.collection("lesson_transcripts")
      .where("contributorUid", "==", uid)
      .get();
    for (let offset = 0; offset < transcriptsSnap.docs.length; offset += 400) {
      const batch = db.batch();
      transcriptsSnap.docs.slice(offset, offset + 400).forEach((doc) => {
        batch.update(doc.ref, {
          contributorUid: admin.firestore.FieldValue.delete(),
          contributorName: admin.firestore.FieldValue.delete(),
          contributorDeletedAt: admin.firestore.FieldValue.serverTimestamp(),
        });
      });
      await batch.commit();
    }
    // البلاغات تُخزَّن ببصمة مجزّأة لا بالمعرّف الخام (سياسة الخصوصية)؛
    // والاستعلام بالمعرّف الخام يبقى لحذف ما كُتب قبل هذا التغيير.
    const feedback = (await deleteQuery(
      db.collection("feedback").where("uidHash", "==", hashId(uid)),
    )) + (await deleteQuery(
      db.collection("feedback").where("uid", "==", uid),
    ));
    const anonymizedLessons = await anonymizePublishedLessons(uid);
    await db.collection("admin_device_tokens").doc(uid).delete().catch(() => {});
    const rates = await deleteQuery(
      db.collection("private_rate_limits").where("uid", "==", uid),
    );
    await admin.auth().deleteUser(uid).catch((error) => {
      if (error.code !== "auth/user-not-found") throw error;
    });
    return {
      ok: true,
      deleted: { submissions, transcriptSubmissions, feedback, rates },
      anonymizedLessons,
      anonymizedTranscripts: transcriptsSnap.size,
    };
  });

// ─── أدوات الملفات والمساهمات الإدارية الذرية ───────────────────────
function storagePathFromUrl(pathOrUrl) {
  const value = String(pathOrUrl || "").trim();
  if (!value) return "";
  if (!/^https?:/i.test(value)) return value.replace(/^\/+/, "");
  try {
    const url = new URL(value);
    const marker = "/o/";
    const index = url.pathname.indexOf(marker);
    if (index >= 0) return decodeURIComponent(url.pathname.slice(index + marker.length));
  } catch (_) {
    return "";
  }
  return "";
}

async function deleteFileIfExists(pathOrUrl) {
  const path = storagePathFromUrl(pathOrUrl);
  if (!path) return false;
  try {
    await bucket.file(path).delete({ ignoreNotFound: true });
    return true;
  } catch (error) {
    if (error.code === 404) return true;
    console.error("storage delete failed", path, error);
    throw error;
  }
}

function safeFileName(value) {
  const original = cleanString(value, 255) || "lesson.mp3";
  return original.replace(/[^\p{L}\p{N}._-]+/gu, "_");
}

async function copySubmissionAudio(sourcePath, lessonId, fileName) {
  const source = bucket.file(sourcePath);
  const [metadata] = await source.getMetadata();
  const destinationPath = `lessons/${lessonId}/${safeFileName(fileName)}`;
  const destination = bucket.file(destinationPath);
  await source.copy(destination);
  const token = crypto.randomUUID();
  await destination.setMetadata({
    contentType: metadata.contentType || "audio/mpeg",
    cacheControl: "public,max-age=3600",
    metadata: {
      firebaseStorageDownloadTokens: token,
      sourceSubmissionPath: sourcePath,
    },
  });
  const audioUrl = `https://firebasestorage.googleapis.com/v0/b/${encodeURIComponent(bucket.name)}`
    + `/o/${encodeURIComponent(destinationPath)}?alt=media&token=${token}`;
  return { destinationPath, audioUrl };
}

exports.approveSubmission = functions.runWith({ timeoutSeconds: 120, memory: "512MB" })
  .https.onCall(async (data, context) => {
    const actor = await assertAuthorized(context);
    const submissionId = requireString(
      data && data.submissionId,
      "submissionId",
      1,
      180,
    );
    const submissionRef = db.collection("lesson_submissions").doc(submissionId);
    const firstSnap = await submissionRef.get();
    if (!firstSnap.exists) {
      throw new functions.https.HttpsError("not-found", "المساهمة غير موجودة.");
    }
    const original = firstSnap.data() || {};
    if (original.status !== "pending") {
      if (["approved", "approved_edited"].includes(original.status)
          && original.publishedLessonId) {
        return {
          ok: true,
          id: original.publishedLessonId,
          lessonId: original.publishedLessonId,
          storagePath: cleanString(original.publishedStoragePath, 700),
          alreadyApproved: true,
        };
      }
      throw new functions.https.HttpsError(
        "failed-precondition",
        "سبق حسم هذه المساهمة.",
      );
    }
    const title = requireString(
      data && data.title || original.title,
      "title",
      3,
      120,
    );
    const categoryId = cleanString(data && data.categoryId || original.categoryId, 180);
    const categoryName = cleanString(data && data.categoryName || original.categoryName, 180);
    const subcategoryId = cleanString(
      data && data.subcategoryId || original.subcategoryId,
      180,
    );
    const subcategoryName = cleanString(
      data && data.subcategoryName || original.subcategoryName,
      180,
    );
    const sourcePath = requireString(original.storagePath, "storagePath", 1, 700);
    const lessonRef = db.collection("lessons").doc();
    let published;
    try {
      published = await copySubmissionAudio(
        sourcePath,
        lessonRef.id,
        original.fileName,
      );
      const edited = title !== cleanString(original.title, 120)
        || categoryId !== cleanString(original.categoryId, 180)
        || subcategoryId !== cleanString(original.subcategoryId, 180);
      const status = edited ? "approved_edited" : "approved";
      await db.runTransaction(async (tx) => {
        const currentSnap = await tx.get(submissionRef);
        if (!currentSnap.exists || currentSnap.data().status !== "pending") {
          throw new functions.https.HttpsError(
            "aborted",
            "حُسمت المساهمة من مشرف آخر.",
          );
        }
        tx.create(lessonRef, {
          title,
          normalizedTitle: title.toLocaleLowerCase("ar").replace(/\s+/g, " ").trim(),
          categoryId,
          categoryName,
          subcategoryId,
          subcategoryName,
          audioUrl: published.audioUrl,
          audioStoragePath: published.destinationPath,
          storagePath: published.destinationPath,
          views: 0,
          createdAt: new Date().toISOString(),
          createdAtTs: admin.firestore.FieldValue.serverTimestamp(),
          addedBy: actor.email,
          createdByUid: context.auth.uid,
          createdByEmail: actor.email,
          updatedByUid: context.auth.uid,
          updatedByEmail: actor.email,
          updatedAt: admin.firestore.FieldValue.serverTimestamp(),
          sourceSubmissionId: submissionId,
          submittedByUid: cleanString(original.uid, 180),
          submittedBy: cleanString(original.submitterName, 60),
        });
        tx.update(submissionRef, {
          status,
          publishedLessonId: lessonRef.id,
          publishedTitle: title,
          publishedCategoryName: categoryName,
          publishedSubcategoryName: subcategoryName,
          publishedStoragePath: published.destinationPath,
          decidedBy: actor.email,
          decidedAt: new Date().toISOString(),
          decidedAtTs: admin.firestore.FieldValue.serverTimestamp(),
          cleanupPending: false,
        });
      });
    } catch (error) {
      if (published && published.destinationPath) {
        await deleteFileIfExists(published.destinationPath).catch(() => {});
      }
      throw error;
    }
    try {
      await deleteFileIfExists(sourcePath);
    } catch (_) {
      await submissionRef.update({ cleanupPending: true }).catch(() => {});
    }
    // «النص المشروح» المرافق (إن أُرفق): يُنشر مع الدرس فور اعتماده.
    // فشله لا يُسقط نشر الدرس نفسه — يُسجَّل ويستطيع المشرف إضافته يدوياً.
    const transcriptText = cleanString(original.transcriptText, 20000);
    const transcriptImages = Array.isArray(original.transcriptImagePaths)
      ? original.transcriptImagePaths
      : [];
    if (transcriptText.length >= 10 || transcriptImages.length) {
      try {
        const publishedImages = [];
        for (let index = 0; index < transcriptImages.length; index += 1) {
          publishedImages.push(
            await publishTranscriptImage(transcriptImages[index], lessonRef.id, index),
          );
        }
        await db.collection("lesson_transcripts").doc(lessonRef.id).set({
          lessonId: lessonRef.id,
          lessonTitle: title,
          text: transcriptText,
          bookTitle: cleanString(original.transcriptBookTitle, 200),
          sourceRef: cleanString(original.transcriptSourceRef, 300),
          images: publishedImages,
          contributorUid: cleanString(original.uid, 180),
          contributorName: cleanString(original.submitterName, 60),
          sourceSubmissionId: submissionId,
          updatedBy: actor.email,
          updatedAt: admin.firestore.FieldValue.serverTimestamp(),
          updatedAtMs: Date.now(),
          createdAt: new Date().toISOString(),
        });
        for (const path of transcriptImages) {
          await deleteFileIfExists(path).catch(() => {});
        }
      } catch (error) {
        console.error("transcript publish with lesson failed", submissionId, error);
      }
    }
    await auditOwnerAction(
      actor.email,
      "approve_submission",
      submissionId,
      { lessonId: lessonRef.id },
    );
    return {
      ok: true,
      id: lessonRef.id,
      lessonId: lessonRef.id,
      audioUrl: published.audioUrl,
      storagePath: published.destinationPath,
    };
  });

exports.rejectSubmission = functions.https.onCall(async (data, context) => {
  const actor = await assertAuthorized(context);
  const submissionId = requireString(
    data && data.submissionId,
    "submissionId",
    1,
    180,
  );
  const reason = requireString(data && data.reason, "reason", 2, 300);
  const ref = db.collection("lesson_submissions").doc(submissionId);
  let storagePath = "";
  let transcriptImages = [];
  await db.runTransaction(async (tx) => {
    const snap = await tx.get(ref);
    if (!snap.exists) {
      throw new functions.https.HttpsError("not-found", "المساهمة غير موجودة.");
    }
    const value = snap.data() || {};
    if (value.status !== "pending") {
      if (value.status === "rejected") return;
      throw new functions.https.HttpsError(
        "failed-precondition",
        "سبق حسم هذه المساهمة.",
      );
    }
    storagePath = cleanString(value.storagePath, 700);
    transcriptImages = Array.isArray(value.transcriptImagePaths)
      ? value.transcriptImagePaths
      : [];
    tx.update(ref, {
      status: "rejected",
      rejectReason: reason,
      decidedBy: actor.email,
      decidedAt: new Date().toISOString(),
      decidedAtTs: admin.firestore.FieldValue.serverTimestamp(),
      cleanupPending: Boolean(storagePath) || transcriptImages.length > 0,
    });
  });
  if (storagePath || transcriptImages.length) {
    try {
      if (storagePath) await deleteFileIfExists(storagePath);
      for (const path of transcriptImages) await deleteFileIfExists(path);
      await ref.update({ cleanupPending: false });
    } catch (_) {
      // تبقى cleanupPending=true لإعادة المحاولة الآمنة لاحقاً.
    }
  }
  await auditOwnerAction(actor.email, "reject_submission", submissionId, { reason });
  return { ok: true, id: submissionId };
});

async function deleteSubmissionHandler(data, context) {
  const actor = await assertAuthorized(context);
  const submissionId = requireString(
    data && data.submissionId,
    "submissionId",
    1,
    180,
  );
  const ref = db.collection("lesson_submissions").doc(submissionId);
  const snap = await ref.get();
  if (!snap.exists) return { ok: true, alreadyDeleted: true };
  const value = snap.data() || {};
  if (value.storagePath) await deleteFileIfExists(value.storagePath);
  const transcriptImages = Array.isArray(value.transcriptImagePaths)
    ? value.transcriptImagePaths
    : [];
  for (const path of transcriptImages) await deleteFileIfExists(path).catch(() => {});
  await ref.delete();
  await auditOwnerAction(actor.email, "delete_submission", submissionId, {});
  return { ok: true };
}

exports.deleteSubmission = functions.https.onCall(deleteSubmissionHandler);
// اسم بديل تستدعيه نسخ لوحة الإدارة المثبتة قبل توحيد الاسم — أبقه منشوراً.
exports.deleteSubmissionRecord = functions.https.onCall(deleteSubmissionHandler);

exports.deleteMySubmission = functions.https.onCall(async (data, context) => {
  assertAppCheck(context);
  const uid = assertSignedIn(context);
  const submissionId = requireString(
    data && data.submissionId,
    "submissionId",
    1,
    180,
  );
  const ref = db.collection("lesson_submissions").doc(submissionId);
  const snap = await ref.get();
  if (!snap.exists) return { ok: true, alreadyDeleted: true };
  const value = snap.data() || {};
  if (value.uid !== uid || value.status !== "pending") {
    throw new functions.https.HttpsError("permission-denied", "لا يمكن حذف هذا الطلب.");
  }
  if (value.storagePath) await deleteFileIfExists(value.storagePath);
  const transcriptImages = Array.isArray(value.transcriptImagePaths)
    ? value.transcriptImagePaths
    : [];
  for (const path of transcriptImages) await deleteFileIfExists(path).catch(() => {});
  await ref.delete();
  return { ok: true };
});

// ─── النص المشروح: المتن/المقطع الذي تشرحه الصوتية ─────────────────
// وثيقة واحدة لكل درس في lesson_transcripts (معرّفها = معرّف الدرس) تُجلب
// عند فتح المشغّل فقط، فلا تُثقل مزامنة مجموعة lessons الكاملة إطلاقاً.
// اقتراحات المستمعين تمرّ عبر transcript_submissions بنفس دورة «شارك درساً».
const TRANSCRIPTS_COLLECTION = "lesson_transcripts";
const TRANSCRIPT_SUBMISSIONS_COLLECTION = "transcript_submissions";
const MAX_TRANSCRIPT_CHARS = 20000;
const MAX_TRANSCRIPT_IMAGES = 4;
const MAX_TRANSCRIPT_IMAGE_BYTES = 10 * 1024 * 1024;
const MIN_TRANSCRIPT_TEXT_CHARS = 10;

async function validateTranscriptImages(paths, requiredPrefix) {
  const list = Array.isArray(paths) ? paths.slice(0, MAX_TRANSCRIPT_IMAGES) : [];
  const cleaned = [];
  for (const raw of list) {
    const path = cleanString(raw, 700);
    if (!path || !path.startsWith(requiredPrefix) || path.includes("..")) {
      throw new functions.https.HttpsError("permission-denied", "مسار صورة غير صالح.");
    }
    let metadata;
    try {
      [metadata] = await bucket.file(path).getMetadata();
    } catch (_) {
      throw new functions.https.HttpsError("not-found", "صورة مرفقة غير موجودة.");
    }
    const size = Number(metadata.size || 0);
    const contentType = String(metadata.contentType || "");
    if (size <= 0 || size > MAX_TRANSCRIPT_IMAGE_BYTES
        || !contentType.startsWith("image/")) {
      throw new functions.https.HttpsError("invalid-argument", "صورة مرفقة غير صالحة.");
    }
    if (!cleaned.includes(path)) cleaned.push(path);
  }
  return cleaned;
}

function buildTokenUrl(path, token) {
  return `https://firebasestorage.googleapis.com/v0/b/${encodeURIComponent(bucket.name)}`
    + `/o/${encodeURIComponent(path)}?alt=media&token=${token}`;
}

async function ensureImageDownloadUrl(path) {
  const file = bucket.file(path);
  const [metadata] = await file.getMetadata();
  let token = String(
    (metadata.metadata || {}).firebaseStorageDownloadTokens || "",
  ).split(",")[0].trim();
  if (!token) {
    token = crypto.randomUUID();
    await file.setMetadata({
      metadata: { firebaseStorageDownloadTokens: token },
    });
  }
  return buildTokenUrl(path, token);
}

async function publishTranscriptImage(sourcePath, lessonId, index) {
  const source = bucket.file(sourcePath);
  const [metadata] = await source.getMetadata();
  const baseName = safeFileName(sourcePath.split("/").pop() || `page_${index + 1}.jpg`);
  const destinationPath = `lesson_transcripts/${lessonId}/${Date.now()}_${index}_${baseName}`;
  const destination = bucket.file(destinationPath);
  await source.copy(destination);
  const token = crypto.randomUUID();
  await destination.setMetadata({
    contentType: metadata.contentType || "image/jpeg",
    cacheControl: "public,max-age=86400",
    metadata: {
      firebaseStorageDownloadTokens: token,
      sourceSubmissionPath: sourcePath,
    },
  });
  return { path: destinationPath, url: buildTokenUrl(destinationPath, token) };
}

exports.createTranscriptSubmission = functions.https.onCall(async (data, context) => {
  assertAppCheck(context);
  const uid = assertSignedIn(context);
  const lessonId = requireString(data && data.lessonId, "lessonId", 1, 180);
  const lessonSnap = await db.collection("lessons").doc(lessonId).get();
  if (!lessonSnap.exists) {
    throw new functions.https.HttpsError("not-found", "الدرس غير موجود.");
  }
  const lesson = unwrapLegacy(lessonSnap.data());
  const text = cleanString(data && data.text, MAX_TRANSCRIPT_CHARS);
  const bookTitle = cleanString(data && data.bookTitle, 200);
  const sourceRef = cleanString(data && data.sourceRef, 300);
  const note = cleanString(data && data.note, 500);
  const submitterName = cleanString(data && data.submitterName, 60);
  const fcmToken = cleanString(data && data.fcmToken, 4096);
  const requiredPrefix = `transcript_submissions/${uid}/`;
  const rawImagePaths = Array.isArray(data && data.imagePaths) ? data.imagePaths : [];
  const firstImagePath = cleanString(rawImagePaths[0], 700);
  const pathParts = firstImagePath.split("/");
  const pathSubmissionId = pathParts.length >= 4 ? pathParts[2] : "";
  const requestedId = cleanString(
    (data && data.submissionId) || pathSubmissionId,
    180,
  );
  const ref = requestedId && /^[A-Za-z0-9_-]+$/.test(requestedId)
    ? db.collection(TRANSCRIPT_SUBMISSIONS_COLLECTION).doc(requestedId)
    : db.collection(TRANSCRIPT_SUBMISSIONS_COLLECTION).doc();
  // كل الصور يجب أن تكون داخل مجلد هذه المساهمة تحديداً (لا مجلد آخر للمستخدم).
  const imagePaths = await validateTranscriptImages(
    rawImagePaths,
    `${requiredPrefix}${ref.id}/`,
  );
  if (text.length < MIN_TRANSCRIPT_TEXT_CHARS && !imagePaths.length) {
    throw new functions.https.HttpsError(
      "invalid-argument",
      "أرفق نص المقطع أو صورة صفحة واحدة على الأقل.",
    );
  }
  const existing = await ref.get();
  if (existing.exists) {
    const value = existing.data() || {};
    if (value.uid === uid && value.lessonId === lessonId) {
      return { ok: true, id: ref.id, submissionId: ref.id, existing: true };
    }
    throw new functions.https.HttpsError("already-exists", "المساهمة موجودة مسبقاً.");
  }
  await consumeRateLimit({
    uid,
    action: "transcript_submission",
    limit: 5,
    windowMs: 24 * 60 * 60 * 1000,
    minIntervalMs: 60 * 1000,
  });
  await ref.set({
    uid,
    submitterName,
    lessonId,
    lessonTitle: cleanString(lesson.title || lesson.name, 160),
    text,
    bookTitle,
    sourceRef,
    note,
    imagePaths,
    fcmToken,
    status: "pending",
    rejectReason: "",
    createdAt: new Date().toISOString(),
    createdAtTs: admin.firestore.FieldValue.serverTimestamp(),
    createdAtMs: Date.now(),
  });
  return { ok: true, id: ref.id, submissionId: ref.id };
});

exports.approveTranscriptSubmission = functions
  .runWith({ timeoutSeconds: 120, memory: "512MB" })
  .https.onCall(async (data, context) => {
    const actor = await assertAuthorized(context);
    const submissionId = requireString(
      data && data.submissionId,
      "submissionId",
      1,
      180,
    );
    const submissionRef = db.collection(TRANSCRIPT_SUBMISSIONS_COLLECTION)
      .doc(submissionId);
    const firstSnap = await submissionRef.get();
    if (!firstSnap.exists) {
      throw new functions.https.HttpsError("not-found", "المساهمة غير موجودة.");
    }
    const original = firstSnap.data() || {};
    if (original.status !== "pending") {
      if (["approved", "approved_edited"].includes(original.status)) {
        return { ok: true, lessonId: original.lessonId, alreadyApproved: true };
      }
      throw new functions.https.HttpsError(
        "failed-precondition",
        "سبق حسم هذه المساهمة.",
      );
    }
    const lessonId = requireString(original.lessonId, "lessonId", 1, 180);
    const text = cleanString(
      data && data.text !== undefined ? data.text : original.text,
      MAX_TRANSCRIPT_CHARS,
    );
    const bookTitle = cleanString(
      data && data.bookTitle !== undefined ? data.bookTitle : original.bookTitle,
      200,
    );
    const sourceRef = cleanString(
      data && data.sourceRef !== undefined ? data.sourceRef : original.sourceRef,
      300,
    );
    const keepImages = !(data && data.keepImages === false);
    const sourceImages = keepImages
      ? (Array.isArray(original.imagePaths) ? original.imagePaths : [])
      : [];
    if (text.length < MIN_TRANSCRIPT_TEXT_CHARS && !sourceImages.length) {
      throw new functions.https.HttpsError(
        "invalid-argument",
        "لا يمكن اعتماد نص فارغ بلا صور.",
      );
    }
    const transcriptRef = db.collection(TRANSCRIPTS_COLLECTION).doc(lessonId);
    const published = [];
    let previousImagePaths = [];
    try {
      for (let index = 0; index < sourceImages.length; index += 1) {
        published.push(
          await publishTranscriptImage(sourceImages[index], lessonId, index),
        );
      }
      const edited = text !== cleanString(original.text, MAX_TRANSCRIPT_CHARS)
        || bookTitle !== cleanString(original.bookTitle, 200)
        || sourceRef !== cleanString(original.sourceRef, 300)
        || (!keepImages
          && Array.isArray(original.imagePaths)
          && original.imagePaths.length > 0);
      const status = edited ? "approved_edited" : "approved";
      const nowIso = new Date().toISOString();
      await db.runTransaction(async (tx) => {
        const [currentSnap, transcriptSnap] = await Promise.all([
          tx.get(submissionRef),
          tx.get(transcriptRef),
        ]);
        if (!currentSnap.exists || currentSnap.data().status !== "pending") {
          throw new functions.https.HttpsError(
            "aborted",
            "حُسمت المساهمة من مشرف آخر.",
          );
        }
        const previous = transcriptSnap.data() || {};
        previousImagePaths = (Array.isArray(previous.images) ? previous.images : [])
          .map((item) => item && item.path)
          .filter(Boolean);
        tx.set(transcriptRef, {
          lessonId,
          lessonTitle: cleanString(original.lessonTitle, 160),
          text,
          bookTitle,
          sourceRef,
          images: published,
          contributorUid: cleanString(original.uid, 180),
          contributorName: cleanString(original.submitterName, 60),
          sourceSubmissionId: submissionId,
          updatedBy: actor.email,
          updatedAt: admin.firestore.FieldValue.serverTimestamp(),
          updatedAtMs: Date.now(),
          createdAt: transcriptSnap.exists
            ? (previous.createdAt || nowIso)
            : nowIso,
        });
        tx.update(submissionRef, {
          status,
          publishedLessonId: lessonId,
          publishedTextPreview: text.slice(0, 200),
          decidedBy: actor.email,
          decidedAt: nowIso,
          decidedAtTs: admin.firestore.FieldValue.serverTimestamp(),
          cleanupPending: false,
        });
      });
      // الصور المعتمدة سابقاً واستُبدلت الآن تُحذف بعد نجاح المعاملة فقط.
      for (const path of previousImagePaths) {
        if (!published.some((item) => item.path === path)) {
          await deleteFileIfExists(path).catch(() => {});
        }
      }
    } catch (error) {
      for (const item of published) {
        await deleteFileIfExists(item.path).catch(() => {});
      }
      throw error;
    }
    const originalImages = Array.isArray(original.imagePaths)
      ? original.imagePaths
      : [];
    for (const path of originalImages) {
      try {
        await deleteFileIfExists(path);
      } catch (_) {
        await submissionRef.update({ cleanupPending: true }).catch(() => {});
      }
    }
    await auditOwnerAction(actor.email, "approve_transcript", submissionId, {
      lessonId,
    });
    return { ok: true, lessonId };
  });

exports.rejectTranscriptSubmission = functions.https.onCall(async (data, context) => {
  const actor = await assertAuthorized(context);
  const submissionId = requireString(
    data && data.submissionId,
    "submissionId",
    1,
    180,
  );
  const reason = requireString(data && data.reason, "reason", 2, 300);
  const ref = db.collection(TRANSCRIPT_SUBMISSIONS_COLLECTION).doc(submissionId);
  let imagePaths = [];
  await db.runTransaction(async (tx) => {
    const snap = await tx.get(ref);
    if (!snap.exists) {
      throw new functions.https.HttpsError("not-found", "المساهمة غير موجودة.");
    }
    const value = snap.data() || {};
    if (value.status !== "pending") {
      if (value.status === "rejected") return;
      throw new functions.https.HttpsError(
        "failed-precondition",
        "سبق حسم هذه المساهمة.",
      );
    }
    imagePaths = Array.isArray(value.imagePaths) ? value.imagePaths : [];
    tx.update(ref, {
      status: "rejected",
      rejectReason: reason,
      decidedBy: actor.email,
      decidedAt: new Date().toISOString(),
      decidedAtTs: admin.firestore.FieldValue.serverTimestamp(),
      cleanupPending: imagePaths.length > 0,
    });
  });
  if (imagePaths.length) {
    try {
      for (const path of imagePaths) await deleteFileIfExists(path);
      await ref.update({ cleanupPending: false });
    } catch (_) {
      // تبقى cleanupPending=true لإعادة المحاولة الآمنة لاحقاً.
    }
  }
  await auditOwnerAction(actor.email, "reject_transcript", submissionId, { reason });
  return { ok: true, id: submissionId };
});

exports.deleteTranscriptSubmission = functions.https.onCall(async (data, context) => {
  const actor = await assertAuthorized(context);
  const submissionId = requireString(
    data && data.submissionId,
    "submissionId",
    1,
    180,
  );
  const ref = db.collection(TRANSCRIPT_SUBMISSIONS_COLLECTION).doc(submissionId);
  const snap = await ref.get();
  if (!snap.exists) return { ok: true, alreadyDeleted: true };
  const value = snap.data() || {};
  const imagePaths = Array.isArray(value.imagePaths) ? value.imagePaths : [];
  for (const path of imagePaths) await deleteFileIfExists(path).catch(() => {});
  await ref.delete();
  await auditOwnerAction(actor.email, "delete_transcript_submission", submissionId, {});
  return { ok: true };
});

exports.deleteMyTranscriptSubmission = functions.https.onCall(async (data, context) => {
  assertAppCheck(context);
  const uid = assertSignedIn(context);
  const submissionId = requireString(
    data && data.submissionId,
    "submissionId",
    1,
    180,
  );
  const ref = db.collection(TRANSCRIPT_SUBMISSIONS_COLLECTION).doc(submissionId);
  const snap = await ref.get();
  if (!snap.exists) return { ok: true, alreadyDeleted: true };
  const value = snap.data() || {};
  if (value.uid !== uid || value.status !== "pending") {
    throw new functions.https.HttpsError("permission-denied", "لا يمكن حذف هذا الطلب.");
  }
  const imagePaths = Array.isArray(value.imagePaths) ? value.imagePaths : [];
  for (const path of imagePaths) await deleteFileIfExists(path).catch(() => {});
  await ref.delete();
  return { ok: true };
});

// إضافة/تعديل مباشر من لوحة الإدارة: الصور تكون قد رُفعت مسبقاً عبر SDK إلى
// lesson_transcripts/{lessonId}/ (قواعد التخزين تسمح بذلك للمشرفين فقط)،
// والخادم يتحقق منها ويولّد روابطها ويحذف اليتيم منها.
exports.upsertLessonTranscript = functions
  .runWith({ timeoutSeconds: 120, memory: "512MB" })
  .https.onCall(async (data, context) => {
    const actor = await assertAuthorized(context);
    const lessonId = requireString(data && data.lessonId, "lessonId", 1, 180);
    const transcriptRef = db.collection(TRANSCRIPTS_COLLECTION).doc(lessonId);
    const requiredPrefix = `lesson_transcripts/${lessonId}/`;
    if (data && data.remove === true) {
      await transcriptRef.delete();
      await bucket.deleteFiles({ prefix: requiredPrefix }).catch(() => {});
      await auditOwnerAction(actor.email, "delete_transcript", lessonId, {});
      return { ok: true, removed: true };
    }
    const lessonSnap = await db.collection("lessons").doc(lessonId).get();
    if (!lessonSnap.exists) {
      throw new functions.https.HttpsError("not-found", "الدرس غير موجود.");
    }
    const lesson = unwrapLegacy(lessonSnap.data());
    const text = cleanString(data && data.text, MAX_TRANSCRIPT_CHARS);
    const bookTitle = cleanString(data && data.bookTitle, 200);
    const sourceRef = cleanString(data && data.sourceRef, 300);
    const imagePaths = await validateTranscriptImages(
      data && data.imagePaths,
      requiredPrefix,
    );
    if (text.length < MIN_TRANSCRIPT_TEXT_CHARS && !imagePaths.length) {
      throw new functions.https.HttpsError(
        "invalid-argument",
        "أدخل نص المقطع أو أرفق صورة واحدة على الأقل.",
      );
    }
    const images = [];
    for (const path of imagePaths) {
      images.push({ path, url: await ensureImageDownloadUrl(path) });
    }
    // حذف صور المجلد التي لم تعد ضمن القائمة المرسلة (اليتيمة).
    try {
      const [existingFiles] = await bucket.getFiles({ prefix: requiredPrefix });
      for (const file of existingFiles) {
        if (!imagePaths.includes(file.name)) await file.delete().catch(() => {});
      }
    } catch (_) { /* تنظيف اختياري */ }
    const prevSnap = await transcriptRef.get();
    const previous = prevSnap.data() || {};
    const nowIso = new Date().toISOString();
    await transcriptRef.set({
      lessonId,
      lessonTitle: cleanString(lesson.title || lesson.name, 160),
      text,
      bookTitle,
      sourceRef,
      images,
      contributorUid: cleanString(previous.contributorUid, 180),
      contributorName: cleanString(previous.contributorName, 60),
      sourceSubmissionId: cleanString(previous.sourceSubmissionId, 180),
      updatedBy: actor.email,
      updatedAt: admin.firestore.FieldValue.serverTimestamp(),
      updatedAtMs: Date.now(),
      createdAt: prevSnap.exists ? (previous.createdAt || nowIso) : nowIso,
    });
    await auditOwnerAction(actor.email, "upsert_transcript", lessonId, {
      images: images.length,
    });
    return { ok: true, lessonId, images };
  });

// استخراج النص من صورة صفحة الكتاب (OCR عربي) — للمشرفين فقط، عبر Cloud
// Vision REST بهوية حساب خدمة الدوال. إن لم تكن الواجهة مفعّلة تُعاد رسالة
// إرشادية واضحة بدل فشل غامض.
exports.extractImageText = functions
  .runWith({ timeoutSeconds: 60, memory: "512MB" })
  .https.onCall(async (data, context) => {
    await assertAuthorized(context);
    const storagePath = requireString(data && data.storagePath, "storagePath", 1, 700);
    const allowed = storagePath.startsWith("transcript_submissions/")
      || storagePath.startsWith("lesson_transcripts/");
    if (!allowed || storagePath.includes("..")) {
      throw new functions.https.HttpsError("permission-denied", "مسار الصورة غير صالح.");
    }
    const file = bucket.file(storagePath);
    let metadata;
    try {
      [metadata] = await file.getMetadata();
    } catch (_) {
      throw new functions.https.HttpsError("not-found", "الصورة غير موجودة.");
    }
    const size = Number(metadata.size || 0);
    if (size <= 0 || size > MAX_TRANSCRIPT_IMAGE_BYTES
        || !String(metadata.contentType || "").startsWith("image/")) {
      throw new functions.https.HttpsError("invalid-argument", "الصورة غير صالحة للاستخراج.");
    }
    const [buffer] = await file.download();
    const { GoogleAuth } = require("google-auth-library");
    const auth = new GoogleAuth({
      scopes: ["https://www.googleapis.com/auth/cloud-platform"],
    });
    const client = await auth.getClient();
    let response;
    try {
      response = await client.request({
        url: "https://vision.googleapis.com/v1/images:annotate",
        method: "POST",
        data: {
          requests: [{
            image: { content: buffer.toString("base64") },
            features: [{ type: "DOCUMENT_TEXT_DETECTION" }],
            imageContext: { languageHints: ["ar"] },
          }],
        },
      });
    } catch (error) {
      console.error("vision request failed", error && error.message);
      throw new functions.https.HttpsError(
        "failed-precondition",
        "تعذّر استخراج النص. تأكد من تفعيل Cloud Vision API لمشروع mxqp-8d1e8 ثم أعد المحاولة.",
      );
    }
    const result = (((response.data || {}).responses || [])[0]) || {};
    if (result.error && result.error.message) {
      throw new functions.https.HttpsError(
        "internal",
        cleanString(result.error.message, 300) || "فشل استخراج النص.",
      );
    }
    const text = cleanString(
      (result.fullTextAnnotation || {}).text,
      MAX_TRANSCRIPT_CHARS,
    );
    return { ok: true, text };
  });

exports.onTranscriptSubmissionCreated = functions.firestore
  .document("transcript_submissions/{id}")
  .onCreate(async (snap) => {
    const d = snap.data() || {};
    const who = cleanString(d.submitterName, 60) || "مستمع";
    const lessonTitle = cleanString(d.lessonTitle, 160) || "درس";
    const hasImages = Array.isArray(d.imagePaths) && d.imagePaths.length > 0;
    const alertTitle = "اقتراح نص مشروح بانتظار المراجعة";
    const alertBody = hasImages
      ? `أرسل ${who} نص/صور المقطع المشروح لدرس «${lessonTitle}».`
      : `أرسل ${who} نص المقطع المشروح لدرس «${lessonTitle}».`;
    await Promise.all([
      writeAdminAlert("", alertTitle, alertBody, {
        type: "transcript",
        submissionId: snap.id,
        refId: snap.id,
        lessonId: cleanString(d.lessonId, 180),
      }),
      // وجهة اللوحة صريحة: تبويب «النصوص المشروحة» في شاشة المراجعة.
      pushToAdmins(alertTitle, alertBody, {
        type: "transcript",
        submissionId: snap.id,
        refId: snap.id,
        lessonId: cleanString(d.lessonId, 180),
        route: "submissions",
      }),
    ]);
    return null;
  });

exports.onTranscriptSubmissionDecided = functions.firestore
  .document("transcript_submissions/{id}")
  .onUpdate(async (change) => {
    const before = change.before.data() || {};
    const after = change.after.data() || {};
    if (before.status !== "pending" || after.status === "pending") return null;
    const token = cleanString(after.fcmToken, 4096);
    const lessonTitle = cleanString(after.lessonTitle, 160) || "الدرس";
    // معرّف الدرس المرتبط بالاقتراح — قد يخلو منه سجلّ قديم، وسلسلة فارغة
    // تُفسِد التوجيه (يسقط العميل على معرّف المساهمة فيبني شاشة ميتة)،
    // لذا لا يُرسل المفتاح إلا صالحاً، ويرافقه `route` صريح دائماً.
    const linkedLessonId = cleanString(after.lessonId, 180);
    if (after.status === "approved" || after.status === "approved_edited") {
      const edited = after.status === "approved_edited";
      const notificationTitle = edited
        ? "اعتُمد النص الذي أرسلته بعد المراجعة"
        : "اعتُمد النص الذي أرسلته";
      const notificationBody = `صار النص المشروح ظاهراً في درس «${lessonTitle}». شكراً لمساهمتك.`;
      const notificationData = {
        type: "transcript",
        id: change.after.id,
        refId: change.after.id,
        submissionId: change.after.id,
        result: after.status,
        route: linkedLessonId ? "lesson" : "my-submissions",
      };
      if (linkedLessonId) notificationData.lessonId = linkedLessonId;
      await Promise.all([
        clearAdminAlerts("transcript", change.after.id),
        writeUserNotification(after.uid, notificationTitle, notificationBody, notificationData),
        pushToToken(token, notificationTitle, notificationBody, notificationData),
      ]);
      return null;
    }
    if (after.status === "rejected") {
      const reason = cleanString(after.rejectReason, 300);
      const notificationTitle = "نتيجة مراجعة النص المقترح";
      const notificationBody = reason
        ? `لم يُعتمد نص «${lessonTitle}»: ${reason}`
        : `لم يُعتمد نص «${lessonTitle}».`;
      // ⛳ الوجهة «مساهماتي» صراحةً: هنا يقرأ المستمع سبب عدم الاعتماد.
      const notificationData = {
        type: "transcript",
        id: change.after.id,
        refId: change.after.id,
        submissionId: change.after.id,
        result: "rejected",
        route: "my-submissions",
      };
      if (linkedLessonId) notificationData.lessonId = linkedLessonId;
      await Promise.all([
        clearAdminAlerts("transcript", change.after.id),
        writeUserNotification(after.uid, notificationTitle, notificationBody, notificationData),
        pushToToken(token, notificationTitle, notificationBody, notificationData),
      ]);
      return null;
    }
    return null;
  });

async function queueStorageCleanup(paths, reason, targetId) {
  const unique = [...new Set((paths || []).map(storagePathFromUrl).filter(Boolean))];
  if (!unique.length) return "";
  const ref = await db.collection("storage_cleanup_jobs").add({
    paths: unique,
    reason,
    targetId: targetId || "",
    status: "pending",
    attempts: 0,
    createdAt: admin.firestore.FieldValue.serverTimestamp(),
    createdAtMs: Date.now(),
  });
  return ref.id;
}

function lessonStoragePaths(value) {
  return [...new Set([
    value && value.storagePath,
    value && value.audioStoragePath,
    value && value.audioUrl,
  ].map(storagePathFromUrl).filter(Boolean))];
}

async function deletePathsBestEffort(paths, reason, targetId) {
  const failed = [];
  for (const path of paths) {
    try {
      await deleteFileIfExists(path);
    } catch (_) {
      failed.push(path);
    }
  }
  const cleanupJobId = failed.length
    ? await queueStorageCleanup(failed, reason, targetId)
    : "";
  return { failed, cleanupJobId };
}

// 🗑️ سلة المحذوفات: الحذف نقلٌ لا إعدام — الوثيقة (ومعها نصّها المشروح)
// تنتقل إلى deleted_lessons وملفات التخزين تبقى كما هي، فتصحّ الاستعادة
// بنقرة. الحذف النهائي (يدوي أو بعد TRASH_RETENTION_MS) هو وحده ما يمسح
// الملفات. (درسٌ من فاجعة 2026-08-01: حذفٌ بالخطأ استلزم إنقاذاً من
// soft-delete التخزين ونافذة الساعة في Firestore.)
const TRASH_COLLECTION = "deleted_lessons";
const TRASH_RETENTION_MS = 30 * 24 * 60 * 60 * 1000;

async function deleteLessonHandler(data, context) {
  const actor = await assertAuthorized(context);
  const lessonId = requireString(data && data.lessonId, "lessonId", 1, 180);
  const lessonRef = db.collection("lessons").doc(lessonId);
  const reviewRef = db.collection("owner_lesson_reviews").doc(lessonId);
  const transcriptRef = db.collection("lesson_transcripts").doc(lessonId);
  const [lessonSnap, reviewSnap, transcriptSnap] = await Promise.all([
    lessonRef.get(),
    reviewRef.get(),
    transcriptRef.get(),
  ]);
  if (!lessonSnap.exists) return { ok: true, alreadyDeleted: true };
  const batch = db.batch();
  batch.set(db.collection(TRASH_COLLECTION).doc(lessonId), {
    lesson: lessonSnap.data(),
    transcript: transcriptSnap.exists ? transcriptSnap.data() : null,
    deletedBy: actor.email,
    deletedAt: admin.firestore.FieldValue.serverTimestamp(),
    deletedAtMs: Date.now(),
    purgeAfterMs: Date.now() + TRASH_RETENTION_MS,
  });
  batch.delete(lessonRef);
  batch.delete(transcriptRef);
  if (reviewSnap.exists) {
    batch.update(reviewRef, {
      status: "deleted",
      resolution: "delete_by_admin",
      resolvedBy: actor.email,
      resolvedAt: admin.firestore.FieldValue.serverTimestamp(),
    });
  }
  await batch.commit();
  await auditOwnerAction(actor.email, "trash_lesson", lessonId, {});
  return { ok: true, id: lessonId, trashed: true };
}

/** استعادة درس من السلة: تعيد الوثيقة ونصّها المشروح كما كانا. */
exports.restoreDeletedLesson = functions.https.onCall(async (data, context) => {
  const actor = await assertAuthorized(context);
  const lessonId = requireString(data && data.lessonId, "lessonId", 1, 180);
  const trashRef = db.collection(TRASH_COLLECTION).doc(lessonId);
  const snap = await trashRef.get();
  if (!snap.exists) {
    throw new functions.https.HttpsError("not-found", "العنصر غير موجود في السلة.");
  }
  const value = snap.data() || {};
  const lesson = value.lesson;
  if (!lesson || typeof lesson !== "object") {
    throw new functions.https.HttpsError("internal", "بيانات السلة غير مكتملة.");
  }
  const batch = db.batch();
  // ♻️ وسم الاستعادة: مُشغِّلات الإنشاء تُطلق ثانيةً عند إعادة الكتابة،
  // فيميّز هذا الوسمُ الدرسَ المستعاد من درس جديد فعلاً (يستعمله كاشف
  // الشبهة كي لا يُعيد إزعاج المالك ببلاغ سبق أن رآه). لا يمسّ حقول
  // المحتوى فبصمة المراجعة تبقى كما هي.
  batch.set(db.collection("lessons").doc(lessonId), Object.assign({}, lesson, {
    restoredAt: admin.firestore.FieldValue.serverTimestamp(),
    restoredAtMs: Date.now(),
    restoredBy: actor.email,
  }));
  if (value.transcript && typeof value.transcript === "object") {
    batch.set(db.collection("lesson_transcripts").doc(lessonId), value.transcript);
  }
  batch.delete(trashRef);
  await batch.commit();
  await auditOwnerAction(actor.email, "restore_lesson", lessonId, {});
  return { ok: true, id: lessonId };
});

/** الحذف النهائي من السلة: يمسح الوثيقة وملفات التخزين معاً. */
async function purgeTrashedLesson(trashDoc, actorEmail) {
  const value = trashDoc.data() || {};
  const lesson = unwrapLegacy(value.lesson || {});
  const paths = lessonStoragePaths(lesson);
  const transcript = value.transcript || {};
  (Array.isArray(transcript.images) ? transcript.images : []).forEach((item) => {
    const path = item && item.path && storagePathFromUrl(item.path);
    if (path) paths.push(path);
  });
  await trashDoc.ref.delete();
  await bucket.deleteFiles({ prefix: `lesson_transcripts/${trashDoc.id}/` })
    .catch(() => {});
  const cleanup = await deletePathsBestEffort(paths, "purge_lesson", trashDoc.id);
  if (actorEmail) {
    await auditOwnerAction(actorEmail, "purge_lesson", trashDoc.id, {
      cleanupPending: cleanup.failed.length > 0,
    });
  }
  return cleanup;
}

exports.purgeDeletedLesson = functions
  .runWith({ timeoutSeconds: 120, memory: "512MB" })
  .https.onCall(async (data, context) => {
    const actor = await assertAuthorized(context);
    const lessonId = requireString(data && data.lessonId, "lessonId", 1, 180);
    const snap = await db.collection(TRASH_COLLECTION).doc(lessonId).get();
    if (!snap.exists) return { ok: true, alreadyDeleted: true };
    const cleanup = await purgeTrashedLesson(snap, actor.email);
    return { ok: true, id: lessonId, cleanupPending: cleanup.failed.length > 0 };
  });

/** تفريغ السلة كاملةً — **للمالك حصراً**: حذف نهائي لكل محتوياتها. */
exports.emptyTrash = functions
  .runWith({ timeoutSeconds: 540, memory: "512MB" })
  .https.onCall(async (data, context) => {
    const actorEmail = await assertOwner(context);
    const snap = await db.collection(TRASH_COLLECTION).get();
    let purged = 0;
    for (const doc of snap.docs) {
      await purgeTrashedLesson(doc, "").catch((error) => {
        console.error("empty trash item failed", doc.id, error);
      });
      purged += 1;
    }
    await auditOwnerAction(actorEmail, "empty_trash", "", { purged });
    return { ok: true, purged };
  });

/**
 * 🔀 إعادة ترتيب دروس قسم فرعي: ترتيب التطبيق قائم على تاريخ الإنشاء
 * (الأقدم أولاً افتراضياً، والأحدث إن اختاره المستمع) — لذا لا نخترع
 * حقلاً جديداً بل **نعيد توزيع طوابع الإنشاء الموجودة نفسها** على الدروس
 * بالترتيب المطلوب: أقدم طابع لأول درس في الترتيب الجديد وهكذا. فيصحّ
 * الترتيبان تلقائياً في كل النسخ المثبتة بلا أي تعديل على التطبيق العام.
 */
exports.reorderSubcategoryLessons = functions
  .runWith({ timeoutSeconds: 300, memory: "512MB" })
  .https.onCall(async (data, context) => {
    const actor = await assertAuthorized(context);
    const subcategoryId = requireString(
      data && data.subcategoryId,
      "subcategoryId",
      1,
      180,
    );
    const orderedIds = Array.isArray(data && data.lessonIds)
      ? data.lessonIds.map((id) => cleanString(id, 180)).filter(Boolean)
      : [];
    if (orderedIds.length < 2) {
      throw new functions.https.HttpsError(
        "invalid-argument",
        "أرسل ترتيباً يضم درسين على الأقل.",
      );
    }
    const [plainSnap, wrappedSnap] = await Promise.all([
      db.collection("lessons").where("subcategoryId", "==", subcategoryId).get(),
      db.collection("lessons")
        .where("data.subcategoryId", "==", subcategoryId).get(),
    ]);
    const byId = new Map();
    [...plainSnap.docs, ...wrappedSnap.docs].forEach((doc) => byId.set(doc.id, doc));
    // الترتيب المرسل يجب أن يطابق دروس القسم تماماً (لا أكثر ولا أقل):
    // قائمة ناقصة تعني أن اللوحة ترى نسخة قديمة — نرفض بدل خلط الترتيب.
    if (orderedIds.length !== byId.size ||
        orderedIds.some((id) => !byId.has(id))) {
      throw new functions.https.HttpsError(
        "failed-precondition",
        "قائمة الترتيب لا تطابق دروس القسم الحالية — حدّث الشاشة وأعد المحاولة.",
      );
    }
    // جمع طوابع الإنشاء الحالية ثم فرزها تصاعدياً وإزالة أي تطابق بدفعة
    // +1ms — طابعان متساويان يجعلان موضعَي درسين غير محسومَين.
    const parseMs = (value) => {
      const raw = unwrapLegacy(value);
      const fromIso = Date.parse(String(raw.createdAt || ""));
      if (!Number.isNaN(fromIso)) return fromIso;
      if (Number.isFinite(Number(raw.createdAtMs))) return Number(raw.createdAtMs);
      if (raw.createdAtTs && typeof raw.createdAtTs.toMillis === "function") {
        return raw.createdAtTs.toMillis();
      }
      return Date.now();
    };
    const stamps = orderedIds
      .map((id) => parseMs(byId.get(id).data()))
      .sort((a, b) => a - b);
    for (let i = 1; i < stamps.length; i += 1) {
      if (stamps[i] <= stamps[i - 1]) stamps[i] = stamps[i - 1] + 1;
    }
    const batch = db.batch();
    orderedIds.forEach((id, index) => {
      const doc = byId.get(id);
      const ms = stamps[index];
      const iso = new Date(ms).toISOString();
      const update = {
        createdAt: iso,
        createdAtTs: admin.firestore.Timestamp.fromMillis(ms),
        createdAtMs: ms,
        reorderedBy: actor.email,
        reorderedAt: admin.firestore.FieldValue.serverTimestamp(),
      };
      // الوثائق القديمة الملفوفة `{data:{...}}`: التطبيق يقرأ المفتاح
      // الملفوف — يُحدَّث الموضعان معاً.
      const raw = doc.data() || {};
      if (raw.data && typeof raw.data === "object") {
        update["data.createdAt"] = iso;
      }
      batch.update(doc.ref, update);
    });
    await batch.commit();
    await auditOwnerAction(actor.email, "reorder_subcategory", subcategoryId, {
      lessons: orderedIds.length,
    });
    return { ok: true, id: subcategoryId, lessons: orderedIds.length };
  });

/** تنظيف يومي: ما تجاوز مدة بقائه في السلة (30 يوماً) يُحذف نهائياً. */
exports.purgeExpiredTrash = functions
  .runWith({ timeoutSeconds: 300, memory: "512MB" })
  .pubsub.schedule("40 3 * * *")
  .timeZone("Asia/Riyadh")
  .onRun(async () => {
    const snap = await db.collection(TRASH_COLLECTION)
      .where("purgeAfterMs", "<", Date.now())
      .limit(100)
      .get();
    for (const doc of snap.docs) {
      await purgeTrashedLesson(doc, "").catch((error) => {
        console.error("trash purge failed", doc.id, error);
      });
    }
    if (snap.size > 0) {
      await auditOwnerAction("system", "purge_expired_trash", "", {
        purged: snap.size,
      });
    }
    return null;
  });

exports.deleteLesson = functions.runWith({ timeoutSeconds: 120, memory: "512MB" })
  .https.onCall(deleteLessonHandler);
exports.deleteLessonPermanently = functions.runWith({ timeoutSeconds: 120, memory: "512MB" })
  .https.onCall(deleteLessonHandler);

async function deleteRefsInBatches(refs) {
  for (let offset = 0; offset < refs.length; offset += 400) {
    const batch = db.batch();
    refs.slice(offset, offset + 400).forEach((ref) => batch.delete(ref));
    await batch.commit();
  }
}

/**
 * نقل دفعة دروس إلى سلة المحذوفات (تستعملها عمليات الحذف التعاقبي):
 * الوثيقة + نصّها المشروح يُحفظان في السلة وملفات التخزين لا تُمسّ —
 * فحذف قسم بالخطأ لم يعد كارثة.
 */
async function trashLessonDocs(lessonDocs, actorEmail) {
  if (!lessonDocs.length) return 0;
  const transcriptSnaps = await db.getAll(
    ...lessonDocs.map((doc) => db.collection("lesson_transcripts").doc(doc.id)),
  );
  const transcriptById = new Map(transcriptSnaps.map((s) => [s.id, s]));
  const now = Date.now();
  for (let offset = 0; offset < lessonDocs.length; offset += 150) {
    const batch = db.batch();
    lessonDocs.slice(offset, offset + 150).forEach((doc) => {
      const transcriptSnap = transcriptById.get(doc.id);
      batch.set(db.collection(TRASH_COLLECTION).doc(doc.id), {
        lesson: doc.data(),
        transcript: transcriptSnap && transcriptSnap.exists
          ? transcriptSnap.data()
          : null,
        deletedBy: actorEmail,
        deletedAt: admin.firestore.FieldValue.serverTimestamp(),
        deletedAtMs: now,
        purgeAfterMs: now + TRASH_RETENTION_MS,
      });
      batch.delete(doc.ref);
      batch.delete(db.collection("lesson_transcripts").doc(doc.id));
    });
    await batch.commit();
  }
  return lessonDocs.length;
}

exports.deleteSubcategoryCascade = functions.runWith({ timeoutSeconds: 540, memory: "512MB" })
  .https.onCall(async (data, context) => {
    const actor = await assertAuthorized(context);
    const subcategoryId = requireString(
      data && data.subcategoryId,
      "subcategoryId",
      1,
      180,
    );
    // الوثائق القديمة ملفوفة `{data:{...}}`: استعلام الجذر لا يراها إطلاقاً
    // بينما تفكّها unwrapLegacy عند المعالجة — فكانت دروسها وملفاتها تنجو من
    // الحذف «الكامل» وتبقى ظاهرة في التطبيق العام. لذا استعلام ثانٍ على
    // المفتاح الملفوف، والدمج في خريطة واحدة بمعرّف الوثيقة (لا تكرار).
    const [subcategorySnap, lessonsSnap, wrappedLessonsSnap] = await Promise.all([
      db.collection("subcategories").doc(subcategoryId).get(),
      db.collection("lessons").where("subcategoryId", "==", subcategoryId).get(),
      db.collection("lessons")
        .where("data.subcategoryId", "==", subcategoryId).get(),
    ]);
    const lessonMap = new Map();
    [...lessonsSnap.docs, ...wrappedLessonsSnap.docs]
      .forEach((doc) => lessonMap.set(doc.id, doc));
    const lessons = [...lessonMap.values()];
    // دروس القسم تنتقل إلى السلة (لا حذف نهائي ولا مساس بالتخزين) —
    // فحذف قسم بالخطأ قابل للتراجع درساً درساً من سلة المحذوفات.
    await trashLessonDocs(lessons, actor.email);
    if (subcategorySnap.exists) await subcategorySnap.ref.delete();
    await auditOwnerAction(actor.email, "delete_subcategory_cascade", subcategoryId, {
      lessonsTrashed: lessons.length,
    });
    return {
      ok: true,
      id: subcategoryId,
      lessonsDeleted: lessons.length,
      cleanupPending: false,
    };
  });

exports.deleteCategoryCascade = functions.runWith({ timeoutSeconds: 540, memory: "1GB" })
  .https.onCall(async (data, context) => {
    const actor = await assertAuthorized(context);
    const categoryId = requireString(data && data.categoryId, "categoryId", 1, 180);
    // كما في حذف القسم الفرعي: الوثائق الملفوفة `{data:{...}}` لا يراها
    // استعلام الجذر، فتنجو من الحذف التعاقبي كلّه (دروساً وأقساماً وكتباً)
    // بينما تعلن اللوحة «حُذف بالكامل». لكل مجموعة استعلامان يُدمجان بالمعرّف.
    const [
      categorySnap,
      subcategoriesSnap,
      wrappedSubcategoriesSnap,
      categoryLessonsSnap,
      wrappedCategoryLessonsSnap,
      booksSnap,
      wrappedBooksSnap,
    ] = await Promise.all([
      db.collection("categories").doc(categoryId).get(),
      db.collection("subcategories").where("categoryId", "==", categoryId).get(),
      db.collection("subcategories")
        .where("data.categoryId", "==", categoryId).get(),
      db.collection("lessons").where("categoryId", "==", categoryId).get(),
      db.collection("lessons").where("data.categoryId", "==", categoryId).get(),
      db.collection("books").where("categoryId", "==", categoryId).get(),
      db.collection("books").where("data.categoryId", "==", categoryId).get(),
    ]);
    const lessonMap = new Map();
    [...categoryLessonsSnap.docs, ...wrappedCategoryLessonsSnap.docs]
      .forEach((doc) => lessonMap.set(doc.id, doc));
    const subcategoryMap = new Map();
    [...subcategoriesSnap.docs, ...wrappedSubcategoriesSnap.docs]
      .forEach((doc) => subcategoryMap.set(doc.id, doc));
    const bookMap = new Map();
    [...booksSnap.docs, ...wrappedBooksSnap.docs]
      .forEach((doc) => bookMap.set(doc.id, doc));
    const subcategories = [...subcategoryMap.values()];
    for (const subcategory of subcategories) {
      const [plainSnap, wrappedSnap] = await Promise.all([
        db.collection("lessons")
          .where("subcategoryId", "==", subcategory.id).get(),
        db.collection("lessons")
          .where("data.subcategoryId", "==", subcategory.id).get(),
      ]);
      [...plainSnap.docs, ...wrappedSnap.docs]
        .forEach((doc) => lessonMap.set(doc.id, doc));
    }
    const lessons = [...lessonMap.values()];
    const books = [...bookMap.values()];
    // دروس القسم كلّها تنتقل إلى السلة (قابلة للاستعادة)؛ الكتب تُحذف كما
    // كانت (لا واجهة لها في التطبيقين وملفاتها PDF قليلة).
    await trashLessonDocs(lessons, actor.email);
    const bookPaths = [];
    books.forEach((doc) => {
      const value = unwrapLegacy(doc.data());
      [value.storagePath, value.pdfStoragePath, value.fileUrl, value.url]
        .map(storagePathFromUrl)
        .filter(Boolean)
        .forEach((path) => bookPaths.push(path));
    });
    const refs = [
      ...books.map((doc) => doc.ref),
      ...subcategories.map((doc) => doc.ref),
    ];
    if (categorySnap.exists) refs.push(categorySnap.ref);
    await deleteRefsInBatches(refs);
    const cleanup = await deletePathsBestEffort(
      bookPaths,
      "delete_category_cascade",
      categoryId,
    );
    await auditOwnerAction(actor.email, "delete_category_cascade", categoryId, {
      subcategoriesDeleted: subcategories.length,
      lessonsTrashed: lessons.length,
      booksDeleted: books.length,
      cleanupPending: cleanup.failed.length > 0,
      cleanupJobId: cleanup.cleanupJobId,
    });
    return {
      ok: true,
      id: categoryId,
      subcategoriesDeleted: subcategories.length,
      lessonsDeleted: lessons.length,
      booksDeleted: books.length,
      cleanupPending: cleanup.failed.length > 0,
    };
  });

exports.onSubmissionCreated = functions.firestore
  .document("lesson_submissions/{id}")
  .onCreate(async (snap) => {
    const d = snap.data() || {};
    const title = cleanString(d.title, 120);
    const who = cleanString(d.submitterName, 60) || "مستمع";
    const alertTitle = "مساهمة جديدة بانتظار المراجعة";
    const alertBody = `أرسل ${who} درساً مقترحاً: «${title}».`;
    await Promise.all([
      writeAdminAlert("", alertTitle, alertBody, {
        type: "submission",
        submissionId: snap.id,
        refId: snap.id,
      }),
      // وجهة اللوحة صريحة: تبويب «المساهمات» في شاشة المراجعة.
      pushToAdmins(alertTitle, alertBody, {
        type: "submission",
        submissionId: snap.id,
        refId: snap.id,
        route: "submissions",
      }),
    ]);
    return null;
  });

exports.onSubmissionDecided = functions.firestore
  .document("lesson_submissions/{id}")
  .onUpdate(async (change) => {
    const before = change.before.data() || {};
    const after = change.after.data() || {};
    if (before.status !== "pending" || after.status === "pending") return null;
    const token = cleanString(after.fcmToken, 4096);
    const title = cleanString(after.publishedTitle || after.title, 120);
    if (after.status === "approved" || after.status === "approved_edited") {
      const edited = after.status === "approved_edited";
      const notificationTitle = edited
        ? "نُشرت مساهمتك بعد المراجعة"
        : "نُشرت مساهمتك";
      const notificationBody = `نُشر الدرس «${title}». شكراً لمساهمتك.`;
      // معرّف الدرس المنشور فقط إن وُجد فعلاً — سلسلة فارغة تُفسد التوجيه.
      const publishedLessonId = cleanString(after.publishedLessonId, 180);
      const notificationData = {
        type: "submission",
        id: change.after.id,
        refId: change.after.id,
        submissionId: change.after.id,
        result: after.status,
        route: publishedLessonId ? "lesson" : "my-submissions",
      };
      if (publishedLessonId) notificationData.lessonId = publishedLessonId;
      await Promise.all([
        clearAdminAlerts("submission", change.after.id),
        writeUserNotification(after.uid, notificationTitle, notificationBody, notificationData),
        pushToToken(token, notificationTitle, notificationBody, notificationData),
      ]);
      return null;
    }
    if (after.status === "rejected") {
      const reason = cleanString(after.rejectReason, 300);
      const notificationTitle = "نتيجة مراجعة مساهمتك";
      const notificationBody = reason
        ? `لم يُنشر «${title}»: ${reason}`
        : `لم يُنشر «${title}».`;
      // ⛳ الوجهة «مساهماتي» صراحةً: لا درس منشوراً يُفتح بعد الرفض.
      const notificationData = {
        type: "submission",
        id: change.after.id,
        refId: change.after.id,
        submissionId: change.after.id,
        result: "rejected",
        route: "my-submissions",
      };
      await Promise.all([
        clearAdminAlerts("submission", change.after.id),
        writeUserNotification(after.uid, notificationTitle, notificationBody, notificationData),
        pushToToken(token, notificationTitle, notificationBody, notificationData),
      ]);
      return null;
    }
    return null;
  });

// تنظيف التنبيهات المستهلَكة: مساهمات حُسمت، تنبيهات نسخ قديمة بلا type
// (يستحيل ربطها بمصدرها — النسخ الحالية تكتب type دائماً)، ورموز اعتماد انتهت
// صلاحيتها دون حسم.
exports.cleanupResolvedAdminAlerts = functions.https.onCall(async (_data, context) => {
  await assertAuthorized(context);
  const alertsSnap = await db.collection("admin_alerts").get();
  const parsed = alertsSnap.docs.map((doc) => {
    const value = doc.data() || {};
    const metadata = value.data || {};
    return {
      ref: doc.ref,
      type: cleanString(value.type || metadata.type, 40),
      submissionId: cleanString(
        value.refId || metadata.refId || metadata.submissionId,
        180,
      ),
      expiresAt: Number(metadata.expiresAt || 0),
    };
  });
  const stale = parsed
    .filter((item) => !item.type
      || (item.type === "owner_code" && item.expiresAt && item.expiresAt < Date.now()))
    .map((item) => item.ref);

  // التنبيهات المرتبطة بوثيقة حالة: تُحذف حين لا تعود الوثيقة معلّقة.
  const trackedKinds = [
    { type: "submission", collection: "lesson_submissions" },
    { type: "suspicious_lesson", collection: "owner_lesson_reviews" },
  ];
  for (const kind of trackedKinds) {
    const tracked = parsed.filter(
      (item) => item.type === kind.type && item.submissionId,
    );
    if (!tracked.length) continue;
    const uniqueIds = [...new Set(tracked.map((item) => item.submissionId))];
    const statusById = new Map();
    for (let offset = 0; offset < uniqueIds.length; offset += 300) {
      const refs = uniqueIds.slice(offset, offset + 300)
        .map((id) => db.collection(kind.collection).doc(id));
      const docs = await db.getAll(...refs);
      docs.forEach((doc) => {
        statusById.set(doc.id, doc.exists ? cleanString((doc.data() || {}).status, 40) : "missing");
      });
    }
    tracked
      .filter((item) => statusById.get(item.submissionId) !== "pending")
      .forEach((item) => stale.push(item.ref));
  }
  for (let offset = 0; offset < stale.length; offset += 400) {
    const batch = db.batch();
    stale.slice(offset, offset + 400).forEach((ref) => batch.delete(ref));
    await batch.commit();
  }
  return { ok: true, deleted: stale.length };
});

// إشعار خاص بأعضاء مجموعة الإدارة الموثقين، مع احترام الكتم واستبعاد المرسل.
exports.onAdminChatMessageCreated = functions.firestore
  .document("admin_chat_messages/{id}")
  .onCreate(async (snap) => {
    const value = snap.data() || {};
    if (value.deleted === true) return null;
    const senderId = cleanString(value.senderId, 180);
    const senderName = cleanString(value.senderName || "عضو", 100);
    const typeLabels = {
      image: "صورة",
      video: "فيديو",
      audio: "مقطع صوتي",
      voice: "رسالة صوتية",
      file: "ملف",
    };
    const messageType = cleanString(value.type, 20);
    const preview = cleanString(value.text, 160)
      || typeLabels[messageType]
      || "رسالة جديدة";
    await pushToAdminsFiltered(
      `رسالة من ${senderName}`,
      preview,
      { type: "admin_chat", messageId: snap.id, senderId },
      { excludeUid: senderId, respectChatMute: true },
    );
    return null;
  });

// إشعار المحادثة الفرديّة: يصل الطرف الآخر وحده (لا المجموعة ولا غيرهما).
exports.onAdminDmMessageCreated = functions.firestore
  .document("admin_dm_threads/{threadId}/messages/{msgId}")
  .onCreate(async (snap, context) => {
    const value = snap.data() || {};
    if (value.deleted === true) return null;
    // سجلّ المكالمة رسالةٌ في الثريد لكنّه ليس «رسالة خاصّة» — إشعاره يصل
    // بعد كلّ مكالمة (حتى الفائتة) فيبدو تكراراً مربكاً بلا فائدة.
    if (cleanString(value.type, 20) === "call") return null;
    const senderId = cleanString(value.senderId, 180);
    const senderName = cleanString(value.senderName || "مشرف", 100);
    const threadId = cleanString(context.params.threadId, 400);
    // طرفا المحادثة من معرّفها الحتمي (uidA__uidB) ومن وثيقتها احتياطاً.
    let members = threadId.split("__").filter(Boolean);
    if (members.length !== 2) {
      const threadSnap = await db.collection("admin_dm_threads")
        .doc(threadId).get();
      const data = threadSnap.data() || {};
      members = Array.isArray(data.members) ? data.members.map(String) : [];
    }
    const target = members.find((uid) => uid && uid !== senderId);
    if (!target) return null;

    const typeLabels = {
      image: "صورة",
      video: "فيديو",
      audio: "مقطع صوتي",
      voice: "رسالة صوتيّة",
      file: "ملف",
    };
    const messageType = cleanString(value.type, 20);
    const preview = cleanString(value.text, 160)
      || typeLabels[messageType]
      || "رسالة خاصّة";

    // الرمز المستهدف: جهاز العضو المعتمَد فقط، مع احترام كتم الدردشة.
    const tokens = (await activeAdminTokens(false)).filter(
      (item) => item.uid === target && !item.chatMuted,
    );
    return sendToAdminTargets(
      tokens,
      `رسالة خاصّة من ${senderName}`,
      preview,
      {
        type: "admin_dm",
        threadId,
        messageId: snap.id,
        senderId,
        senderName,
      },
    );
  });

// ─── نظام المراجعة السرية للدروس المشبوهة (للمالك فقط) ─────────────
//
// أُعيدت معايرته بعد بلاغ المالك (2026-07-30): «الفحص الشامل يُظهر 67 درساً
// مشبوهاً ولا شيء فيها فعلاً». سبب ذلك أن المعايير القديمة كانت تُطابق
// **مفردات** لا **سياقات**، وتخلط بين إشارة محتوى وإشارة سلامة بيانات:
//   • «القاعدة» كانت في قائمة التنظيمات بدرجة 5، وwholeWord يسمح بسابقة
//     «ال» — فكل درس اسمه «القاعدة الفقهية» أو «القاعدة الأولى» من سلسلة
//     «القواعد الأربع» يُفتح له بلاغ خطورة عالية. أكبر مصدر للضجيج.
//   • «سفك الدماء» بدرجة 4 = عتبة التنبيه تماماً، وهي عبارة خطبٍ تُحرّمه.
//   • ألفاظ «القتل/سلاح/مخدرات/تكفير» هي موضوع الدروس الفقهية نفسه، وكانت
//     تكفي درجتها (3) لتجاوز العتبة مع أيّ ملاحظة إدارية عابرة (تكرار عنوان).
//
// المبدأ الجديد — فئتان لا فئة واحدة:
//   1) إشارات محتوى/أمان: هي وحدها ما يجوز أن يفتح مراجعة بذاته، وتُطابَق
//      في سياق لا كمفردة (تنظيم القاعدة ≠ القاعدة، صنع قنبلة ≠ قنبلة).
//   2) إشارات سلامة بيانات (رابط ناقص، قسم محذوف، تكرار ملف…): لا تفتح
//      مراجعة بمفردها أبداً؛ تحتاج قرينتين مستقلّتين ومجموعاً عالياً.
// وحُذف ما لا معنى له في مكتبة دروس صوتية: كشف «القرصنة»، وكشف «معلومات
// الاتصال» (نمطه `(?:\+?\d[\s-]?){9,}` كان يلتقط أيّ تاريخين أو ترقيم دروس).

/// حدّ كلمة عربي/لاتيني (\b اللاتينية لا تعمل مع العربية).
/// تُسمح السوابق الملتصقة الشائعة وحدها: العطف (و/ف) ثم الجرّ (ب/ك/ل)،
/// لأن منعها كلياً فتح تهرّباً بحرف واحد — «وداعش» و«فاقتلوا» و«لصنع قنبلة»
/// كانت تُفلت من كل أنماط المحتوى. ويبقى **منع «ال» التعريف الملتصقة**
/// (فالألف ليست ضمن السوابق المسموحة)، وهي وحدها سبب إيجابيات «القاعدة
/// الفقهية»؛ والعبارات أدناه مركّبة فلا يعود بها الضجيج.
const phrase = (alternatives) =>
  new RegExp(
    `(?<![\\p{L}\\p{N}])(?:و|ف)?(?:ب|ك|ل)?(?:${alternatives})(?![\\p{L}\\p{N}])`,
    "iu",
  );

// إشارات المحتوى: قاطعة بدرجة 5 (تفتح مراجعة وحدها)، ومرجّحة بدرجة 4
// (تحتاج قرينة أخرى) — لأن صيغة الأمر قد ترد داخل نقل نصّ أو ردٍّ عليه.
const CONTENT_PATTERNS = [
  {
    pattern: /<script|javascript:|data:text\/html|<iframe/iu,
    reason: "شفرة أو رابط غير آمن داخل البيانات",
    score: 5,
  },
  {
    // أسماء التنظيمات في سياقها المركّب وحده.
    pattern: phrase(
      "داعش|تنظيم\\s+القاعدة|تنظيم\\s+الدولة|جبهة\\s+النصرة|"
      + "الدولة\\s+الإسلامية\\s+في\\s+العراق",
    ),
    reason: "إشارة صريحة إلى تنظيم متطرف",
    score: 5,
  },
  {
    // تعليمات تصنيع لا مجرّد ذكر: «قنبلة» و«تفجير» تردان في السيرة والتاريخ.
    pattern: phrase(
      "(?:صنع|تصنيع|تركيب|إعداد|تحضير|طريقة|كيفية)\\s+(?:ال)?"
      + "(?:قنبلة|قنابل|متفجرات|عبوة\\s+ناسفة|حزام\\s+ناسف|سلاح\\s+ناري)",
    ),
    reason: "ما يشبه تعليمات تصنيع متفجرات أو سلاح",
    score: 5,
  },
  {
    // تحريض بصيغة الأمر على فئة — لا مجرّد ذكر «القتل» في باب فقهي.
    // درجته 5 لا 4: بدرجة 4 كان التحريض الصريح لا يبلغ أيّ عتبة وحده، بل
    // يسلك الدرس مسار «دون العتبة» فيُغلق بلاغه السابق تلقائياً — وهو أخطر
    // ما يمكن أن يفعله كاشف. «قتلوا» ضمن البدائل كي تُطابق «فاقتلوا/واقتلوا».
    pattern: phrase(
      "(?:اقتلوا|اقتل|قتلوا|اذبحوا|اذبح|فجّروا|فجروا)\\s+(?:كلَّ|كل|جميع|من)"
      + "|يجب\\s+قتلُ?\\s+(?:كلَّ|كل|جميع|من)",
    ),
    reason: "صيغة أمر صريحة بالقتل موجَّهة إلى فئة",
    score: 5,
  },
];

/// درجة المحتوى التي تفتح مراجعة بذاتها.
const CONTENT_ALERT_THRESHOLD = 5;
/// مجموع الدرجات الذي يفتح مراجعة عند وجود إشارة محتوى مرجّحة مع قرينة.
const MIXED_ALERT_THRESHOLD = 6;
/// مجموع درجات سلامة البيانات — ولا يكفي إلا مع قرينتين مستقلّتين فأكثر.
const HYGIENE_ALERT_THRESHOLD = 6;
const HYGIENE_MIN_SIGNALS = 2;

/// إشارة سلامة بيانات: لا تفتح مراجعة بمفردها مهما بلغت درجتها.
function addHygieneSignal(result, reason, score) {
  if (!reason || result.reasons.includes(reason)) return;
  result.reasons.push(reason);
  result.hygieneScore += score;
  result.hygieneSignals += 1;
}

/// إشارة محتوى/أمان.
function addContentSignal(result, reason, score) {
  if (!reason || result.reasons.includes(reason)) return;
  result.reasons.push(reason);
  result.contentScore += score;
}

/// القرار النهائي: هل يستحق هذا الدرس إزعاج المالك؟
function shouldOpenReview(result) {
  if (!result.reasons.length) return false;
  if (result.contentScore >= CONTENT_ALERT_THRESHOLD) return true;
  const total = result.contentScore + result.hygieneScore;
  if (result.contentScore > 0 && total >= MIXED_ALERT_THRESHOLD) return true;
  return result.hygieneSignals >= HYGIENE_MIN_SIGNALS
    && result.hygieneScore >= HYGIENE_ALERT_THRESHOLD;
}

function lessonModerationFields(raw) {
  const d = unwrapLegacy(raw);
  const title = cleanString(d.title || d.name, 300);
  return {
    title,
    normalizedTitle: cleanString(d.normalizedTitle, 300)
      || title.toLocaleLowerCase("ar").replace(/\s+/g, " ").trim(),
    description: cleanString(d.description || d.note || d.text, 2000),
    audioUrl: cleanString(d.audioUrl || d.url, 2500),
    storagePath: cleanString(d.storagePath || d.audioStoragePath, 700),
    categoryId: cleanString(d.categoryId, 180),
    subcategoryId: cleanString(d.subcategoryId, 180),
    publishAt: cleanString(d.publishAt, 100),
    publishNotified: d.publishNotified === true,
    addedBy: cleanString(d.addedBy, 180),
    createdByEmail: normalizeEmail(d.createdByEmail || d.addedBy),
    createdByUid: cleanString(d.createdByUid, 180),
    updatedByEmail: normalizeEmail(d.updatedByEmail),
    updatedByUid: cleanString(d.updatedByUid, 180),
  };
}

/// مقتطف سياق حول موضع المطابقة — يُعرض للمالك دليلاً لا تخميناً.
function evidenceExcerpt(text, index, length) {
  const start = Math.max(0, index - 25);
  const end = Math.min(text.length, index + length + 25);
  const prefix = start > 0 ? "…" : "";
  const suffix = end < text.length ? "…" : "";
  return `${prefix}${text.slice(start, end).trim()}${suffix}`;
}

function inspectLesson(raw) {
  const fields = lessonModerationFields(raw);
  const result = {
    fields,
    reasons: [],
    contentScore: 0,
    hygieneScore: 0,
    hygieneSignals: 0,
    // البصمة تستثني publishNotified: قلبُه علامة نشر إجرائية (يكتبها
    // onLessonCreated والمجدول) لا تغييراً في المحتوى، وإدراجه كان يعيد
    // فتح المراجعة وتنبيه المالك مرتين لنفس الدرس.
    fingerprint: hashId(
      JSON.stringify({ ...fields, publishNotified: undefined }),
    ),
  };
  const sources = [
    ["العنوان", fields.title],
    ["الوصف", fields.description],
  ];
  CONTENT_PATTERNS.forEach((item) => {
    for (const [label, text] of sources) {
      if (!text) continue;
      const match = item.pattern.exec(text);
      if (match) {
        addContentSignal(
          result,
          `${item.reason} — وردت عبارة «${match[0]}» في ${label}: `
          + `"${evidenceExcerpt(text, match.index, match[0].length)}"`,
          item.score,
        );
        break; // يكفي دليل واحد لكل نمط.
      }
    }
  });
  if (!fields.title) {
    addHygieneSignal(result, "الدرس بلا عنوان إطلاقاً", 3);
  } else if (fields.title.length < 3) {
    addHygieneSignal(
      result,
      `العنوان أقصر من أن يكون دالاً: «${fields.title}»`,
      2,
    );
  }
  // التطويل العربي (ـ) حرفٌ زخرفيّ مشروع في العناوين، فلا يُحسب تكراراً
  // شاذاً؛ والحدّ رُفع إلى سبعة أحرف متطابقة كي لا يُلتقط المدّ المكتوب.
  if (/(?!ـ)([\p{L}\p{N}])\1{6,}/u.test(fields.title)) {
    addHygieneSignal(result, "العنوان يحوي تكراراً غير طبيعي لنفس الحرف", 2);
  }
  if (!fields.audioUrl) {
    addHygieneSignal(result, "الدرس بلا رابط صوت", 3);
  } else {
    try {
      const host = new URL(fields.audioUrl).hostname.toLowerCase();
      const approved = host === "firebasestorage.googleapis.com"
        || host.endsWith(".googleapis.com")
        || host.endsWith(".firebasestorage.app")
        || host === "storage.cloud.google.com"
        || host.endsWith("res.cloudinary.com")
        || host.endsWith("archive.org");
      if (!approved) {
        addHygieneSignal(
          result,
          `مصدر الصوت خارج تخزين التطبيق المعتمد: ${host}`,
          2,
        );
      }
    } catch (_) {
      addHygieneSignal(result, "رابط الصوت ليس رابطاً صالحاً أصلاً", 3);
    }
  }
  if (fields.publishAt) {
    const parsedPublishAt = Date.parse(fields.publishAt);
    if (Number.isNaN(parsedPublishAt)) {
      addHygieneSignal(
        result,
        `تاريخ النشر المجدول غير مفهوم: «${fields.publishAt}»`,
        2,
      );
    } else if (!fields.publishNotified
        && parsedPublishAt < Date.now() - 24 * 60 * 60 * 1000) {
      addHygieneSignal(
        result,
        "درس مجدول تجاوز موعد نشره بأكثر من يوم دون أن يُنشر",
        2,
      );
    }
  }
  return result;
}

async function recordSuspiciousLesson(
  lessonId,
  raw,
  source,
  notifyOwner,
  extraReasons = [],
  preloaded = null,
) {
  const result = inspectLesson(raw);
  // الأسباب الخارجية (من مسارات أخرى) إدارية بطبيعتها، فتُعامَل معاملة
  // إشارات سلامة البيانات: لا تفتح مراجعة بمفردها.
  extraReasons.forEach((reason) => addHygieneSignal(result, reason, 2));
  const checks = [];
  // الدروس المكرّرة: عنوانٌ متطابق وحده أمر طبيعي تماماً في السلاسل
  // («الدرس الأول»، «القاعدة الأولى»)، فدرجته 1 فقط؛ أمّا تطابق الملف
  // المخزَّن فأقوى دلالة على تكرار حقيقي.
  if (preloaded) {
    if (result.fields.categoryId
        && !preloaded.categoryIds.has(result.fields.categoryId)) {
      addHygieneSignal(result, "القسم الرئيسي المُشار إليه غير موجود في القاعدة", 3);
    }
    if (result.fields.subcategoryId) {
      if (!preloaded.subcategoryParents.has(result.fields.subcategoryId)) {
        addHygieneSignal(result, "القسم الفرعي المُشار إليه غير موجود في القاعدة", 3);
      } else {
        const parent = preloaded.subcategoryParents.get(result.fields.subcategoryId);
        if (result.fields.categoryId && parent && parent !== result.fields.categoryId) {
          addHygieneSignal(result, "القسم الفرعي المحدد لا يتبع القسم الرئيسي المحدد", 3);
        }
      }
    }
    if (result.fields.normalizedTitle
        && (preloaded.titleCounts.get(result.fields.normalizedTitle) || 0) > 1) {
      addHygieneSignal(result, "العنوان مطابق حرفياً لدرس آخر موجود", 1);
    }
    if (result.fields.audioUrl
        && (preloaded.audioUrlCounts.get(result.fields.audioUrl) || 0) > 1) {
      addHygieneSignal(result, "رابط الصوت نفسه مستخدم في درس آخر", 2);
    }
    if (result.fields.storagePath
        && (preloaded.storagePathCounts.get(result.fields.storagePath) || 0) > 1) {
      addHygieneSignal(result, "ملف الصوت المخزَّن نفسه مستخدم في درس آخر", 3);
    }
  } else if (result.fields.categoryId) {
    checks.push(
      db.collection("categories").doc(result.fields.categoryId).get()
        .then((snap) => {
          if (!snap.exists) {
            addHygieneSignal(result, "القسم الرئيسي المُشار إليه غير موجود في القاعدة", 3);
          }
        }),
    );
  }
  if (!preloaded && result.fields.subcategoryId) {
    checks.push(
      db.collection("subcategories").doc(result.fields.subcategoryId).get()
        .then((snap) => {
          if (!snap.exists) {
            addHygieneSignal(result, "القسم الفرعي المُشار إليه غير موجود في القاعدة", 3);
          } else {
            const parent = cleanString((snap.data() || {}).categoryId, 180);
            if (result.fields.categoryId && parent && parent !== result.fields.categoryId) {
              addHygieneSignal(result, "القسم الفرعي المحدد لا يتبع القسم الرئيسي المحدد", 3);
            }
          }
        }),
    );
  }
  if (!preloaded && result.fields.normalizedTitle) {
    checks.push(
      db.collection("lessons")
        .where("normalizedTitle", "==", result.fields.normalizedTitle)
        .limit(3)
        .get()
        .then((snap) => {
          if (snap.docs.some((doc) => doc.id !== lessonId)) {
            addHygieneSignal(result, "العنوان مطابق حرفياً لدرس آخر موجود", 1);
          }
        }),
    );
  }
  if (!preloaded && result.fields.audioUrl) {
    checks.push(
      db.collection("lessons").where("audioUrl", "==", result.fields.audioUrl)
        .limit(3).get().then((snap) => {
          if (snap.docs.some((doc) => doc.id !== lessonId)) {
            addHygieneSignal(result, "رابط الصوت نفسه مستخدم في درس آخر", 2);
          }
        }),
    );
  }
  if (!preloaded && result.fields.storagePath) {
    checks.push(
      db.collection("lessons").where("storagePath", "==", result.fields.storagePath)
        .limit(3).get().then((snap) => {
          if (snap.docs.some((doc) => doc.id !== lessonId)) {
            addHygieneSignal(result, "ملف الصوت المخزَّن نفسه مستخدم في درس آخر", 3);
          }
        }),
    );
  }
  await Promise.all(checks);
  result.riskScore = result.contentScore + result.hygieneScore;
  const ref = db.collection("owner_lesson_reviews").doc(lessonId);
  const existing = await ref.get();
  const old = existing.data() || {};
  // دون العتبة = ليس شبهة تستحق مراجعة المالك. يشمل هذا المراجعات المعلّقة
  // من منطق قديم أشد حساسية — تُغلق تلقائياً ويُمسح تنبيهها مهما كانت بصمتها.
  // («flagged» حالة قديمة يعدّها التطبيق معلّقة أيضاً، فتُغلق معها.)
  if (!shouldOpenReview(result)) {
    if (existing.exists && (old.status === "pending" || old.status === "flagged")) {
      await ref.update({
        status: "auto_cleared",
        resolution: result.reasons.length
          ? "below_alert_threshold"
          : "no_longer_flagged",
        resolvedAt: admin.firestore.FieldValue.serverTimestamp(),
      });
      await clearAdminAlerts("suspicious_lesson", lessonId);
    }
    return false;
  }
  if (old.status === "verified" && old.fingerprint === result.fingerprint) {
    return false;
  }
  const unchangedPending = old.status === "pending"
    && old.fingerprint === result.fingerprint;
  await ref.set({
    lessonId,
    lessonTitle: result.fields.title,
    reasons: result.reasons,
    riskScore: result.riskScore,
    contentScore: result.contentScore,
    hygieneScore: result.hygieneScore,
    riskLevel: result.contentScore >= CONTENT_ALERT_THRESHOLD
      ? "high"
      : result.contentScore > 0 ? "medium" : "low",
    status: "pending",
    fingerprint: result.fingerprint,
    source,
    lessonSnapshot: result.fields,
    detectedAt: admin.firestore.FieldValue.serverTimestamp(),
    detectedAtMs: Date.now(),
    updatedAt: admin.firestore.FieldValue.serverTimestamp(),
  }, { merge: true });
  if (notifyOwner && !unchangedPending) {
    const title = "تنبيه خاص: درس يحتاج مراجعتك";
    const body = `«${result.fields.title || lessonId}» — ${result.reasons[0]}`;
    await Promise.all([
      writeAdminAlert(OWNER_EMAIL, title, body, {
        type: "suspicious_lesson",
        reviewId: lessonId,
        lessonId,
      }),
      pushToAdmins(title, body, {
        type: "suspicious_lesson",
        reviewId: lessonId,
        lessonId,
        refId: lessonId,
      }, true),
    ]);
  }
  return true;
}

exports.onLessonSuspicionCreated = functions.firestore
  .document("lessons/{lessonId}")
  .onCreate((snap, context) => {
    const value = snap.data() || {};
    // ♻️ الدرس المستعاد من السلة ليس محتوى جديداً: الفحص يجري كاملاً
    // (فيبقى سجلّ المراجعة صحيحاً ومحدَّثاً) لكن بلا تنبيه فوريّ للمالك،
    // فقد رأى هذا البلاغ نفسه يوم أُضيف الدرس أوّل مرّة. الوسم قصير العمر
    // كي لا يُسكت الكشف عن أي إضافة لاحقة بالمعرّف نفسه.
    const restoredAtMs = Number(value.restoredAtMs || 0);
    const justRestored = restoredAtMs > 0
      && Date.now() - restoredAtMs < 10 * 60 * 1000;
    return recordSuspiciousLesson(
      context.params.lessonId,
      value,
      justRestored ? "restored" : "created",
      !justRestored,
    );
  });

exports.onLessonSuspicionUpdated = functions.firestore
  .document("lessons/{lessonId}")
  .onUpdate(async (change, context) => {
    const before = inspectLesson(change.before.data());
    const after = inspectLesson(change.after.data());
    if (before.fingerprint === after.fingerprint) return null;
    return recordSuspiciousLesson(
      context.params.lessonId,
      change.after.data(),
      "updated",
      true,
    );
  });

exports.scanSuspiciousLessons = functions.runWith({ timeoutSeconds: 540, memory: "512MB" })
  .https.onCall(async (_data, context) => {
    await assertOwner(context);
    const [snap, categoriesSnap, subcategoriesSnap] = await Promise.all([
      db.collection("lessons").get(),
      db.collection("categories").get(),
      db.collection("subcategories").get(),
    ]);
    const preloaded = {
      categoryIds: new Set(categoriesSnap.docs.map((doc) => doc.id)),
      subcategoryParents: new Map(subcategoriesSnap.docs.map((doc) => [
        doc.id,
        cleanString(unwrapLegacy(doc.data()).categoryId, 180),
      ])),
      titleCounts: new Map(),
      audioUrlCounts: new Map(),
      storagePathCounts: new Map(),
    };
    const increment = (map, key) => {
      if (key) map.set(key, (map.get(key) || 0) + 1);
    };
    snap.docs.forEach((doc) => {
      const fields = lessonModerationFields(doc.data());
      increment(preloaded.titleCounts, fields.normalizedTitle);
      increment(preloaded.audioUrlCounts, fields.audioUrl);
      increment(preloaded.storagePathCounts, fields.storagePath);
    });
    let suspicious = 0;
    const concurrency = 20;
    for (let offset = 0; offset < snap.docs.length; offset += concurrency) {
      const results = await Promise.all(
        snap.docs.slice(offset, offset + concurrency).map((doc) =>
          recordSuspiciousLesson(
            doc.id,
            doc.data(),
            "manual_scan",
            false,
            [],
            preloaded,
          )),
      );
      suspicious += results.filter(Boolean).length;
    }
    // مراجعات معلّقة لدروس لم تعد موجودة (حُذفت من مسار آخر): لا يمكن
    // للمالك حسمها من الشاشة لأن الدرس مفقود، فتبقى معلّقة إلى الأبد.
    const lessonIds = new Set(snap.docs.map((doc) => doc.id));
    const pendingSnap = await db.collection("owner_lesson_reviews")
      .where("status", "==", "pending").get();
    const orphans = pendingSnap.docs.filter((doc) => {
      const value = doc.data() || {};
      return !lessonIds.has(cleanString(value.lessonId, 180) || doc.id);
    });
    for (let offset = 0; offset < orphans.length; offset += 400) {
      const batch = db.batch();
      orphans.slice(offset, offset + 400).forEach((doc) => {
        batch.update(doc.ref, {
          status: "auto_cleared",
          resolution: "lesson_missing",
          resolvedAt: admin.firestore.FieldValue.serverTimestamp(),
        });
      });
      await batch.commit();
    }
    await auditOwnerAction(OWNER_EMAIL, "scan_suspicious_lessons", "", {
      scanned: snap.size,
      suspicious,
      orphansCleared: orphans.length,
    });
    if (suspicious) {
      await pushToAdmins(
        "اكتمل فحص الدروس",
        `تم فحص ${snap.size} درساً والعثور على ${suspicious} درساً يحتاج المراجعة.`,
        { type: "suspicious_scan", suspicious },
        true,
      );
    }
    // `flagged` مرادف متوافق مع نسخ اللوحة القديمة التي تقرأ هذا المفتاح.
    return {
      ok: true,
      scanned: snap.size,
      suspicious,
      flagged: suspicious,
      orphansCleared: orphans.length,
    };
  });

exports.resolveSuspiciousLesson = functions.runWith({ timeoutSeconds: 120, memory: "512MB" })
  .https.onCall(async (data, context) => {
    const actorEmail = await assertOwner(context);
    const reviewId = requireString(data && data.reviewId, "reviewId", 1, 180);
    const lessonId = requireString(data && data.lessonId, "lessonId", 1, 180);
    const action = cleanString(data && data.action, 20);
    if (!["verified", "delete"].includes(action)) {
      throw new functions.https.HttpsError("invalid-argument", "الإجراء غير صالح.");
    }
    const reviewRef = db.collection("owner_lesson_reviews").doc(reviewId);
    const lessonRef = db.collection("lessons").doc(lessonId);
    const [reviewSnap, lessonSnap] = await Promise.all([
      reviewRef.get(),
      lessonRef.get(),
    ]);
    if (!reviewSnap.exists || reviewSnap.data().lessonId !== lessonId) {
      throw new functions.https.HttpsError("not-found", "سجل المراجعة غير موجود.");
    }
    let cleanup = { failed: [], cleanupJobId: "" };
    if (action === "verified") {
      if (!lessonSnap.exists) {
        throw new functions.https.HttpsError("not-found", "الدرس غير موجود.");
      }
      const batch = db.batch();
      batch.update(reviewRef, {
        status: "verified",
        resolvedBy: actorEmail,
        resolvedAt: admin.firestore.FieldValue.serverTimestamp(),
        resolution: "verified",
      });
      batch.update(lessonRef, {
        moderationStatus: "verified",
        moderationVerifiedBy: actorEmail,
        moderationVerifiedAt: admin.firestore.FieldValue.serverTimestamp(),
      });
      await batch.commit();
    } else {
      const paths = lessonSnap.exists
        ? lessonStoragePaths(unwrapLegacy(lessonSnap.data()))
        : [];
      const batch = db.batch();
      if (lessonSnap.exists) batch.delete(lessonRef);
      batch.update(reviewRef, {
        status: "deleted",
        resolvedBy: actorEmail,
        resolvedAt: admin.firestore.FieldValue.serverTimestamp(),
        resolution: "delete",
      });
      await batch.commit();
      cleanup = await deletePathsBestEffort(
        paths,
        "delete_suspicious_lesson",
        lessonId,
      );
    }
    // اتُّخذ القرار — تنبيه «درس يحتاج مراجعتك» يختفي من تنبيهات المالك.
    await clearAdminAlerts("suspicious_lesson", lessonId);
    await auditOwnerAction(actorEmail, `suspicious_${action}`, lessonId, {
      reviewId,
      cleanupPending: cleanup.failed.length > 0,
      cleanupJobId: cleanup.cleanupJobId,
    });
    return {
      ok: true,
      action,
      lessonId,
      cleanupPending: cleanup.failed.length > 0,
      cleanupJobId: cleanup.cleanupJobId,
    };
  });

/// مسح تنبيهات «درس يحتاج مراجعتك» لمجموعة دروس بمرور واحد على
/// admin_alerts — بدل مرور كامل لكلّ درس كما في clearAdminAlerts.
async function clearSuspicionAlertsFor(lessonIds) {
  const wanted = new Set(lessonIds.filter(Boolean));
  if (!wanted.size) return 0;
  const snap = await db.collection("admin_alerts").get();
  const refs = snap.docs.filter((doc) => {
    const value = doc.data() || {};
    const metadata = value.data || {};
    if (cleanString(value.type || metadata.type, 40) !== "suspicious_lesson") {
      return false;
    }
    const refId = cleanString(
      value.refId || metadata.refId || metadata.lessonId || metadata.id,
      180,
    );
    return wanted.has(refId);
  }).map((doc) => doc.ref);
  for (let offset = 0; offset < refs.length; offset += 400) {
    const batch = db.batch();
    refs.slice(offset, offset + 400).forEach((ref) => batch.delete(ref));
    await batch.commit();
  }
  return refs.length;
}

/// حسم جماعي: «اعتماد الكل» في شاشة المراجعة. يعتمد فقط — الحذف يبقى
/// فردياً بقرار واعٍ لكل درس (لا حذف جماعي أبداً).
/// `reviewIds` فارغة تعني كل المراجعات المعلّقة.
exports.bulkResolveSuspiciousLessons = functions
  .runWith({ timeoutSeconds: 540, memory: "512MB" })
  .https.onCall(async (data, context) => {
    const actorEmail = await assertOwner(context);
    const action = cleanString(data && data.action, 20) || "verified";
    if (action !== "verified") {
      throw new functions.https.HttpsError(
        "invalid-argument",
        "الإجراء الجماعي المتاح هو الاعتماد فقط.",
      );
    }
    const requested = Array.isArray(data && data.reviewIds)
      ? [...new Set(
        data.reviewIds.map((item) => cleanString(item, 180)).filter(Boolean),
      )].slice(0, 1000)
      : [];
    const reviewsRef = db.collection("owner_lesson_reviews");
    let docs = [];
    if (requested.length) {
      for (let offset = 0; offset < requested.length; offset += 200) {
        const refs = requested.slice(offset, offset + 200)
          .map((id) => reviewsRef.doc(id));
        const snaps = await db.getAll(...refs);
        docs = docs.concat(snaps.filter((item) => item.exists));
      }
    } else {
      const snap = await reviewsRef.where("status", "==", "pending").get();
      docs = snap.docs;
    }
    // لا يُحسم إلا ما هو معلّق فعلاً («flagged» حالة قديمة معلّقة أيضاً).
    const targets = docs
      .filter((doc) => {
        const status = cleanString((doc.data() || {}).status, 40) || "pending";
        return status === "pending" || status === "flagged";
      })
      .map((doc) => ({
        ref: doc.ref,
        lessonId: cleanString((doc.data() || {}).lessonId, 180) || doc.id,
      }));
    if (!targets.length) {
      return { ok: true, verified: 0, missingLessons: 0, alertsCleared: 0 };
    }
    let verified = 0;
    let missingLessons = 0;
    for (let offset = 0; offset < targets.length; offset += 200) {
      const chunk = targets.slice(offset, offset + 200);
      const lessonRefs = chunk.map(
        (item) => db.collection("lessons").doc(item.lessonId),
      );
      const lessonSnaps = await db.getAll(...lessonRefs);
      const batch = db.batch();
      chunk.forEach((item, index) => {
        const lessonExists = lessonSnaps[index] && lessonSnaps[index].exists;
        batch.update(item.ref, {
          status: "verified",
          resolvedBy: actorEmail,
          resolvedAt: admin.firestore.FieldValue.serverTimestamp(),
          resolution: lessonExists ? "verified_bulk" : "lesson_missing",
        });
        if (lessonExists) {
          batch.update(lessonRefs[index], {
            moderationStatus: "verified",
            moderationVerifiedBy: actorEmail,
            moderationVerifiedAt: admin.firestore.FieldValue.serverTimestamp(),
          });
        } else {
          missingLessons += 1;
        }
      });
      await batch.commit();
      verified += chunk.length;
    }
    const alertsCleared = await clearSuspicionAlertsFor(
      targets.map((item) => item.lessonId),
    );
    await auditOwnerAction(actorEmail, "suspicious_bulk_verified", "", {
      verified,
      missingLessons,
      alertsCleared,
      scope: requested.length ? "selection" : "all_pending",
    });
    return { ok: true, verified, missingLessons, alertsCleared };
  });

// ─── الإشعار اليدوي من تطبيق الإدارة ────────────────────────────────
exports.sendNotification = functions.https.onCall(async (data, context) => {
  const actor = await assertAuthorized(context);
  const title = cleanString(data && data.title, 80);
  const body = cleanString(data && data.body, 500);
  if (!title && !body) {
    throw new functions.https.HttpsError("invalid-argument", "العنوان أو النص مطلوب.");
  }
  // بلا عنوان من اللوحة: يسقط إلى اسم التطبيق «منبر ادكصهك» داخل
  // pushToTopic — لا إلى كلمة «إشعار» العامّة.
  const messageId = await pushToTopic(title, body, { type: "manual" });
  await auditOwnerAction(actor.email, "send_notification", "", {
    title,
    bodyLength: body.length,
  });
  return { ok: true, messageId };
});

// ⭐ إنهاء تمييز الدروس التي انقضت مدّتها. التطبيق العام يُخفيها فوراً
// بترشيح محلّي، وهذه تُنظّف الراية في القاعدة كي يستقيم المصدر ولا تظهر
// عند النسخ القديمة التي لا تعرف featuredUntil.
//
// ⏳ وقبل السقوط بساعات: إنذار موجَّه إلى مَن أضاف الدرس («مدّده أو دعه
// يسقط») — التمييز كان يسقط صامتاً فيفاجَأ صاحبه باختفاء درسه من
// «مختارات المنبر». الإنذار مرّة واحدة لكل مدّة (وسم featuredExpiryWarnedFor
// يحمل قيمة featuredUntil نفسها)، فتمديد المدّة يستحق إنذاراً جديداً.
const FEATURED_WARN_BEFORE_MS = 6 * 60 * 60 * 1000;

exports.expireFeaturedLessons = functions.pubsub
  .schedule("every 30 minutes")
  .timeZone("Asia/Riyadh")
  .onRun(async () => {
    const nowIso = new Date().toISOString();
    const nowMs = Date.now();
    const snap = await db.collection("lessons")
      .where("featured", "==", true)
      .get();
    let cleared = 0;
    let batch = db.batch();
    let pending = 0;
    const warnings = [];
    for (const doc of snap.docs) {
      const value = doc.data() || {};
      const wrapped = value.data && typeof value.data === "object";
      const inner = wrapped ? value.data : value;
      const until = value.featuredUntil || (wrapped && value.data.featuredUntil);
      if (!until) continue; // تمييز دائم.
      const ms = Date.parse(until);
      if (Number.isNaN(ms)) continue;
      if (ms > nowMs) {
        if (ms - nowMs > FEATURED_WARN_BEFORE_MS) continue;
        const warnedFor = cleanString(inner.featuredExpiryWarnedFor, 60);
        const target = normalizeEmail(inner.addedBy || inner.createdByEmail);
        if (!target || warnedFor === String(until)) continue;
        warnings.push({
          ref: doc.ref,
          id: doc.id,
          wrapped,
          until: String(until),
          target,
          title: cleanString(inner.title || inner.name, 180),
          remainingMs: ms - nowMs,
        });
        continue;
      }
      batch.update(doc.ref, wrapped
        ? {
          "data.featured": false,
          "data.featuredUntil": admin.firestore.FieldValue.delete(),
          "data.featuredExpiredAt": nowIso,
        }
        : {
          featured: false,
          featuredUntil: admin.firestore.FieldValue.delete(),
          featuredExpiredAt: nowIso,
        });
      cleared += 1;
      pending += 1;
      if (pending >= 400) {
        await batch.commit();
        batch = db.batch();
        pending = 0;
      }
    }
    if (pending > 0) await batch.commit();
    if (cleared > 0) console.log(`expireFeaturedLessons: cleared ${cleared}`);

    let warned = 0;
    for (const item of warnings) {
      const hours = Math.max(
        1,
        Math.round(item.remainingMs / (60 * 60 * 1000)),
      );
      // صياغة عربية سليمة للعدد (ساعة/ساعتين/ساعات) لا «1 ساعات».
      const hoursText = hours === 1
        ? "ساعة واحدة"
        : hours === 2 ? "ساعتين" : `${hours} ساعات`;
      const alertTitle = "⭐ تمييز درسك يوشك أن ينتهي";
      const alertBody = `تمييز «${item.title || "درسك"}» ينتهي بعد ${hoursText}`
        + " تقريباً — مدّده أو دعه يسقط من مختارات المنبر.";
      try {
        await Promise.all([
          writeAdminAlert(item.target, alertTitle, alertBody, {
            type: "featured_expiring",
            lessonId: item.id,
            refId: item.id,
            featuredUntil: item.until,
          }),
          pushToAdminsFiltered(alertTitle, alertBody, {
            type: "featured_expiring",
            lessonId: item.id,
            refId: item.id,
          }, { targetEmail: item.target }),
        ]);
        // الوسم بعد نجاح الإرسال وحده، كي تُعاد المحاولة في الدورة التالية
        // إن فشل — ولا يتكرّر الإنذار إن نجح.
        await item.ref.update(item.wrapped
          ? {
            "data.featuredExpiryWarnedFor": item.until,
            "data.featuredExpiryWarnedAt": nowIso,
          }
          : {
            featuredExpiryWarnedFor: item.until,
            featuredExpiryWarnedAt: nowIso,
          });
        warned += 1;
      } catch (error) {
        console.error("featured expiry warning failed", item.id, error);
      }
    }
    if (warned > 0) console.log(`expireFeaturedLessons: warned ${warned}`);
    return null;
  });

// تنظيف يومي للملفات اليتيمة وإعادة محاولة مهام تنظيف التخزين الفاشلة.
exports.cleanupOrphanSubmissionUploads = functions.runWith({
  timeoutSeconds: 540,
  memory: "512MB",
}).pubsub.schedule("30 3 * * *").timeZone("Asia/Riyadh").onRun(async () => {
  const now = Date.now();
  const [files] = await bucket.getFiles({ prefix: "submissions/" });
  const submissionsSnap = await db.collection("lesson_submissions").get();
  const submissions = new Map(
    submissionsSnap.docs.map((doc) => [doc.id, doc.data() || {}]),
  );
  let deletedOrphans = 0;
  let failedOrphans = 0;
  for (const file of files) {
    const parts = file.name.split("/");
    if (parts.length < 4) continue;
    const submissionId = parts[2];
    const submission = submissions.get(submissionId);
    const createdAt = Date.parse(file.metadata && file.metadata.timeCreated || "") || 0;
    const oldEnough = createdAt && now - createdAt > 24 * 60 * 60 * 1000;
    const orphan = !submission && oldEnough;
    const decidedLeftover = submission && submission.status !== "pending";
    if (!orphan && !decidedLeftover) continue;
    try {
      await file.delete({ ignoreNotFound: true });
      deletedOrphans += 1;
    } catch (error) {
      console.error("orphan cleanup failed", file.name, error);
      failedOrphans += 1;
    }
  }

  const jobsSnap = await db.collection("storage_cleanup_jobs")
    .where("status", "==", "pending")
    .limit(100)
    .get();
  let completedJobs = 0;
  for (const job of jobsSnap.docs) {
    const value = job.data() || {};
    const failed = [];
    for (const path of Array.isArray(value.paths) ? value.paths : []) {
      try {
        await deleteFileIfExists(path);
      } catch (_) {
        failed.push(path);
      }
    }
    if (!failed.length) {
      await job.ref.update({
        status: "done",
        completedAt: admin.firestore.FieldValue.serverTimestamp(),
      });
      completedJobs += 1;
    } else {
      await job.ref.update({
        paths: failed,
        attempts: Number(value.attempts || 0) + 1,
        lastAttemptAt: admin.firestore.FieldValue.serverTimestamp(),
      });
    }
  }
  await auditOwnerAction("system", "cleanup_orphan_submission_uploads", "", {
    scannedFiles: files.length,
    deletedOrphans,
    failedOrphans,
    cleanupJobsScanned: jobsSnap.size,
    completedJobs,
  });
  return null;
});

// ─── اعتماد المشرفين: رمز مستقل لكل بريد ────────────────────────────
function generateSixDigitCode() {
  return String(crypto.randomInt(0, 1_000_000)).padStart(6, "0");
}

exports.onCodeRequested = functions.firestore
  .document("dashboard_code_requests/{email}")
  .onCreate(async (snap, context) => {
    const email = normalizeEmail(decodeURIComponent(context.params.email));
    const requestData = snap.data() || {};
    try {
      if (email === OWNER_EMAIL) {
        await snap.ref.update({ result: "already_authorized" });
        return null;
      }
      const adminSnap = await db.collection(ADMINS_COLLECTION).doc(email).get();
      const adminData = adminSnap.data() || {};
      if (adminSnap.exists) {
        await snap.ref.update({
          result: adminData.blocked === true ? "blocked" : "already_authorized",
        });
        return null;
      }
      const codeRef = db.collection("dashboard_owner_codes").doc(email);
      const now = Date.now();
      const codeSnap = await codeRef.get();
      const previous = codeSnap.data() || {};
      const lastRequestedAt = Number(previous.lastRequestedAt || 0);
      if (lastRequestedAt && now - lastRequestedAt < CODE_REQUEST_INTERVAL_MS) {
        await snap.ref.update({
          result: "rate_limited",
          retryAfterSec: Math.ceil(
            (CODE_REQUEST_INTERVAL_MS - (now - lastRequestedAt)) / 1000,
          ),
        });
        return null;
      }
      const codeData = {
        code: generateSixDigitCode(),
        candidateUid: cleanString(requestData.uid, 180),
        candidateEmail: email,
        candidateName: cleanString(requestData.name, 100),
        candidatePhotoURL: cleanString(requestData.photoURL, 2048),
        createdAt: now,
        expiresAt: now + CODE_TTL_MS,
        attempts: 0,
        lastRequestedAt: now,
      };
      await codeRef.set(codeData);
      // مرآة توافق مؤقتة للنسخة القديمة التي تراقب current فقط.
      await db.collection("dashboard_owner_codes").doc("current").set(codeData);
      await snap.ref.update({ result: "ok" });
      const candidate = codeData.candidateName || email;
      const alertBody = `رمز اعتماد ${candidate} هو ${codeData.code}، وصالح لمدة 10 دقائق.`;
      await clearAdminAlerts("owner_code", email);
      await Promise.all([
        writeAdminAlert(OWNER_EMAIL, "رمز اعتماد مشرف جديد", alertBody, {
          type: "owner_code",
          refId: email,
          candidateEmail: email,
          expiresAt: codeData.expiresAt,
        }),
        pushToAdmins(
          "رمز اعتماد مشرف جديد",
          alertBody,
          { type: "owner_code", candidateEmail: email, refId: email },
          true,
        ),
      ]);
      return null;
    } catch (error) {
      console.error("onCodeRequested failed", error);
      await snap.ref.update({ result: "error" }).catch(() => {});
      return null;
    }
  });

exports.onCodeVerifyRequested = functions.firestore
  .document("dashboard_code_verify/{email}")
  .onCreate(async (snap, context) => {
    const email = normalizeEmail(decodeURIComponent(context.params.email));
    const entered = cleanString((snap.data() || {}).code, 6);
    const codeRef = db.collection("dashboard_owner_codes").doc(email);
    const adminRef = db.collection(ADMINS_COLLECTION).doc(email);
    const now = Date.now();
    try {
      await db.runTransaction(async (tx) => {
        const codeSnap = await tx.get(codeRef);
        if (!codeSnap.exists) {
          tx.update(snap.ref, { result: "no_code" });
          return;
        }
        const value = codeSnap.data() || {};
        if (now > Number(value.expiresAt || 0)) {
          tx.delete(codeRef);
          tx.update(snap.ref, { result: "expired" });
          return;
        }
        const attempts = Number(value.attempts || 0);
        if (attempts >= MAX_CODE_ATTEMPTS) {
          tx.delete(codeRef);
          tx.update(snap.ref, { result: "too_many_attempts" });
          return;
        }
        if (!/^\d{6}$/.test(entered) || entered !== String(value.code || "")) {
          const next = attempts + 1;
          if (next >= MAX_CODE_ATTEMPTS) {
            tx.delete(codeRef);
            tx.update(snap.ref, { result: "too_many_attempts" });
          } else {
            tx.update(codeRef, { attempts: next });
            tx.update(snap.ref, { result: "invalid" });
          }
          return;
        }
        tx.set(adminRef, {
          email,
          role: "supervisor",
          blocked: false,
          displayName: cleanString(value.candidateName, 100),
          photoURL: cleanString(value.candidatePhotoURL, 2048),
          addedBy: "owner_code_approval",
          addedAt: now,
          lastSignedInAt: now,
        });
        tx.delete(codeRef);
        tx.update(snap.ref, { result: "ok" });
      });
      const mirrorRef = db.collection("dashboard_owner_codes").doc("current");
      const mirror = await mirrorRef.get();
      if (mirror.exists && normalizeEmail(mirror.data().candidateEmail) === email) {
        await mirrorRef.delete();
      }
      const resultSnap = await snap.ref.get();
      const result = cleanString((resultSnap.data() || {}).result, 40);
      if (["ok", "expired", "too_many_attempts", "no_code"].includes(result)) {
        await clearAdminAlerts("owner_code", email);
      }
    } catch (error) {
      console.error("onCodeVerifyRequested failed", error);
      await snap.ref.update({ result: "error" }).catch(() => {});
    }
    return null;
  });

// ─── المكالمات الصوتيّة بين المشرفين (تنبيه data-only) ──────────────
//
// ⚠️ لا تستعمل sendToAdminTargets هنا إطلاقاً: هو يُدرج كتلة notification
// وقناة admin_alerts، فيرسم النظام الإشعار بنفسه في الخلفية ولا يعمل كود
// المكالمة (onMessageReceived) أصلاً. المكالمة تحتاج رسالة data-only
// بأولوية عالية كي يستيقظ الجهاز ويعرض شاشة الرنين هو.
//
// وكتم الدردشة (chatMuted) لا يُطبَّق هنا عمداً: الكتم للرسائل لا للمكالمات.
async function sendCallPush(targetUid, data) {
  const target = cleanString(targetUid, 180);
  if (!target) return { successCount: 0, failureCount: 0 };
  const targets = (await activeAdminTokens(false)).filter(
    (item) => item.uid === target,
  );
  if (!targets.length) return { successCount: 0, failureCount: 0 };
  let successCount = 0;
  let failureCount = 0;
  for (let offset = 0; offset < targets.length; offset += 500) {
    const chunk = targets.slice(offset, offset + 500);
    const response = await admin.messaging().sendEachForMulticast({
      tokens: chunk.map((item) => item.token),
      data: safeData(data),
      android: { priority: "high" },
    });
    successCount += response.successCount;
    failureCount += response.failureCount;
    const removals = [];
    response.responses.forEach((item, index) => {
      const code = item.error && item.error.code || "";
      if (code.includes("registration-token-not-registered")
          || code.includes("invalid-registration-token")) {
        removals.push(chunk[index].ref.delete());
      }
    });
    await Promise.all(removals);
  }
  return { successCount, failureCount };
}

// الحالات التي تُسقط شاشة الرنين عند الطرفين.
const CALL_CANCEL_STATUSES = ["declined", "ended", "missed", "busy"];

exports.onAdminCallCreated = functions.firestore
  .document("admin_calls/{callId}")
  .onCreate(async (snap, context) => {
    const value = snap.data() || {};
    if (cleanString(value.status, 20) !== "ringing") return null;
    const calleeId = cleanString(value.calleeId, 180);
    if (!calleeId) return null;
    try {
      await sendCallPush(calleeId, {
        type: "admin_call",
        action: "incoming",
        callId: cleanString(context.params.callId, 200),
        callerId: cleanString(value.callerId, 180),
        callerName: cleanString(value.callerName || "مشرف", 100),
        callerPhoto: cleanString(value.callerPhoto, 2048),
      });
    } catch (error) {
      console.error("onAdminCallCreated failed", error);
    }
    return null;
  });

exports.onAdminCallUpdated = functions.firestore
  .document("admin_calls/{callId}")
  .onUpdate(async (change, context) => {
    const before = change.before.data() || {};
    const after = change.after.data() || {};
    const beforeStatus = cleanString(before.status, 20);
    const afterStatus = cleanString(after.status, 20);
    if (beforeStatus === afterStatus) return null;
    const accepted = afterStatus === "accepted";
    if (!accepted && !CALL_CANCEL_STATUSES.includes(afterStatus)) return null;

    const callId = cleanString(context.params.callId, 200);
    // عند القبول نوقف رنين أجهزة المستقبِل الأخرى فقط؛ المتّصل يبقى في
    // المكالمة. أمّا النهاية فتسقط الشاشة عند الطرفين.
    const members = accepted
      ? [after.calleeId]
      : (Array.isArray(after.members) ? after.members : [])
        .concat([after.callerId, after.calleeId]);
    const targets = [];
    members.forEach((raw) => {
      const uid = cleanString(raw, 180);
      if (uid && !targets.includes(uid)) targets.push(uid);
    });
    if (!targets.length) return null;

    try {
      await Promise.all(targets.map((uid) => sendCallPush(uid, {
        type: "admin_call",
        action: "cancel",
        callId,
      })));
    } catch (error) {
      console.error("onAdminCallUpdated failed", error);
    }
    return null;
  });
