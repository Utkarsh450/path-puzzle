#include "puzzle_core.h"

#include <algorithm>
#include <array>
#include <cmath>
#include <numeric>

namespace puzzle {

// mulberry32: tiny, fast and identical on every platform.
class Rng {
public:
    explicit Rng(uint32_t seed) : a_(seed) {}
    double next() {
        a_ += 0x6D2B79F5u;
        uint32_t t = a_;
        t = (t ^ (t >> 15)) * (t | 1u);
        t ^= t + (t ^ (t >> 7)) * (t | 61u);
        return static_cast<double>(t ^ (t >> 14)) / 4294967296.0;
    }
    int below(int n) { return n <= 0 ? 0 : static_cast<int>(next() * n); }

private:
    uint32_t a_;
};

namespace {

constexpr int kDirs[4][2] = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
constexpr double kPi = 3.14159265358979323846;

// Level at which each mechanic first appears (portal, stop, redirect, zone, two-way, link).
constexpr int kUnlockLevel[kMechanicCount] = {15, 25, 35, 45, 55, 65};
constexpr int kMaxCount[kMechanicCount] = {2, 3, 3, 1, 3, 2};
// Portals, stop points, turn markers, lock zones, two-way and magnetic arrows are
// implemented but switched off: boards use the classic rules only.
constexpr bool kMechanicsEnabled = false;

// Normal 0, Hard 1, Expert 2, Master 3 (matches game_screen_difficulty_* in the real game).
int difficultyOf(int level) {
    if (level < 6) return 0;
    if (level % 25 == 0) return 3;
    if (level % 10 == 0) return 2;
    if (level % 5 == 0) return 1;
    return 0;
}

uint32_t mix(uint32_t a, uint32_t b, uint32_t c) {
    uint32_t h = a * 0x9E3779B1u ^ (b + 0x7F4A7C15u) * 0x85EBCA6Bu ^ (c + 0x165667B1u) * 0xC2B2AE35u;
    h ^= h >> 16;
    h *= 0x7FEB352Du;
    h ^= h >> 15;
    return h;
}

// u, v in [-1, 1], v grows downwards.
bool shapeContains(Shape s, double u, double v) {
    const double r = std::sqrt(u * u + v * v);
    switch (s) {
        case Shape::Rect: return true;
        case Shape::Circle: return r <= 1.0;
        case Shape::Diamond: return std::fabs(u) + std::fabs(v) <= 1.08;
        case Shape::Plus: return std::fabs(u) <= 0.42 || std::fabs(v) <= 0.42;
        case Shape::Heart: {
            const double x = u * 1.22, y = -v * 1.22 + 0.22;
            const double a = x * x + y * y - 1.0;
            return a * a * a - x * x * y * y * y <= 0.0;
        }
        case Shape::Star: {
            const double th = std::atan2(v, u) + kPi / 2;
            const double k = 0.5 + 0.5 * (0.5 + 0.5 * std::cos(5 * th));
            return r <= k * 1.05;
        }
        case Shape::Hexagon: return std::fabs(v) <= 0.92 && std::fabs(u) + std::fabs(v) * 0.5 <= 1.0;
        case Shape::Ring: return r <= 1.0 && r >= 0.42;
        case Shape::Leaf: {
            const double a = (u + v) / std::sqrt(2.0), b = (v - u) / std::sqrt(2.0);
            return (a - 0.62) * (a - 0.62) + b * b <= 1.45 && (a + 0.62) * (a + 0.62) + b * b <= 1.45;
        }
        case Shape::Hourglass: return std::fabs(u) <= std::fabs(v) * 0.95 + 0.3;
        case Shape::Bow: {   // two rounded wings pinched by a knot
            const double a = (std::fabs(u) - 0.58) / 0.46, b = v / 0.86;
            return a * a + b * b <= 1.0 || r <= 0.3;
        }
        case Shape::Butterfly: {   // four slanted wings with a notch between them, a slim body and a head
            const double x = std::fabs(u);
            auto wing = [&](double cx, double cy, double rx, double ry, double ang) {
                const double du = x - cx, dv = v - cy, c = std::cos(ang), sn = std::sin(ang);
                const double px = du * c + dv * sn, py = -du * sn + dv * c;
                return (px / rx) * (px / rx) + (py / ry) * (py / ry) <= 1.0;
            };
            return wing(0.56, -0.42, 0.50, 0.40, 0.55) ||     // upper wing, tip swept up and out
                   wing(0.50, 0.52, 0.32, 0.30, -0.35) ||     // lower wing, smaller, a slit beside the body
                   (x <= 0.27 && v >= -0.08 && v <= 0.42) || // where the wings meet the body
                   (x <= 0.10 && v >= -0.62 && v <= 0.92) || // body
                   (x * x + (v + 0.68) * (v + 0.68) <= 0.14 * 0.14);   // head
        }
        case Shape::House: {
            if (v < -0.1) return std::fabs(u) <= std::min(1.0, (v + 0.98) * 1.2);
            return std::fabs(u) <= 0.8 && v <= 0.96;
        }
        case Shape::Moon: return r <= 1.0 && (u - 0.5) * (u - 0.5) + (v + 0.05) * (v + 0.05) > 0.76 * 0.76;
        case Shape::Flower: {   // five round petals around a centre
            const double th = std::atan2(v, u) - kPi / 2;
            return r <= 0.34 + 0.7 * std::fabs(std::cos(2.5 * th));
        }
        case Shape::Drop: {   // a round belly under a point
            if (v > 0.18) return u * u + (v - 0.28) * (v - 0.28) <= 0.72 * 0.72;
            return std::fabs(u) <= (v + 0.98) * 0.62;
        }
        case Shape::Cross: return std::fabs(std::fabs(u) - std::fabs(v)) <= 0.34;
        case Shape::Triangle: return std::fabs(u) <= (v + 0.96) * 0.54 && v <= 0.96;
        case Shape::Mushroom: {
            if (v < 0.1) return (u * u) / (1.0 * 1.0) + ((v - 0.1) * (v - 0.1)) / (0.98 * 0.98) <= 1.0;
            return std::fabs(u) <= 0.34 && v <= 0.96;
        }
        default: return true;
    }
}

// Share of the bounding box a shape covers, used to size the board so every shape holds
// about as many arrows as a plain rectangle of the same level would.
double shapeFraction(Shape s) {
    static double cache[static_cast<int>(Shape::Count)] = {};
    double& f = cache[static_cast<int>(s)];
    if (f == 0.0) {
        const int n = 64;
        int in = 0;
        for (int y = 0; y < n; ++y)
            for (int x = 0; x < n; ++x)
                if (shapeContains(s, (x + 0.5) / n * 2.0 - 1.0, (y + 0.5) / n * 2.0 - 1.0)) ++in;
        f = std::max(0.2, static_cast<double>(in) / (n * n));
    }
    return f;
}

// Width over height of the bounding box: wide shapes get wide boards.
double shapeAspect(Shape s) {
    switch (s) {
        case Shape::Bow: return 1.3;
        case Shape::House: case Shape::Triangle: return 1.0;
        case Shape::Mushroom: return 0.95;
        case Shape::Drop: return 0.85;
        case Shape::Butterfly: return 1.15;
        case Shape::Moon: return 0.9;
        case Shape::Cross: case Shape::Circle: case Shape::Ring: case Shape::Flower: return 0.95;
        default: return 1.0 / 1.3;
    }
}

int manhattan(int ax, int ay, int bx, int by) { return std::abs(ax - bx) + std::abs(ay - by); }

} // namespace

// ---------------------------------------------------------------------------
// Configuration
// ---------------------------------------------------------------------------

LevelConfig Board::config(int level, uint32_t salt, int forceShape) {
    if (level < 1) level = 1;
    LevelConfig c;
    Rng r(mix(static_cast<uint32_t>(level), salt, 17u));

    // Sizes are calibrated against the real game: level 9 (Normal) has 54 arrows and the
    // October 21 daily (Normal) has 177. Harder tiers scale the arrow count up.
    // Mode boards carry kVariantFlag: level sizes, their own layout. Any other salt is a daily.
    const bool variant = (salt & kVariantFlag) != 0;
    const bool daily = salt != 0 && !variant;
    c.difficulty = daily ? 0 : difficultyOf(level);
    static const int kEarly[] = {0, 3, 5, 9, 15, 22, 30, 38, 46};
    double target;
    if (daily) target = 200 + (salt % 31);
    else if (level <= 8) target = kEarly[level];
    else {
        static const double kTierMult[] = {1.0, 1.3, 1.6, 2.0};
        target = std::min(170.0, 50.0 + (level - 9) * 3.0) * kTierMult[c.difficulty];
    }
    const bool shaped = level >= 6 && r.next() < 0.6;   // dailies too: every day looks different
    c.shape = shaped ? static_cast<Shape>(1 + r.below(static_cast<int>(Shape::Count) - 1)) : Shape::Rect;
    if (forceShape >= 0 && forceShape < static_cast<int>(Shape::Count)) c.shape = static_cast<Shape>(forceShape);
    c.maxLen = level <= 3 ? 3 + level / 2 : std::min(22, 8 + level / 2);
    c.minLen = 3;
    c.straight = 0.62;
    // Cells budgeted per target arrow. The dense generator's arrows average ~7.5 cells, so
    // boards keep their size and hold fewer, longer arrows than the target.
    const double avgLen = level <= 3 ? 3.0 : 5.2;
    double cells = target * avgLen / (c.shape == Shape::Rect ? 1.0 : shapeFraction(c.shape));
    if (level <= 3 && !daily) cells /= (level == 1 ? 0.4 : 0.6);  // sparse tutorial boards
    const double aspect = shapeAspect(c.shape);
    c.cols = std::max(4, static_cast<int>(std::lround(std::sqrt(cells * aspect))));
    c.rows = std::max(5, static_cast<int>(std::lround(cells / c.cols)));
    c.fill = level == 1 ? 0.4 : level <= 3 ? 0.6 : 0.97;
    const int area = c.cols * c.rows;
    c.variants = level < 4 ? 1 : area < 400 ? 8 : area < 900 ? 5 : 3;

    for (int m = 0; m < kMechanicCount && kMechanicsEnabled; ++m) {
        if (level == kUnlockLevel[m] && salt == 0) {
            c.introduces = m;
            c.count[m] = 1;
        } else if (level > kUnlockLevel[m] && r.next() < 0.3) {
            c.count[m] = 1 + r.below(kMaxCount[m]);
        }
    }
    return c;
}

// ---------------------------------------------------------------------------
// Construction
// ---------------------------------------------------------------------------

Board::Board(int level, uint32_t salt, int forceShape) {
    if (level < 1) level = 1;
    const LevelConfig cfg = config(level, salt, forceShape);

    // Several deterministic layouts; the one with the longest dependency chain and the
    // fewest arrows free at the start wins.
    Board best;
    long bestScore = -1000000000L;
    for (int v = 0; v < cfg.variants; ++v) {
        Board cand;
        cand.level_ = level;
        if (level < 4) cand.buildBase(cfg, mix(static_cast<uint32_t>(level), salt, 1000u + v));
        else {
            cand.buildDense(cfg, mix(static_cast<uint32_t>(level), salt, 1000u + v));
            cand.harden(mix(static_cast<uint32_t>(level), salt, 3000u + v));
        }
        int freeAtStart = 0;
        const int d = cand.fastDepth(&freeAtStart);
        if (d < 0) continue;
        const long score = (static_cast<long>(d) - 3L * freeAtStart) * 1000 + cand.arrowCount();
        if (score > bestScore) {
            bestScore = score;
            best = std::move(cand);
        }
    }
    *this = std::move(best);
    introduces_ = cfg.introduces;
    difficulty_ = cfg.difficulty;

    bool wantsMechanics = false;
    for (int m = 0; m < kMechanicCount; ++m) wantsMechanics |= cfg.count[m] > 0;
    if (!wantsMechanics) return;

    const Board base = *this;
    for (int attempt = 0; attempt < 40; ++attempt) {
        Board cand = base;
        if (cand.addMechanics(cfg, mix(static_cast<uint32_t>(level), salt, 5000u + attempt)) && cand.solvable()) {
            *this = std::move(cand);
            return;
        }
    }
    // Fall back to only the mechanic this level introduces, so its tutorial still makes sense.
    if (cfg.introduces >= 0) {
        LevelConfig only = cfg;
        for (int m = 0; m < kMechanicCount; ++m) only.count[m] = (m == cfg.introduces) ? 1 : 0;
        for (int attempt = 0; attempt < 80; ++attempt) {
            Board cand = base;
            if (cand.addMechanics(only, mix(static_cast<uint32_t>(level), salt, 9000u + attempt)) && cand.solvable()) {
                *this = std::move(cand);
                return;
            }
        }
    }
    *this = base;
    introduces_ = -1;
}

void Board::makeMask(const LevelConfig& cfg) {
    cols_ = cfg.cols;
    rows_ = cfg.rows;
    const int total = cols_ * rows_;
    mask_.assign(total, 0);
    for (int y = 0; y < rows_; ++y) {
        for (int x = 0; x < cols_; ++x) {
            const double u = (x + 0.5) / cols_ * 2.0 - 1.0;
            const double v = (y + 0.5) / rows_ * 2.0 - 1.0;
            mask_[idx(x, y)] = shapeContains(cfg.shape, u, v) ? 1 : 0;
        }
    }
    // keep one piece: a shape's thin tips must never leave stray cells that no arrow can fill
    {
        std::vector<int> comp(total, -1), stack;
        int best = -1, bestSize = 0, n = 0;
        for (int start = 0; start < total; ++start) {
            if (!mask_[start] || comp[start] >= 0) continue;
            int size = 0;
            stack.assign(1, start);
            comp[start] = n;
            while (!stack.empty()) {
                const int cur = stack.back(); stack.pop_back();
                ++size;
                const int cx = cur % cols_, cy = cur / cols_;
                const int nx[4] = {cx + 1, cx - 1, cx, cx}, ny[4] = {cy, cy, cy + 1, cy - 1};
                for (int k = 0; k < 4; ++k) {
                    if (nx[k] < 0 || ny[k] < 0 || nx[k] >= cols_ || ny[k] >= rows_) continue;
                    const int ni = idx(nx[k], ny[k]);
                    if (mask_[ni] && comp[ni] < 0) { comp[ni] = n; stack.push_back(ni); }
                }
            }
            if (size > bestSize) { bestSize = size; best = n; }
            ++n;
        }
        for (int i = 0; i < total; ++i) if (mask_[i] && comp[i] != best) mask_[i] = 0;
    }
    occ_.assign(total, -1);
    arrows_.clear();
    portals_.clear();
    stops_.clear();
    redirects_.clear();
    zones_.clear();
}

namespace {
void reverseArrow(Arrow& a) {
    std::vector<Pt> rev(a.pts.rbegin(), a.pts.rend());
    for (Pt& p : rev) p.jump = false;
    a.pts = std::move(rev);
    std::swap(a.dx, a.tdx);
    std::swap(a.dy, a.tdy);
}

void refreshDirections(Arrow& a) {
    const size_t m = a.pts.size();
    a.dx = a.pts[m - 1].x - a.pts[m - 2].x;
    a.dy = a.pts[m - 1].y - a.pts[m - 2].y;
    a.tdx = a.pts[0].x - a.pts[1].x;
    a.tdy = a.pts[0].y - a.pts[1].y;
}
} // namespace

namespace {
using Path = std::vector<Pt>;

struct Grid {
    int cols, rows;
    const std::vector<uint8_t>& mask;
    bool in(int x, int y) const { return x >= 0 && y >= 0 && x < cols && y < rows && mask[y * cols + x]; }
    int id(int x, int y) const { return y * cols + x; }
};

// Covers the shape with long winding snakes. Each snake starts in the most cramped free
// cell (corners and pockets first) and prefers the neighbour with the fewest free
// neighbours, so cells are rarely left stranded between finished snakes.
std::vector<Path> tileSnakes(const Grid& g, int minLen, int maxLen, double straight, Rng& rng,
                             std::vector<int>& owner) {
    const int total = g.cols * g.rows;
    owner.assign(total, -1);
    std::vector<int8_t> deg(total, 0);
    auto isFree = [&](int x, int y) { return g.in(x, y) && owner[g.id(x, y)] == -1; };
    for (int y = 0; y < g.rows; ++y)
        for (int x = 0; x < g.cols; ++x)
            if (g.in(x, y))
                for (const auto& d : kDirs) deg[g.id(x, y)] += isFree(x + d[0], y + d[1]) ? 1 : 0;
    auto take = [&](int x, int y, int pid) {
        owner[g.id(x, y)] = pid;
        for (const auto& d : kDirs)
            if (g.in(x + d[0], y + d[1])) --deg[g.id(x + d[0], y + d[1])];
    };

    std::vector<Path> paths;
    for (;;) {
        int start = -1, bestDeg = 99, ties = 0;
        for (int c = 0; c < total; ++c) {
            if (!g.mask[c] || owner[c] != -1) continue;
            if (deg[c] < bestDeg) { bestDeg = deg[c]; start = c; ties = 1; }
            else if (deg[c] == bestDeg && rng.below(++ties) == 0) start = c;
        }
        if (start < 0) break;

        const int pid = static_cast<int>(paths.size());
        const int want = minLen + rng.below(maxLen - minLen + 1);
        Path pts{{start % g.cols, start / g.cols, false}};
        take(pts[0].x, pts[0].y, pid);
        int ldx = 0, ldy = 0;
        while (static_cast<int>(pts.size()) < want) {
            const Pt cur = pts.back();
            int opts[4], n = 0;
            for (int d = 0; d < 4; ++d)
                if (isFree(cur.x + kDirs[d][0], cur.y + kDirs[d][1])) opts[n++] = d;
            if (n == 0) break;
            int pick = -1;
            if ((ldx || ldy) && rng.next() < straight)
                for (int k = 0; k < n; ++k)
                    if (kDirs[opts[k]][0] == ldx && kDirs[opts[k]][1] == ldy) pick = opts[k];
            if (pick < 0) {
                if (rng.next() < 0.6) {
                    int bd = 99, t = 0;
                    for (int k = 0; k < n; ++k) {
                        const int nd = deg[g.id(cur.x + kDirs[opts[k]][0], cur.y + kDirs[opts[k]][1])];
                        if (nd < bd) { bd = nd; pick = opts[k]; t = 1; }
                        else if (nd == bd && rng.below(++t) == 0) pick = opts[k];
                    }
                } else {
                    pick = opts[rng.below(n)];
                }
            }
            ldx = kDirs[pick][0];
            ldy = kDirs[pick][1];
            pts.push_back({cur.x + ldx, cur.y + ldy, false});
            take(pts.back().x, pts.back().y, pid);
        }
        paths.push_back(std::move(pts));
    }
    return paths;
}

// Absorbs stub snakes into their neighbours so the board has no tiny arrows: a stub is
// joined end-to-end onto a neighbouring snake when their ends touch, otherwise it cuts a
// long neighbour in two at the touching cell and takes one half.
void absorbStubs(const Grid& g, std::vector<Path>& paths, std::vector<int>& owner, int minLen, int maxLen) {
    auto relabel = [&](int pid) {
        for (const Pt& p : paths[pid]) owner[g.id(p.x, p.y)] = pid;
    };
    for (int pass = 0; pass < 4; ++pass) {
        bool changed = false;
        std::vector<int> stubs;
        for (int p = 0; p < static_cast<int>(paths.size()); ++p)
            if (!paths[p].empty() && static_cast<int>(paths[p].size()) < minLen) stubs.push_back(p);
        std::sort(stubs.begin(), stubs.end(),
                  [&](int a, int b) { return paths[a].size() < paths[b].size(); });
        for (int p : stubs) {
            if (paths[p].empty() || static_cast<int>(paths[p].size()) >= minLen) continue;
            const int plen = static_cast<int>(paths[p].size());
            int bestScore = -1, bq = -1, bi = 0, bend = 0, bmode = 0;
            for (int end = 0; end < 2; ++end) {
                const Pt e = end == 0 ? paths[p].front() : paths[p].back();
                for (const auto& d : kDirs) {
                    const int nx = e.x + d[0], ny = e.y + d[1];
                    if (!g.in(nx, ny)) continue;
                    const int q = owner[g.id(nx, ny)];
                    if (q < 0 || q == p) continue;
                    const Path& qp = paths[q];
                    const int qlen = static_cast<int>(qp.size());
                    int qi = 0;
                    while (qp[qi].x != nx || qp[qi].y != ny) ++qi;
                    if (qi == 0 || qi == qlen - 1) {
                        if (plen + qlen > maxLen + 2) continue;
                        const int score = 1000 - (plen + qlen);  // join the shortest neighbour
                        if (score > bestScore) { bestScore = score; bq = q; bi = qi; bend = end; bmode = 0; }
                    } else {
                        const int need = plen == 1 ? 2 : 3;
                        const int keepFront = qi + 1;    // stub goes on the front half
                        const int keepBack = qlen - qi;  // stub goes on the back half
                        if (qlen - keepFront >= need) {
                            const int score = std::min(qlen - keepFront, keepFront + plen);
                            if (score > bestScore) { bestScore = score; bq = q; bi = qi; bend = end; bmode = 1; }
                        }
                        if (qlen - keepBack >= need) {
                            const int score = std::min(qlen - keepBack, keepBack + plen);
                            if (score > bestScore) { bestScore = score; bq = q; bi = qi; bend = end; bmode = 2; }
                        }
                    }
                }
            }
            if (bq < 0) continue;
            Path stub = paths[p];
            if (bend == 0) std::reverse(stub.begin(), stub.end());  // touching cell is now last
            Path q = paths[bq];
            if (bmode == 0) {
                if (bi != 0) std::reverse(q.begin(), q.end());  // touching cell is now first
                stub.insert(stub.end(), q.begin(), q.end());
                paths[bq].clear();
                paths[p] = std::move(stub);
                relabel(p);
            } else if (bmode == 1) {
                // q[0..bi] + reversed stub; the remainder of q becomes its own snake
                Path front(q.begin(), q.begin() + bi + 1);
                Path rest(q.begin() + bi + 1, q.end());
                front.insert(front.end(), stub.rbegin(), stub.rend());
                paths[bq] = std::move(front);
                paths[p] = std::move(rest);
                relabel(bq);
                relabel(p);
            } else {
                // stub + q[bi..]; the head of q becomes its own snake
                Path head(q.begin(), q.begin() + bi);
                Path back(q.begin() + bi, q.end());
                stub.insert(stub.end(), back.begin(), back.end());
                paths[bq] = std::move(head);
                paths[p] = std::move(stub);
                relabel(bq);
                relabel(p);
            }
            changed = true;
        }
        if (!changed) break;
    }
}
} // namespace

// Tiles the whole shape with long winding arrows, then decides where each one points by
// "peeling" the board: arrows are removed one at a time, each through whichever of its two
// ends has a clear exit ray at that moment. The peel order is a valid solution, so the
// board is solvable. Ends that would be free from the very start are only used when
// nothing else can move, so few arrows can leave at the beginning, and ends whose ray
// crosses many other arrows are preferred so the dependency chains run deep. When every
// remaining arrow is stuck behind another, one is cut in two where the cut exposes a clear
// end; the board stays fully covered and nothing short is added.
void Board::buildDense(const LevelConfig& cfg, uint32_t seed) {
    makeMask(cfg);
    const int total = cols_ * rows_;
    Rng rng(seed);

    // 1. Tile with snakes and absorb the stubs.
    const Grid grid{cols_, rows_, mask_};
    std::vector<int> owner;
    std::vector<Path> paths = tileSnakes(grid, cfg.minLen, cfg.maxLen, cfg.straight, rng, owner);
    absorbStubs(grid, paths, owner, cfg.minLen, cfg.maxLen);

    // 2. Peel. `cur` holds the piece on each cell, `full` the cells covered at the start.
    std::vector<Path> ps;
    std::vector<int> cur(total, -1), full(total, -1);
    for (Path& p : paths) {
        if (p.size() < 2) continue;
        for (const Pt& c : p) cur[idx(c.x, c.y)] = full[idx(c.x, c.y)] = static_cast<int>(ps.size());
        ps.push_back(std::move(p));
    }
    // Exit ray of one end: head cell and heading.
    auto headOf = [&](const Path& p, int e, int& hx, int& hy, int& dx, int& dy) {
        const Pt& h = e == 1 ? p.back() : p.front();
        const Pt& q = e == 1 ? p[p.size() - 2] : p[1];
        hx = h.x; hy = h.y; dx = h.x - q.x; dy = h.y - q.y;
    };
    auto rayCount = [&](const Path& p, int e, const std::vector<int>& g, bool stopAtFirst) {
        int hx, hy, dx, dy, n = 0;
        headOf(p, e, hx, hy, dx, dy);
        for (int x = hx + dx, y = hy + dy; inBounds(x, y); x += dx, y += dy)
            if (g[idx(x, y)] != -1 && ++n && stopAtFirst) break;
        return n;
    };
    std::vector<std::array<int, 2>> startBlocked;  // arrows on each ray in the full board
    std::vector<char> gone(ps.size(), 0), deleted(ps.size(), 0);
    std::vector<int> useEnd(ps.size(), 1);
    std::vector<double> removedAt(ps.size(), 0.0);
    auto measure = [&](int id) {
        startBlocked[id] = {rayCount(ps[id], 0, full, false), rayCount(ps[id], 1, full, false)};
    };
    startBlocked.resize(ps.size());
    for (int id = 0; id < static_cast<int>(ps.size()); ++id) measure(id);
    auto drop = [&](int id) {
        gone[id] = 1;
        for (const Pt& c : ps[id]) cur[idx(c.x, c.y)] = -1;
    };

    int alive = static_cast<int>(ps.size());
    for (int step = 1; alive > 0; ++step) {
        int bestId = -1, bestEnd = 1;
        double bestScore = -1.0;
        for (int id = 0; id < static_cast<int>(ps.size()); ++id) {
            if (gone[id]) continue;
            for (int e = 0; e < 2; ++e) {
                if (rayCount(ps[id], e, cur, true) != 0) continue;
                const int sb = startBlocked[id][e];
                const double score = (sb > 0 ? 1e6 : 0.0) + std::min(sb, 12) * 10.0 + rng.next() * 8.0;
                if (score > bestScore) { bestScore = score; bestId = id; bestEnd = e; }
            }
        }
        // Only moves that would already be open at the start of the level left: rather than
        // add another free arrow, try a cut that exposes an end the player has to work for.
        const bool onlyFree = bestId >= 0 && startBlocked[bestId][bestEnd] == 0;
        if (bestId >= 0 && !onlyFree) {
            useEnd[bestId] = bestEnd;
            removedAt[bestId] = step;
            drop(bestId);
            --alive;
            continue;
        }

        // Cut some arrow where one half gets a clear exit.
        const int minPiece = onlyFree ? 3 : 2;
        int cutId = -1, cutAt = 0, cutSide = 0;
        double cutScore = -1.0;
        for (int id = 0; id < static_cast<int>(ps.size()); ++id) {
            if (gone[id]) continue;
            const Path& p = ps[id];
            const int len = static_cast<int>(p.size());
            for (int k = minPiece - 1; k + minPiece < len; ++k) {  // halves p[0..k] and p[k+1..len-1]
                const double balance = std::min(k + 1, len - k - 1) * 100.0 + rng.next();
                if (balance <= cutScore) continue;
                for (int side = 0; side < 2; ++side) {
                    // side 0: left half leaves through p[k]; side 1: right half through p[k+1]
                    const Pt& h = side == 0 ? p[k] : p[k + 1];
                    const Pt& q = side == 0 ? p[k - 1] : p[k + 2];
                    const int dx = h.x - q.x, dy = h.y - q.y;
                    bool clear = true, coveredAtStart = false;
                    for (int x = h.x + dx, y = h.y + dy; inBounds(x, y) && clear; x += dx, y += dy) {
                        clear = cur[idx(x, y)] == -1;
                        coveredAtStart |= full[idx(x, y)] != -1;
                    }
                    if (clear && (coveredAtStart || !onlyFree)) {
                        cutScore = balance; cutId = id; cutAt = k; cutSide = side;
                        break;
                    }
                }
            }
        }
        if (cutId < 0 && onlyFree) {
            useEnd[bestId] = bestEnd;
            removedAt[bestId] = step;
            drop(bestId);
            --alive;
            continue;
        }
        if (cutId >= 0) {
            Path left(ps[cutId].begin(), ps[cutId].begin() + cutAt + 1);
            Path right(ps[cutId].begin() + cutAt + 1, ps[cutId].end());
            const int rightId = static_cast<int>(ps.size());
            ps[cutId] = std::move(left);
            ps.push_back(std::move(right));
            gone.push_back(0);
            deleted.push_back(0);
            useEnd.push_back(1);
            removedAt.push_back(0.0);
            startBlocked.push_back({0, 0});
            for (const Pt& c : ps[rightId]) cur[idx(c.x, c.y)] = rightId;
            // The halves are new arrows: measure them against the full board.
            measure(cutId);
            measure(rightId);
            ++alive;
            const int leaver = cutSide == 0 ? cutId : rightId;
            useEnd[leaver] = cutSide == 0 ? 1 : 0;
            removedAt[leaver] = step;
            drop(leaver);
            --alive;
            continue;
        }

        // Nothing can be cut either (only very short arrows left): drop the smallest one.
        int victim = -1;
        for (int id = 0; id < static_cast<int>(ps.size()); ++id)
            if (!gone[id] && (victim < 0 || ps[id].size() < ps[victim].size())) victim = id;
        deleted[victim] = 1;
        drop(victim);
        --alive;
    }

    std::vector<double> times;
    for (int id = 0; id < static_cast<int>(ps.size()); ++id) {
        if (deleted[id]) continue;
        Arrow a;
        a.pts = std::move(ps[id]);
        if (useEnd[id] == 0) std::reverse(a.pts.begin(), a.pts.end());
        refreshDirections(a);
        arrows_.push_back(std::move(a));
        times.push_back(removedAt[id]);
    }
    rebuildIndex();
    fillHoles(cfg, rng, times);
}

void Board::harden(uint32_t seed) {
    Rng rng(seed);
    // Harder = longer chain of dependencies (depth) and fewer arrows free at the start.
    int bestFree = 0;
    int bestDepth = fastDepth(&bestFree);
    if (bestDepth < 0) return;
    std::vector<int> ids(arrowCount());
    std::iota(ids.begin(), ids.end(), 0);
    for (int pass = 0; pass < 2; ++pass) {
        for (int i = static_cast<int>(ids.size()) - 1; i > 0; --i) std::swap(ids[i], ids[rng.below(i + 1)]);
        bool changed = false;
        for (int id : ids) {
            reverseArrow(arrows_[id]);
            int f = 0;
            const int d = fastDepth(&f);
            if (d >= 0 && (d > bestDepth || (d == bestDepth && f < bestFree))) {
                bestDepth = d; bestFree = f; changed = true;
            } else {
                reverseArrow(arrows_[id]);
            }
        }
        if (!changed) break;
    }
}

// Puts new arrows into the cells the peel had to empty. Each removal of the known
// solution happens at a time; a new arrow X gets a time t such that every arrow on X's
// exit ray is gone before t (so X can leave) and no exit ray that crosses X's cells is
// used before t (so X never gets in anybody's way). Inserting X at t keeps the board
// solvable, and the gaps mostly disappear.
void Board::fillHoles(const LevelConfig& cfg, Rng& rng, std::vector<double>& times) {
    const int total = cols_ * rows_;
    const double kNever = 1e18;
    std::vector<double> cross(total, kNever);
    auto markRay = [&](const Arrow& a, double t) {
        int x = a.pts.back().x + a.dx, y = a.pts.back().y + a.dy;
        while (inBounds(x, y)) {
            double& c = cross[idx(x, y)];
            if (t < c) c = t;
            x += a.dx;
            y += a.dy;
        }
    };
    for (int i = 0; i < arrowCount(); ++i) markRay(arrows_[i], times[i]);

    // latest removal time among arrows on a ray, or -1 if it never reaches the edge cleanly
    auto rayDeadline = [&](const std::vector<Pt>& pts, const std::vector<int>& own) {
        const Pt& h = pts.back();
        const Pt& p = pts[pts.size() - 2];
        const int dx = h.x - p.x, dy = h.y - p.y;
        int x = h.x + dx, y = h.y + dy;
        double latest = 0.0;
        while (inBounds(x, y)) {
            const int c = idx(x, y);
            if (std::find(own.begin(), own.end(), c) != own.end()) return -1.0;
            const int o = occ_[c];
            if (o >= 0 && times[o] > latest) latest = times[o];
            x += dx;
            y += dy;
        }
        return latest;
    };

    std::vector<int> holes;
    for (int i = 0; i < total; ++i)
        if (mask_[i] && occ_[i] == -1) holes.push_back(i);
    for (int i = static_cast<int>(holes.size()) - 1; i > 0; --i) std::swap(holes[i], holes[rng.below(i + 1)]);

    for (int start : holes) {
        if (occ_[start] != -1) continue;
        std::vector<Pt> walk{{start % cols_, start / cols_, false}};
        std::vector<int> own{start};
        int ldx = 0, ldy = 0;
        const int want = 2 + rng.below(std::max(1, cfg.maxLen - 1));
        while (static_cast<int>(walk.size()) < want) {
            const Pt cur = walk.back();
            int opts[4];
            int k = 0;
            for (int d = 0; d < 4; ++d) {
                const int nx = cur.x + kDirs[d][0], ny = cur.y + kDirs[d][1];
                if (inMask(nx, ny) && occ_[idx(nx, ny)] == -1 &&
                    std::find(own.begin(), own.end(), idx(nx, ny)) == own.end()) opts[k++] = d;
            }
            if (k == 0) break;
            int pick = opts[rng.below(k)];
            for (int q = 0; q < k; ++q)
                if (kDirs[opts[q]][0] == ldx && kDirs[opts[q]][1] == ldy && rng.next() < cfg.straight) pick = opts[q];
            ldx = kDirs[pick][0];
            ldy = kDirs[pick][1];
            walk.push_back({cur.x + ldx, cur.y + ldy, false});
            own.push_back(idx(walk.back().x, walk.back().y));
        }
        // Try the longest prefix that fits, in either orientation.
        bool placed = false;
        for (size_t len = walk.size(); len >= 2 && !placed; --len) {
            for (int orient = 0; orient < 2 && !placed; ++orient) {
                std::vector<Pt> pts(walk.begin(), walk.begin() + static_cast<long>(len));
                if (orient == 1) std::reverse(pts.begin(), pts.end());
                std::vector<int> cells;
                double mustLeaveBy = kNever;
                for (const Pt& p : pts) {
                    cells.push_back(idx(p.x, p.y));
                    mustLeaveBy = std::min(mustLeaveBy, cross[idx(p.x, p.y)]);
                }
                const double after = rayDeadline(pts, cells);
                if (after < 0.0 || after >= mustLeaveBy) continue;
                const double t = mustLeaveBy >= kNever ? after + 1.0 : (after + mustLeaveBy) / 2.0;
                Arrow a;
                a.pts = pts;
                refreshDirections(a);
                const int id = arrowCount();
                for (int c : cells) occ_[c] = static_cast<int16_t>(id);
                markRay(a, t);
                arrows_.push_back(std::move(a));
                times.push_back(t);
                placed = true;
            }
        }
    }
    rebuildIndex();
}

// Arrows are placed one at a time; a new arrow's exit ray may not touch any arrow placed
// before it, so removing them in reverse placement order always works.
void Board::buildBase(const LevelConfig& cfg, uint32_t seed) {
    makeMask(cfg);
    const int total = cols_ * rows_;

    Rng rng(seed);
    std::vector<int> order;
    for (int i = 0; i < total; ++i)
        if (mask_[i]) order.push_back(i);
    for (int i = static_cast<int>(order.size()) - 1; i > 0; --i) std::swap(order[i], order[rng.below(i + 1)]);

    const int target = static_cast<int>(std::floor(order.size() * cfg.fill));
    int filled = 0;
    std::vector<int> own(total, 0);
    int stamp = 0;

    auto rayClear = [&](const std::vector<Pt>& pts) {
        const Pt& h = pts[pts.size() - 1];
        const Pt& p = pts[pts.size() - 2];
        const int dx = h.x - p.x, dy = h.y - p.y;
        int x = h.x + dx, y = h.y + dy;
        while (inBounds(x, y)) {
            if (occ_[idx(x, y)] != -1 || own[idx(x, y)] == stamp) return false;
            x += dx;
            y += dy;
        }
        return true;
    };

    const int passes[4] = {cfg.maxLen, (cfg.maxLen + 1) / 2, 3, 2};
    for (int maxLen : passes) {
        for (int start : order) {
            if (filled >= target) break;
            if (occ_[start] != -1) continue;
            for (int attempt = 0; attempt < 8; ++attempt) {
                const int want = 2 + rng.below(maxLen - 1);
                std::vector<Pt> pts;
                pts.push_back({start % cols_, start / cols_, false});
                ++stamp;
                own[start] = stamp;
                int ldx = 0, ldy = 0;
                bool hasLast = false;
                while (static_cast<int>(pts.size()) < want) {
                    const Pt cur = pts.back();
                    int opts[4];
                    int n = 0;
                    for (int d = 0; d < 4; ++d) {
                        const int nx = cur.x + kDirs[d][0], ny = cur.y + kDirs[d][1];
                        if (inMask(nx, ny) && occ_[idx(nx, ny)] == -1 && own[idx(nx, ny)] != stamp) opts[n++] = d;
                    }
                    if (n == 0) break;
                    int pick = opts[rng.below(n)];
                    if (hasLast && rng.next() < cfg.straight) {
                        for (int k = 0; k < n; ++k)
                            if (kDirs[opts[k]][0] == ldx && kDirs[opts[k]][1] == ldy) { pick = opts[k]; break; }
                    }
                    ldx = kDirs[pick][0];
                    ldy = kDirs[pick][1];
                    hasLast = true;
                    pts.push_back({cur.x + ldx, cur.y + ldy, false});
                    own[idx(pts.back().x, pts.back().y)] = stamp;
                }
                if (pts.size() < 2) continue;
                bool ok = rayClear(pts);
                if (!ok) {
                    std::reverse(pts.begin(), pts.end());
                    ok = rayClear(pts);
                }
                if (!ok) continue;

                Arrow a;
                a.pts = std::move(pts);
                const size_t n = a.pts.size();
                a.dx = a.pts[n - 1].x - a.pts[n - 2].x;
                a.dy = a.pts[n - 1].y - a.pts[n - 2].y;
                a.tdx = a.pts[0].x - a.pts[1].x;
                a.tdy = a.pts[0].y - a.pts[1].y;
                const int id = static_cast<int>(arrows_.size());
                for (const Pt& c : a.pts) occ_[idx(c.x, c.y)] = static_cast<int16_t>(id);
                filled += static_cast<int>(n);
                arrows_.push_back(std::move(a));
                break;
            }
        }
    }
    rebuildIndex();
}

void Board::rebuildIndex() {
    const int total = cols_ * rows_;
    occ_.assign(total, -1);
    portalAt_.assign(total, -1);
    stopAt_.assign(total, -1);
    redirectAt_.assign(total, -1);
    zoneAt_.assign(total, -1);
    for (int i = 0; i < arrowCount(); ++i) {
        if (!arrows_[i].alive) continue;
        for (const Pt& p : arrows_[i].pts) occ_[idx(p.x, p.y)] = static_cast<int16_t>(i);
    }
    for (size_t i = 0; i < portals_.size(); ++i) {
        portalAt_[idx(portals_[i].ax, portals_[i].ay)] = static_cast<int16_t>(i * 2);
        portalAt_[idx(portals_[i].bx, portals_[i].by)] = static_cast<int16_t>(i * 2 + 1);
    }
    for (size_t i = 0; i < stops_.size(); ++i)
        if (stops_[i].alive) stopAt_[idx(stops_[i].x, stops_[i].y)] = static_cast<int16_t>(i);
    for (size_t i = 0; i < redirects_.size(); ++i) redirectAt_[idx(redirects_[i].x, redirects_[i].y)] = static_cast<int16_t>(i);
    for (size_t z = 0; z < zones_.size(); ++z) {
        if (!zones_[z].locked) continue;
        for (int y = zones_[z].y0; y <= zones_[z].y1; ++y)
            for (int x = zones_[z].x0; x <= zones_[z].x1; ++x) zoneAt_[idx(x, y)] = static_cast<int16_t>(z);
    }
}

bool Board::freeCell(int x, int y) const {
    if (!inMask(x, y)) return false;
    const int c = idx(x, y);
    return occ_[c] == -1 && portalAt_[c] == -1 && stopAt_[c] == -1 && redirectAt_[c] == -1 && zoneAt_[c] == -1;
}

bool Board::addMechanics(const LevelConfig& cfg, uint32_t seed) {
    Rng r(seed);

    // Dense boards have almost no empty cells. Portals, stop points and turn markers need
    // some, so remove a few short arrows first; removing arrows never breaks solvability.
    const int needCells = 2 * cfg.count[kPortal] + cfg.count[kStop] + cfg.count[kRedirect];
    if (needCells > 0) {
        int freeCount = 0;
        for (int y = 0; y < rows_; ++y)
            for (int x = 0; x < cols_; ++x) freeCount += freeCell(x, y) ? 1 : 0;
        while (freeCount < needCells * 3 && arrowCount() > 4) {
            int victim = r.below(arrowCount());
            for (int k = 0; k < 6; ++k) {  // prefer short arrows so the board stays dense
                const int c = r.below(arrowCount());
                if (arrows_[c].pts.size() < arrows_[victim].pts.size()) victim = c;
            }
            freeCount += static_cast<int>(arrows_[victim].pts.size());
            arrows_.erase(arrows_.begin() + victim);
        }
        rebuildIndex();
    }

    std::vector<int> empty;
    for (int y = 0; y < rows_; ++y)
        for (int x = 0; x < cols_; ++x)
            if (freeCell(x, y)) empty.push_back(idx(x, y));
    for (int i = static_cast<int>(empty.size()) - 1; i > 0; --i) std::swap(empty[i], empty[r.below(i + 1)]);
    size_t cursor = 0;
    auto take = [&](int nearX, int nearY, int minDist) -> int {
        for (size_t k = cursor; k < empty.size(); ++k) {
            const int c = empty[k];
            const int x = c % cols_, y = c / cols_;
            if (!freeCell(x, y)) continue;
            if (minDist > 0 && manhattan(x, y, nearX, nearY) < minDist) continue;
            std::swap(empty[k], empty[cursor]);
            ++cursor;
            return c;
        }
        return -1;
    };

    for (int i = 0; i < cfg.count[kPortal]; ++i) {
        const int a = take(0, 0, 0);
        if (a < 0) return false;
        const int b = take(a % cols_, a / cols_, std::max(4, (cols_ + rows_) / 4));
        if (b < 0) return false;
        portals_.push_back({a % cols_, a / cols_, b % cols_, b / cols_, static_cast<int>(portals_.size()) % 3});
        rebuildIndex();
    }
    for (int i = 0; i < cfg.count[kStop]; ++i) {
        const int c = take(0, 0, 0);
        if (c < 0) return false;
        stops_.push_back({c % cols_, c / cols_, true});
        rebuildIndex();
    }
    for (int i = 0; i < cfg.count[kRedirect]; ++i) {
        const int c = take(0, 0, 0);
        if (c < 0) return false;
        const int d = r.below(4);
        redirects_.push_back({c % cols_, c / cols_, kDirs[d][0], kDirs[d][1]});
        rebuildIndex();
    }
    for (int z = 0; z < cfg.count[kZone]; ++z) {
        bool placed = false;
        for (int tries = 0; tries < 60 && !placed; ++tries) {
            const int w = 3 + r.below(3), h = 3 + r.below(3);
            if (w > cols_ - 2 || h > rows_ - 2) continue;
            Zone zn{r.below(cols_ - w + 1), r.below(rows_ - h + 1), 0, 0, true};
            zn.x1 = zn.x0 + w - 1;
            zn.y1 = zn.y0 + h - 1;
            std::vector<int> inside;
            bool ok = true;
            for (int y = zn.y0; y <= zn.y1 && ok; ++y) {
                for (int x = zn.x0; x <= zn.x1 && ok; ++x) {
                    const int c = idx(x, y);
                    if (portalAt_[c] != -1 || stopAt_[c] != -1 || redirectAt_[c] != -1 || zoneAt_[c] != -1) ok = false;
                    const int o = occ_[c];
                    if (o >= 0 && std::find(inside.begin(), inside.end(), o) == inside.end()) inside.push_back(o);
                }
            }
            if (!ok || inside.size() < 2) continue;
            for (int id : inside) {
                for (const Pt& p : arrows_[id].pts)
                    if (p.x < zn.x0 || p.x > zn.x1 || p.y < zn.y0 || p.y > zn.y1) ok = false;
                if (arrows_[id].keyZone >= 0) ok = false;
            }
            if (!ok) continue;
            std::vector<int> outside;
            for (int i = 0; i < arrowCount(); ++i)
                if (arrows_[i].zone < 0 && arrows_[i].keyZone < 0 &&
                    std::find(inside.begin(), inside.end(), i) == inside.end()) outside.push_back(i);
            if (outside.empty()) continue;
            const int zi = static_cast<int>(zones_.size());
            for (int id : inside) arrows_[id].zone = zi;
            const int keys = outside.size() > 6 && r.next() < 0.4 ? 2 : 1;
            for (int k = 0; k < keys && !outside.empty(); ++k) {
                const int pick = r.below(static_cast<int>(outside.size()));
                arrows_[outside[pick]].keyZone = zi;
                outside.erase(outside.begin() + pick);
            }
            zones_.push_back(zn);
            rebuildIndex();
            placed = true;
        }
        if (!placed) return false;
    }
    auto plainArrows = [&]() {
        std::vector<int> v;
        for (int i = 0; i < arrowCount(); ++i) {
            const Arrow& a = arrows_[i];
            if (a.zone < 0 && a.keyZone < 0 && !a.twoWay && a.link < 0) v.push_back(i);
        }
        return v;
    };
    for (int i = 0; i < cfg.count[kTwoWay]; ++i) {
        auto v = plainArrows();
        if (v.empty()) return false;
        arrows_[v[r.below(static_cast<int>(v.size()))]].twoWay = true;
    }
    for (int i = 0; i < cfg.count[kLink]; ++i) {
        auto v = plainArrows();
        if (v.size() < 2) return false;
        const int a = r.below(static_cast<int>(v.size()));
        int b = r.below(static_cast<int>(v.size()) - 1);
        if (b >= a) ++b;
        arrows_[v[a]].link = v[b];
        arrows_[v[b]].link = v[a];
    }
    rebuildIndex();
    return true;
}

// ---------------------------------------------------------------------------
// Rules
// ---------------------------------------------------------------------------

bool Board::tappable(int id) const {
    if (id < 0 || id >= arrowCount()) return false;
    const Arrow& a = arrows_[id];
    return a.alive && (a.zone < 0 || !zones_[a.zone].locked);
}

Trace Board::trace(int id, bool reverse, int ignore) const {
    Trace t;
    if (!tappable(id)) {
        t.outcome = Outcome::NotTappable;
        return t;
    }
    const Arrow& a = arrows_[id];
    reverse = reverse && a.twoWay;
    const Pt& start = reverse ? a.pts.front() : a.pts.back();
    int dx = reverse ? a.tdx : a.dx, dy = reverse ? a.tdy : a.dy;
    int x = start.x, y = start.y;
    for (int guard = cols_ * rows_ * 4 + 16; guard > 0; --guard) {
        const int nx = x + dx, ny = y + dy;
        t.dx = dx;
        t.dy = dy;
        if (!inBounds(nx, ny)) {
            t.outcome = Outcome::Exit;
            return t;
        }
        const int c = idx(nx, ny);
        if (zoneAt_[c] >= 0 && zoneAt_[c] != a.zone) {
            t.outcome = Outcome::Blocked;
            t.blocker = -2;
            return t;
        }
        // Any body on the path blocks the arrow, including its own (real-game rule).
        const int o = occ_[c];
        if (o != -1 && o != ignore) {
            t.outcome = Outcome::Blocked;
            t.blocker = o;
            return t;
        }
        if (stopAt_[c] >= 0) {
            t.outcome = Outcome::Stopped;
            t.stop = stopAt_[c];
            return t;
        }
        t.track.push_back({nx, ny, false});
        if (portalAt_[c] >= 0) {
            const Portal& p = portals_[portalAt_[c] / 2];
            const bool fromA = portalAt_[c] % 2 == 0;
            const int ox = fromA ? p.bx : p.ax, oy = fromA ? p.by : p.ay;
            const int o2 = occ_[idx(ox, oy)];
            if (o2 != -1 && o2 != ignore) {
                t.outcome = Outcome::Blocked;
                t.blocker = o2;
                return t;
            }
            t.track.push_back({ox, oy, true});
            x = ox;
            y = oy;
            continue;
        }
        if (redirectAt_[c] >= 0) {
            dx = redirects_[redirectAt_[c]].dx;
            dy = redirects_[redirectAt_[c]].dy;
        }
        x = nx;
        y = ny;
    }
    t.outcome = Outcome::Loop;
    return t;
}

TapResult Board::tap(int id, bool reverse) {
    TapResult r;
    if (!tappable(id)) {
        Trace t;
        t.outcome = Outcome::NotTappable;
        r.moves.push_back({id, reverse, t});
        return r;
    }
    reverse = reverse && arrows_[id].twoWay;
    int partner = arrows_[id].link;
    if (partner >= 0 && !tappable(partner)) partner = -1;

    const Trace t1 = trace(id, reverse, partner);
    auto good = [](const Trace& t) { return t.outcome == Outcome::Exit || t.outcome == Outcome::Stopped; };
    r.moves.push_back({id, reverse, t1});
    bool ok = good(t1);
    if (partner >= 0) {
        const Trace t2 = trace(partner, false, id);
        r.moves.push_back({partner, false, t2});
        ok = ok && good(t2);
    }
    r.success = ok;
    if (!ok) return r;
    for (const MoveResult& m : r.moves) applyMove(m.arrow, m.reverse, m.trace, r.unlockedZones);
    return r;
}

void Board::applyMove(int id, bool reverse, const Trace& t, std::vector<int>& unlocked) {
    Arrow& a = arrows_[id];
    if (t.outcome == Outcome::Exit) {
        removeArrow(id, unlocked);
        return;
    }
    if (t.outcome != Outcome::Stopped) return;
    if (t.stop >= 0) stops_[t.stop].alive = false;
    std::vector<Pt> seq = a.pts;
    if (reverse) {
        // Walk tail-first: reverse the body and move the jump flags onto the right points.
        std::vector<Pt> rev(seq.rbegin(), seq.rend());
        for (size_t i = 0; i < rev.size(); ++i) rev[i].jump = (i > 0) && seq[seq.size() - i].jump;
        seq = rev;
    }
    seq.insert(seq.end(), t.track.begin(), t.track.end());
    const size_t n = a.pts.size();
    a.pts.assign(seq.end() - static_cast<long>(n), seq.end());
    a.pts.front().jump = false;
    a.dx = t.dx;
    a.dy = t.dy;
    if (!a.pts[1].jump) {
        a.tdx = a.pts[0].x - a.pts[1].x;
        a.tdy = a.pts[0].y - a.pts[1].y;
    } else {
        a.tdx = -a.dx;
        a.tdy = -a.dy;
    }
    rebuildIndex();
}

void Board::removeArrow(int id, std::vector<int>& unlocked) {
    Arrow& a = arrows_[id];
    if (!a.alive) return;
    a.alive = false;
    if (a.keyZone >= 0) {
        const int z = a.keyZone;
        bool allGone = true;
        for (const Arrow& o : arrows_)
            if (o.keyZone == z && o.alive) allGone = false;
        if (allGone && zones_[z].locked) {
            zones_[z].locked = false;
            unlocked.push_back(z);
        }
    }
    rebuildIndex();
}

std::vector<int> Board::erase(int id) {
    std::vector<int> unlocked;
    if (tappable(id)) removeArrow(id, unlocked);
    return unlocked;
}

int Board::remaining() const {
    int n = 0;
    for (const Arrow& a : arrows_) n += a.alive ? 1 : 0;
    return n;
}

// Tries exits first (they only ever free cells), then moves onto stop points.
bool Board::greedyStep(std::pair<int, bool>* first) {
    for (int pass = 0; pass < 2; ++pass) {
        for (int id = 0; id < arrowCount(); ++id) {
            if (!tappable(id)) continue;
            const Arrow& a = arrows_[id];
            const int partner = (a.link >= 0 && tappable(a.link)) ? a.link : -1;
            for (int rv = 0; rv < (a.twoWay ? 2 : 1); ++rv) {
                const Trace t1 = trace(id, rv == 1, partner);
                const Trace t2 = partner >= 0 ? trace(partner, false, id) : Trace{};
                const bool exits = t1.outcome == Outcome::Exit && (partner < 0 || t2.outcome == Outcome::Exit);
                const bool stops = (t1.outcome == Outcome::Exit || t1.outcome == Outcome::Stopped) &&
                                   (partner < 0 || t2.outcome == Outcome::Exit || t2.outcome == Outcome::Stopped);
                if (pass == 0 ? exits : stops) {
                    if (first) *first = {id, rv == 1};
                    tap(id, rv == 1);
                    return true;
                }
            }
        }
    }
    return false;
}

bool Board::solvable() const {
    Board sim = *this;
    while (sim.greedyStep(nullptr)) {}
    return sim.remaining() == 0;
}

int Board::depth() const {
    Board sim = *this;
    int rounds = 0;
    for (;;) {
        std::vector<int> free;
        for (int i = 0; i < sim.arrowCount(); ++i)
            if (sim.tappable(i) && sim.trace(i, false).outcome == Outcome::Exit) free.push_back(i);
        if (free.empty()) break;
        std::vector<int> unlocked;
        for (int id : free) sim.removeArrow(id, unlocked);
        ++rounds;
    }
    return sim.remaining() == 0 ? rounds : -1;
}

std::pair<int, bool> Board::hint() const {
    // Prefer a move after which the board is still solvable.
    std::pair<int, bool> fallback{-1, false};
    for (int pass = 0; pass < 2; ++pass) {
        for (int id = 0; id < arrowCount(); ++id) {
            if (!tappable(id)) continue;
            for (int rv = 0; rv < (arrows_[id].twoWay ? 2 : 1); ++rv) {
                Board sim = *this;
                const Trace t = sim.trace(id, rv == 1, sim.arrows_[id].link);
                const bool ok = pass == 0 ? t.outcome == Outcome::Exit
                                          : (t.outcome == Outcome::Exit || t.outcome == Outcome::Stopped);
                if (!ok) continue;
                if (!sim.tap(id, rv == 1).success) continue;
                if (fallback.first < 0) fallback = {id, rv == 1};
                if (sim.solvable()) return {id, rv == 1};
            }
        }
    }
    return fallback;
}

int Board::fastDepth(int* freeAtStart) const {
    std::vector<int16_t> occ = occ_;
    const int n = arrowCount();
    std::vector<char> gone(n, 0);
    int left = 0;
    for (int i = 0; i < n; ++i) { if (arrows_[i].alive) ++left; else gone[i] = 1; }
    std::vector<int> free;
    int rounds = 0;
    while (left > 0) {
        free.clear();
        for (int i = 0; i < n; ++i) {
            if (gone[i]) continue;
            const Arrow& a = arrows_[i];
            int x = a.pts.back().x + a.dx, y = a.pts.back().y + a.dy;
            bool clear = true;
            while (inBounds(x, y)) {
                if (occ[idx(x, y)] != -1) { clear = false; break; }
                x += a.dx; y += a.dy;
            }
            if (clear) free.push_back(i);
        }
        if (rounds == 0 && freeAtStart) *freeAtStart = static_cast<int>(free.size());
        if (free.empty()) break;
        for (int id : free) {
            gone[id] = 1;
            for (const Pt& p : arrows_[id].pts) occ[idx(p.x, p.y)] = -1;
        }
        left -= static_cast<int>(free.size());
        ++rounds;
    }
    return left == 0 ? rounds : -1;
}

bool Board::wellFormed() const {
    std::vector<char> seen(cols_ * rows_, 0);
    for (const Arrow& a : arrows_) {
        if (!a.alive) continue;
        if (a.pts.size() < 2) return false;
        for (size_t i = 0; i < a.pts.size(); ++i) {
            const Pt& p = a.pts[i];
            if (!inBounds(p.x, p.y)) return false;
            if (seen[idx(p.x, p.y)]) return false;
            seen[idx(p.x, p.y)] = 1;
            if (i > 0 && !p.jump && manhattan(p.x, p.y, a.pts[i - 1].x, a.pts[i - 1].y) != 1) return false;
        }
        if (a.link >= 0 && arrows_[a.link].link != static_cast<int>(&a - &arrows_[0])) return false;
    }
    return true;
}

} // namespace puzzle
