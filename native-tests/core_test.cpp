// Host-side tests for the C++ puzzle core (any C++17 compiler):
//   g++ -std=c++17 -O2 native-tests/core_test.cpp app/src/main/cpp/puzzle_core.cpp -o core_test && ./core_test 300
#include <chrono>
#include <cstdio>
#include <cstdlib>
#include <vector>

#include "../app/src/main/cpp/puzzle_core.h"

using puzzle::Board;
using puzzle::Outcome;

namespace {
int failures = 0;
#define CHECK(cond, ...)                         \
    do {                                         \
        if (!(cond)) {                           \
            std::printf("FAIL: " __VA_ARGS__);   \
            std::printf("\n");                   \
            ++failures;                          \
        }                                        \
    } while (0)

// Plays a board to the end by following hints, as a player using only hints would.
bool playByHints(Board b, int* moves) {
    *moves = 0;
    for (int guard = 0; guard < 5000 && b.remaining() > 0; ++guard) {
        const auto h = b.hint();
        if (h.first < 0) return false;
        if (!b.tap(h.first, h.second).success) return false;
        ++*moves;
    }
    return b.remaining() == 0;
}

// Real-game rule: an arrow whose path runs into anything, including its own body, is blocked.
void ruleTests() {
    for (int level = 1; level <= 120; ++level) {
        Board b(level);
        for (int id = 0; id < b.arrowCount(); ++id) {
            const puzzle::Arrow& a = b.arrow(id);
            int x = a.pts.back().x + a.dx, y = a.pts.back().y + a.dy;
            bool clear = true;
            while (x >= 0 && y >= 0 && x < b.cols() && y < b.rows()) {
                for (int o = 0; o < b.arrowCount() && clear; ++o)
                    for (const auto& p : b.arrow(o).pts)
                        if (b.arrow(o).alive && p.x == x && p.y == y) { clear = false; break; }
                x += a.dx; y += a.dy;
            }
            const bool exits = b.trace(id, false).outcome == Outcome::Exit;
            CHECK(exits == clear, "level %d arrow %d: trace says %s but its path is %s", level, id,
                  exits ? "free" : "blocked", clear ? "clear" : "blocked");
            if (!clear) {
                Board copy = b;
                CHECK(!copy.tap(id, false).success, "level %d arrow %d: blocked arrow was released", level, id);
            }
        }
    }
}

// Dense boards (level 4 on) look like the real game: fully covered by long arrows, with only
// a handful of them able to leave at the start.
void generatorTests() {
    int boards = 0;
    long arrows = 0, cells = 0, stubs = 0;
    double freeShare = 0.0;
    auto check = [&](const Board& b, const char* kind, int level) {
        int area = 0, covered = 0;
        for (int y = 0; y < b.rows(); ++y)
            for (int x = 0; x < b.cols(); ++x) area += b.inMask(x, y) ? 1 : 0;
        for (int i = 0; i < b.arrowCount(); ++i) {
            const int len = static_cast<int>(b.arrow(i).pts.size());
            covered += len;
            stubs += len <= 2 ? 1 : 0;
        }
        int free = 0;
        const int depth = b.fastDepth(&free);
        CHECK(depth > 0, "%s %d: no greedy solution", kind, level);
        CHECK(covered * 100 >= area * 98, "%s %d: only %d of %d cells covered", kind, level, covered, area);
        CHECK(free >= 1, "%s %d: no arrow can move at the start", kind, level);
        CHECK(free * 100 <= b.arrowCount() * (b.arrowCount() < 40 ? 25 : 20), "%s %d: %d of %d arrows free at the start", kind, level, free,
              b.arrowCount());
        ++boards;
        arrows += b.arrowCount();
        cells += covered;
        freeShare += static_cast<double>(free) / b.arrowCount();
    };
    for (int level = 4; level <= 300; level += 2) check(Board(level), "level", level);
    for (uint32_t day = 20260101; day < 20260111; ++day) check(Board(30 + day % 45, day), "daily", static_cast<int>(day));
    for (uint32_t key = 1; key <= 3; ++key) check(Board(40, puzzle::kVariantFlag | key), "mode board", static_cast<int>(key));

    const double avgLen = static_cast<double>(cells) / arrows;
    const double stubShare = static_cast<double>(stubs) / arrows;
    CHECK(avgLen >= 6.0, "dense arrows average %.2f cells, expected >= 6", avgLen);
    CHECK(stubShare <= 0.02, "%.1f%% of dense arrows are 1-2 cells, expected <= 2%%", stubShare * 100);
    CHECK(freeShare / boards <= 0.08, "%.1f%% of arrows free at the start on average, expected <= 8%%",
          freeShare / boards * 100);
    std::printf("generator: boards=%d avgLen=%.2f stubs=%.2f%% freeAtStart=%.1f%%\n", boards, avgLen, stubShare * 100,
                freeShare / boards * 100);
}

// Mode boards (kVariantFlag): sized and tiered like the level, laid out differently per mode.
void variantTests() {
    for (int level : {6, 15, 40, 90}) {
        const Board plain(level);
        for (uint32_t key = 1; key <= 3; ++key) {
            const Board mode(level, puzzle::kVariantFlag | key);
            CHECK(mode.solvable(), "level %d mode %u unsolvable", level, key);
            CHECK(mode.difficulty() == plain.difficulty(), "level %d mode %u: difficulty differs", level, key);
            // the shape (heart, circle...) may differ, but the amount of work stays the level's
            CHECK(mode.arrowCount() * 2 >= plain.arrowCount() && mode.arrowCount() <= plain.arrowCount() * 2,
                  "level %d mode %u: %d arrows against the level's %d", level, key, mode.arrowCount(), plain.arrowCount());
            bool same = mode.arrowCount() == plain.arrowCount();
            for (int i = 0; same && i < mode.arrowCount(); ++i)
                same = mode.arrow(i).pts.size() == plain.arrow(i).pts.size() &&
                       mode.arrow(i).pts[0].x == plain.arrow(i).pts[0].x && mode.arrow(i).pts[0].y == plain.arrow(i).pts[0].y;
            CHECK(!same, "level %d mode %u: same layout as the level", level, key);
        }
        const Board a(level, puzzle::kVariantFlag | 3u), b(level, puzzle::kVariantFlag | 2u);
        CHECK(a.arrowCount() != b.arrowCount() || a.arrow(0).pts[0].x != b.arrow(0).pts[0].x ||
              a.arrow(0).pts[0].y != b.arrow(0).pts[0].y, "level %d: modes 2 and 3 share a layout", level);
    }
    // Duel (key 2) and Time rush (key 3) play a fresh round each time: salt = flag | round << 2 | key,
    // as Levels.modeSalt builds it. Every round must be solvable and new.
    for (int level : {4, 6, 8, 9, 10, 12, 14}) {
        for (uint32_t key = 2; key <= 3; ++key) {
            std::vector<uint64_t> seen;
            for (uint32_t round = 0; round < 6; ++round) {
                const Board b(level, puzzle::kVariantFlag | (round << 2) | key);
                CHECK(b.solvable(), "level %d mode %u round %u unsolvable", level, key, round);
                uint64_t sig = 1469598103934665603ull;  // FNV-1a over every cell of every arrow
                for (int i = 0; i < b.arrowCount(); ++i)
                    for (const auto& p : b.arrow(i).pts) sig = (sig ^ static_cast<uint64_t>(p.x * 4099 + p.y * 7 + i)) * 1099511628211ull;
                for (const auto& s : seen) CHECK(s != sig, "level %d mode %u round %u repeats a layout", level, key, round);
                seen.push_back(sig);
            }
        }
    }
}

// Every shape must make a proper board: one connected piece, fully tiled by long arrows,
// solvable, and about as many arrows as a plain rectangle of the same level.
void shapeTests() {
    const int shapes = static_cast<int>(puzzle::Shape::Count);
    CHECK(shapes >= 19, "expected the full shape set, got %d", shapes);
    for (int level : {6, 9, 14, 25, 40, 70}) {
        const Board plain(level, 0, 0);
        for (int sh = 1; sh < shapes; ++sh) {
            const Board b(level, 0, sh);
            CHECK(b.wellFormed(), "level %d shape %d malformed", level, sh);
            CHECK(b.solvable(), "level %d shape %d unsolvable", level, sh);
            int area = 0;
            for (int y = 0; y < b.rows(); ++y) for (int x = 0; x < b.cols(); ++x) if (b.inMask(x, y)) ++area;
            CHECK(area < b.cols() * b.rows(), "level %d shape %d is a full rectangle", level, sh);
            // one connected piece
            std::vector<char> seen(b.cols() * b.rows(), 0);
            std::vector<int> st;
            int first = -1, reached = 0;
            for (int i = 0; i < b.cols() * b.rows() && first < 0; ++i) if (b.inMask(i % b.cols(), i / b.cols())) first = i;
            st.push_back(first); seen[first] = 1;
            while (!st.empty()) {
                const int c = st.back(); st.pop_back(); ++reached;
                const int cx = c % b.cols(), cy = c / b.cols();
                const int nx[4] = {cx + 1, cx - 1, cx, cx}, ny[4] = {cy, cy, cy + 1, cy - 1};
                for (int k = 0; k < 4; ++k)
                    if (b.inMask(nx[k], ny[k]) && !seen[ny[k] * b.cols() + nx[k]]) { seen[ny[k] * b.cols() + nx[k]] = 1; st.push_back(ny[k] * b.cols() + nx[k]); }
            }
            CHECK(reached == area, "level %d shape %d is split in pieces (%d of %d cells)", level, sh, reached, area);
            int covered = 0, stubs = 0;
            for (int i = 0; i < b.arrowCount(); ++i) {
                covered += static_cast<int>(b.arrow(i).pts.size());
                if (b.arrow(i).pts.size() <= 2) ++stubs;
            }
            CHECK(covered * 100 >= area * 97, "level %d shape %d: arrows cover %d of %d cells", level, sh, covered, area);
            CHECK(stubs * 20 <= b.arrowCount() + 1, "level %d shape %d: %d of %d arrows are tiny", level, sh, stubs, b.arrowCount());
            CHECK(b.arrowCount() * 10 >= plain.arrowCount() * 6 && b.arrowCount() * 10 <= plain.arrowCount() * 16,
                  "level %d shape %d: %d arrows against %d for a rectangle", level, sh, b.arrowCount(), plain.arrowCount());
        }
    }
    // shapes show up often, daily boards included, and all of them get used
    int shaped = 0, total = 0, dailyShaped = 0, dailies = 0;
    std::vector<int> used(shapes, 0);
    for (int level = 6; level <= 140; ++level) {
        const Board b(level);
        int area = 0;
        for (int y = 0; y < b.rows(); ++y) for (int x = 0; x < b.cols(); ++x) if (b.inMask(x, y)) ++area;
        ++total; if (area < b.cols() * b.rows()) ++shaped;
    }
    for (uint32_t d = 20261001; d < 20261031; ++d) {
        const Board b(30 + (d % 9973) % 45, d);
        int area = 0;
        for (int y = 0; y < b.rows(); ++y) for (int x = 0; x < b.cols(); ++x) if (b.inMask(x, y)) ++area;
        ++dailies; if (area < b.cols() * b.rows()) ++dailyShaped;
    }
    for (int level = 6; level < 400; ++level) used[static_cast<int>(Board::config(level).shape)]++;
    CHECK(shaped * 100 >= total * 45, "only %d of %d levels have a shape", shaped, total);
    CHECK(dailyShaped >= 8 && dailyShaped <= dailies - 4, "%d of %d dailies have a shape", dailyShaped, dailies);
    for (int sh = 1; sh < shapes; ++sh) CHECK(used[sh] > 0, "shape %d never used in levels 6..400", sh);
}

void mechanicUnitTests() {
    // Every mechanic's introduction level must really contain that mechanic.
    const int intro[] = {15, 25, 35, 45, 55, 65};
    for (int m = 0; m < 6; ++m) {
        Board b(intro[m]);
        const bool present[] = {
            !b.portals().empty(), !b.stops().empty(), !b.redirects().empty(), !b.zones().empty(),
            [&] { for (int i = 0; i < b.arrowCount(); ++i) if (b.arrow(i).twoWay) return true; return false; }(),
            [&] { for (int i = 0; i < b.arrowCount(); ++i) if (b.arrow(i).link >= 0) return true; return false; }(),
        };
        CHECK(b.introduces() == m, "level %d introduces %d, expected %d", intro[m], b.introduces(), m);
        CHECK(present[m], "level %d is missing mechanic %d", intro[m], m);
    }

    // A wrong tap never changes the board.
    for (int level = 2; level < 80; ++level) {
        Board b(level);
        for (int id = 0; id < b.arrowCount(); ++id) {
            const auto t = b.trace(id, false);
            if (t.outcome != Outcome::Blocked) continue;
            const int before = b.remaining();
            const auto r = b.tap(id, false);
            if (b.arrow(id).link < 0) CHECK(!r.success, "level %d arrow %d blocked but tap succeeded", level, id);
            if (!r.success) CHECK(b.remaining() == before, "level %d failed tap changed board", level);
            break;
        }
    }
}
} // namespace

