// JNI bridge: exposes puzzle::Board to Kotlin as an opaque handle. Structured data
// crosses the boundary as flat int arrays; layouts are documented on each function
// and mirrored in Native.kt.
#include <jni.h>

#include "puzzle_core.h"

namespace {
puzzle::Board* from(jlong h) { return reinterpret_cast<puzzle::Board*>(h); }

jintArray toJava(JNIEnv* env, const std::vector<int>& v) {
    jintArray out = env->NewIntArray(static_cast<jsize>(v.size()));
    if (out != nullptr && !v.empty()) env->SetIntArrayRegion(out, 0, static_cast<jsize>(v.size()), v.data());
    return out;
}

void putPoints(std::vector<int>& out, const std::vector<puzzle::Pt>& pts) {
    out.push_back(static_cast<int>(pts.size()));
    for (const auto& p : pts) {
        out.push_back(p.x);
        out.push_back(p.y);
        out.push_back(p.jump ? 1 : 0);
    }
}
} // namespace

extern "C" {

JNIEXPORT jlong JNICALL Java_com_arrowpuzzle_game_Native_create(JNIEnv*, jobject, jint level, jint salt) {
    return reinterpret_cast<jlong>(new puzzle::Board(level, static_cast<uint32_t>(salt)));
}

JNIEXPORT void JNICALL Java_com_arrowpuzzle_game_Native_destroy(JNIEnv*, jobject, jlong h) { delete from(h); }

JNIEXPORT jlong JNICALL Java_com_arrowpuzzle_game_Native_copy(JNIEnv*, jobject, jlong h) {
    return reinterpret_cast<jlong>(new puzzle::Board(*from(h)));
}

// [cols, rows, arrowCount, introducedMechanic, remaining, difficulty, mask...(cols*rows)]
JNIEXPORT jintArray JNICALL Java_com_arrowpuzzle_game_Native_info(JNIEnv* env, jobject, jlong h) {
    const puzzle::Board& b = *from(h);
    std::vector<int> out = {b.cols(), b.rows(), b.arrowCount(), b.introduces(), b.remaining(), b.difficulty()};
    for (int y = 0; y < b.rows(); ++y)
        for (int x = 0; x < b.cols(); ++x) out.push_back(b.inMask(x, y) ? 1 : 0);
    return toJava(env, out);
}

// [alive, dx, dy, tdx, tdy, twoWay, link, keyZone, zone, n, (x, y, jump)*n]
JNIEXPORT jintArray JNICALL Java_com_arrowpuzzle_game_Native_arrow(JNIEnv* env, jobject, jlong h, jint id) {
    const puzzle::Arrow& a = from(h)->arrow(id);
    std::vector<int> out = {a.alive ? 1 : 0, a.dx, a.dy, a.tdx, a.tdy, a.twoWay ? 1 : 0, a.link, a.keyZone, a.zone};
    putPoints(out, a.pts);
    return toJava(env, out);
}

// [portalCount, (ax, ay, bx, by, color)*, stopCount, (x, y, alive)*, redirectCount, (x, y, dx, dy)*,
//  zoneCount, (x0, y0, x1, y1, locked)*]
JNIEXPORT jintArray JNICALL Java_com_arrowpuzzle_game_Native_features(JNIEnv* env, jobject, jlong h) {
    const puzzle::Board& b = *from(h);
    std::vector<int> out;
    out.push_back(static_cast<int>(b.portals().size()));
    for (const auto& p : b.portals()) out.insert(out.end(), {p.ax, p.ay, p.bx, p.by, p.color});
    out.push_back(static_cast<int>(b.stops().size()));
    for (const auto& s : b.stops()) out.insert(out.end(), {s.x, s.y, s.alive ? 1 : 0});
    out.push_back(static_cast<int>(b.redirects().size()));
    for (const auto& r : b.redirects()) out.insert(out.end(), {r.x, r.y, r.dx, r.dy});
    out.push_back(static_cast<int>(b.zones().size()));
    for (const auto& z : b.zones()) out.insert(out.end(), {z.x0, z.y0, z.x1, z.y1, z.locked ? 1 : 0});
    return toJava(env, out);
}

// [success, unlockedCount, unlocked..., moveCount,
//  (arrow, reverse, outcome, dx, dy, blocker, stop, n, (x, y, jump)*n)*]
JNIEXPORT jintArray JNICALL Java_com_arrowpuzzle_game_Native_tap(JNIEnv* env, jobject, jlong h, jint id, jboolean reverse) {
    const puzzle::TapResult r = from(h)->tap(id, reverse == JNI_TRUE);
    std::vector<int> out = {r.success ? 1 : 0, static_cast<int>(r.unlockedZones.size())};
    out.insert(out.end(), r.unlockedZones.begin(), r.unlockedZones.end());
    out.push_back(static_cast<int>(r.moves.size()));
    for (const auto& m : r.moves) {
        out.insert(out.end(), {m.arrow, m.reverse ? 1 : 0, static_cast<int>(m.trace.outcome), m.trace.dx, m.trace.dy,
                               m.trace.blocker, m.trace.stop});
        putPoints(out, m.trace.track);
    }
    return toJava(env, out);
}

// Unlocked zone indices.
JNIEXPORT jintArray JNICALL Java_com_arrowpuzzle_game_Native_erase(JNIEnv* env, jobject, jlong h, jint id) {
    return toJava(env, from(h)->erase(id));
}

JNIEXPORT jboolean JNICALL Java_com_arrowpuzzle_game_Native_tappable(JNIEnv*, jobject, jlong h, jint id) {
    return from(h)->tappable(id) ? JNI_TRUE : JNI_FALSE;
}

// [arrow, reverse] or [-1, 0]
JNIEXPORT jintArray JNICALL Java_com_arrowpuzzle_game_Native_hint(JNIEnv* env, jobject, jlong h) {
    const auto hint = from(h)->hint();
    return toJava(env, {hint.first, hint.second ? 1 : 0});
}

JNIEXPORT jint JNICALL Java_com_arrowpuzzle_game_Native_remaining(JNIEnv*, jobject, jlong h) { return from(h)->remaining(); }
JNIEXPORT jboolean JNICALL Java_com_arrowpuzzle_game_Native_solvable(JNIEnv*, jobject, jlong h) {
    return from(h)->solvable() ? JNI_TRUE : JNI_FALSE;
}

} // extern "C"
