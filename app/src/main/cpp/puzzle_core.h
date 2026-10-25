// Arrow Puzzle core: shaped boards, mechanics, tap resolution, solver and hints.
// Pure C++17 with no Android dependencies so it can be unit tested on any machine.
#pragma once

#include <cstdint>
#include <utility>
#include <vector>

namespace puzzle {

class Rng;  // deterministic PRNG, defined in puzzle_core.cpp

enum class Shape : int { Rect = 0, Heart, Circle, Diamond, Plus, Star, Hexagon, Ring, Leaf, Hourglass,
                         Bow, Butterfly, House, Moon, Flower, Drop, Cross, Triangle, Mushroom, Count };

// Salt bit for mode boards (Duel, Time rush): sized like the level, with a layout of
// their own picked by the lower bits.
constexpr uint32_t kVariantFlag = 0x40000000u;

// Mechanics, in the order the game introduces them.
enum Mechanic : int { kPortal = 0, kStop, kRedirect, kZone, kTwoWay, kLink, kMechanicCount };

struct LevelConfig {
    int cols = 0;
    int rows = 0;
    Shape shape = Shape::Rect;
    int minLen = 2;  // shortest arrow the dense generator aims for
    int maxLen = 3;
    double fill = 0.5;
    double straight = 0.5;
    int variants = 1;
    int count[kMechanicCount] = {};  // how many of each mechanic to place
    int introduces = -1;             // mechanic first seen on this level, or -1
    int difficulty = 0;              // 0 Normal, 1 Hard, 2 Expert, 3 Master
};

struct Pt {
    int x = 0;
    int y = 0;
    bool jump = false;  // reached by teleport: no segment from the previous point
};

struct Arrow {
    std::vector<Pt> pts;  // tail -> head
    int dx = 0, dy = 0;   // heading at the head
    int tdx = 0, tdy = 0; // heading out of the tail (used when a two-way arrow goes backwards)
    bool twoWay = false;
    int link = -1;        // magnetic partner
    int keyZone = -1;     // zone this arrow unlocks when it leaves
    int zone = -1;        // zone this arrow sits inside
    bool alive = true;
};

struct Portal { int ax, ay, bx, by, color; };
struct StopPoint { int x, y; bool alive = true; };
struct Redirect { int x, y, dx, dy; };
struct Zone { int x0, y0, x1, y1; bool locked = true; };

enum class Outcome : int { Exit = 0, Blocked = 1, Stopped = 2, NotTappable = 3, Loop = 4 };

struct Trace {
    Outcome outcome = Outcome::Exit;
    int dx = 0, dy = 0;     // direction when the trace ended
    int blocker = -1;       // arrow id, -2 for a locked zone
    int stop = -1;          // stop point index for Outcome::Stopped
    std::vector<Pt> track;  // cells entered beyond the head, in order
};

struct MoveResult {
    int arrow;
    bool reverse;
    Trace trace;
};

struct TapResult {
    bool success = false;
    std::vector<MoveResult> moves;  // one per arrow that moved (two for magnetic pairs)
    std::vector<int> unlockedZones;
};

class Board {
public:
    // `salt` varies the layout for the same level number (daily challenges).
    // forceShape >= 0 picks that Shape (tests); -1 lets the level decide.
    explicit Board(int level, uint32_t salt = 0, int forceShape = -1);

    static LevelConfig config(int level, uint32_t salt = 0, int forceShape = -1);

    int cols() const { return cols_; }
    int rows() const { return rows_; }
    int level() const { return level_; }
    bool inMask(int x, int y) const { return inBounds(x, y) && mask_[idx(x, y)]; }
    int arrowCount() const { return static_cast<int>(arrows_.size()); }
    const Arrow& arrow(int id) const { return arrows_[id]; }
    const std::vector<Portal>& portals() const { return portals_; }
    const std::vector<StopPoint>& stops() const { return stops_; }
    const std::vector<Redirect>& redirects() const { return redirects_; }
    const std::vector<Zone>& zones() const { return zones_; }
    int introduces() const { return introduces_; }
    int difficulty() const { return difficulty_; }

    bool tappable(int id) const;
    Trace trace(int id, bool reverse, int ignore = -1) const;

    // Resolves a tap exactly as the game does: magnetic partners move together and
    // nothing changes unless every moving arrow is free (exits or reaches a stop point).
    TapResult tap(int id, bool reverse);

    // Eraser booster: removes an arrow unconditionally. Returns zones it unlocked.
    std::vector<int> erase(int id);

    int remaining() const;

    // First move of a greedy solution from the current state, or {-1, false}.
    std::pair<int, bool> hint() const;
    bool solvable() const;
    int depth() const;  // greedy rounds to clear, -1 if stuck (difficulty measure)
    // Same as depth() for boards without mechanics, but without copying the board.
    int fastDepth(int* freeAtStart = nullptr) const;
    bool wellFormed() const;

private:
    Board() = default;

    int level_ = 1;
    int cols_ = 0;
    int rows_ = 0;
    int introduces_ = -1;
    int difficulty_ = 0;
    std::vector<uint8_t> mask_;
    std::vector<int16_t> occ_;       // arrow id per cell, -1 when empty
    std::vector<int16_t> portalAt_;  // portal index * 2 + end, -1 when none
    std::vector<int16_t> stopAt_;
    std::vector<int16_t> redirectAt_;
    std::vector<int16_t> zoneAt_;
    std::vector<Arrow> arrows_;
    std::vector<Portal> portals_;
    std::vector<StopPoint> stops_;
    std::vector<Redirect> redirects_;
    std::vector<Zone> zones_;

    bool inBounds(int x, int y) const { return x >= 0 && y >= 0 && x < cols_ && y < rows_; }
    int idx(int x, int y) const { return y * cols_ + x; }

    void makeMask(const LevelConfig& cfg);
    void buildBase(const LevelConfig& cfg, uint32_t seed);   // sparse, for the first levels
    void buildDense(const LevelConfig& cfg, uint32_t seed);  // fill-then-peel, near-full boards
    // Makes a board harder without breaking it: arrows that are free at the start are
    // turned to face inwards when the board stays solvable.
    void harden(uint32_t seed);
    void fillHoles(const LevelConfig& cfg, Rng& rng, std::vector<double>& times);
    bool addMechanics(const LevelConfig& cfg, uint32_t seed);
    void rebuildIndex();
    bool freeCell(int x, int y) const;

    void applyMove(int id, bool reverse, const Trace& t, std::vector<int>& unlocked);
    void removeArrow(int id, std::vector<int>& unlocked);
    // One greedy solver step; returns false when stuck. `first` receives the move made.
    bool greedyStep(std::pair<int, bool>* first);
};

} // namespace puzzle