int main(int argc, char** argv) {
    const int maxLevel = argc > 1 ? std::atoi(argv[1]) : 300;
    long arrows = 0;
    double worstMs = 0;
    int withMechanics = 0;

    ruleTests();
    generatorTests();
    variantTests();
    shapeTests();

    for (int level = 1; level <= maxLevel; ++level) {
        const auto t0 = std::chrono::steady_clock::now();
        Board a(level);
        const double ms = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - t0).count();
        if (ms > worstMs) worstMs = ms;

        CHECK(a.wellFormed(), "level %d malformed", level);
        CHECK(a.arrowCount() > 0, "level %d has no arrows", level);
        CHECK(a.solvable(), "level %d unsolvable", level);
        int moves = 0;
        CHECK(playByHints(a, &moves), "level %d cannot be finished by following hints", level);

        Board b(level);
        bool same = a.arrowCount() == b.arrowCount();
        for (int i = 0; same && i < a.arrowCount(); ++i) {
            same = a.arrow(i).pts.size() == b.arrow(i).pts.size();
            for (size_t k = 0; same && k < a.arrow(i).pts.size(); ++k)
                same = a.arrow(i).pts[k].x == b.arrow(i).pts[k].x && a.arrow(i).pts[k].y == b.arrow(i).pts[k].y;
        }
        CHECK(same, "level %d not deterministic", level);

        arrows += a.arrowCount();
        if (!a.portals().empty() || !a.stops().empty() || !a.redirects().empty() || !a.zones().empty()) ++withMechanics;
    }

    // Daily challenges use a salt; a few of them must work too.
    for (uint32_t day = 20260101; day < 20260131; ++day) {
        Board d(40 + day % 30, day);
        CHECK(d.solvable(), "daily %u unsolvable", day);
    }

    std::printf("levels=%d failures=%d avgArrows=%.1f withMechanics=%d worstGenMs=%.1f\n", maxLevel, failures,
                static_cast<double>(arrows) / maxLevel, withMechanics, worstMs);
    return failures == 0 ? 0 : 1;
}
