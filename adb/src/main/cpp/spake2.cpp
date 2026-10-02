#include "adb/spake2.hpp"

#include <cstring>

namespace adb::spake2 {

namespace {

#if defined(__SIZEOF_INT128__)
using u128 = unsigned __int128;
#else
struct u128 {
    uint64_t lo;
    uint64_t hi;
    constexpr u128(uint64_t value = 0) : lo(value), hi(0) {}
    constexpr u128(uint64_t low, uint64_t high) : lo(low), hi(high) {}
    explicit constexpr operator uint64_t() const { return lo; }
};

u128 operator+(u128 a, u128 b) {
    uint64_t lo = a.lo + b.lo;
    return u128(lo, a.hi + b.hi + (lo < a.lo ? 1 : 0));
}

u128 operator-(u128 a, u128 b) {
    return u128(a.lo - b.lo, a.hi - b.hi - (a.lo < b.lo ? 1 : 0));
}

u128 operator*(u128 a, u128 b) {
    uint64_t a0 = a.lo & 0xffffffffu, a1 = a.lo >> 32, b0 = b.lo & 0xffffffffu, b1 = b.lo >> 32;
    uint64_t p00 = a0 * b0, p01 = a0 * b1, p10 = a1 * b0, p11 = a1 * b1;
    uint64_t middle = (p00 >> 32) + (p01 & 0xffffffffu) + (p10 & 0xffffffffu);
    uint64_t lo = (p00 & 0xffffffffu) | (middle << 32);
    uint64_t hi = p11 + (p01 >> 32) + (p10 >> 32) + (middle >> 32);
    return u128(lo, hi + a.lo * b.hi + a.hi * b.lo);
}

u128 operator>>(u128 a, int shift) {
    if (shift == 0) return a;
    if (shift >= 64) return u128(a.hi >> (shift - 64), 0);
    return u128((a.lo >> shift) | (a.hi << (64 - shift)), a.hi >> shift);
}

u128& operator+=(u128& a, u128 b) {
    a = a + b;
    return a;
}
#endif

constexpr uint64_t kMask = (uint64_t(1) << 51) - 1;

struct Fe {
    uint64_t v[5];
};

Fe feZero() { return Fe{{0, 0, 0, 0, 0}}; }
Fe feOne() { return Fe{{1, 0, 0, 0, 0}}; }

uint64_t load64(const uint8_t* p) {
    uint64_t v = 0;
    for (int i = 7; i >= 0; i--) v = (v << 8) | p[i];
    return v;
}

Fe feFromBytes(const uint8_t s[32]) {
    uint64_t w0 = load64(s), w1 = load64(s + 8), w2 = load64(s + 16), w3 = load64(s + 24);
    Fe r;
    r.v[0] = w0 & kMask;
    r.v[1] = ((w0 >> 51) | (w1 << 13)) & kMask;
    r.v[2] = ((w1 >> 38) | (w2 << 26)) & kMask;
    r.v[3] = ((w2 >> 25) | (w3 << 39)) & kMask;
    r.v[4] = (w3 >> 12) & kMask;
    return r;
}

Fe feCarry(Fe a) {
    uint64_t c;
    c = a.v[0] >> 51; a.v[0] &= kMask; a.v[1] += c;
    c = a.v[1] >> 51; a.v[1] &= kMask; a.v[2] += c;
    c = a.v[2] >> 51; a.v[2] &= kMask; a.v[3] += c;
    c = a.v[3] >> 51; a.v[3] &= kMask; a.v[4] += c;
    c = a.v[4] >> 51; a.v[4] &= kMask; a.v[0] += c * 19;
    c = a.v[0] >> 51; a.v[0] &= kMask; a.v[1] += c;
    return a;
}

Fe feAdd(const Fe& a, const Fe& b) {
    Fe r;
    for (int i = 0; i < 5; i++) r.v[i] = a.v[i] + b.v[i];
    return feCarry(r);
}

Fe feSub(const Fe& a, const Fe& b) {
    Fe r;
    r.v[0] = a.v[0] + 0xFFFFFFFFFFFDAULL * 2 - b.v[0];
    for (int i = 1; i < 5; i++) r.v[i] = a.v[i] + 0xFFFFFFFFFFFFEULL * 2 - b.v[i];
    return feCarry(r);
}

Fe feNeg(const Fe& a) { return feSub(feZero(), a); }

Fe feMul(const Fe& a, const Fe& b) {
    uint64_t b1 = b.v[1] * 19, b2 = b.v[2] * 19, b3 = b.v[3] * 19, b4 = b.v[4] * 19;
    u128 r0 = (u128)a.v[0] * b.v[0] + (u128)a.v[1] * b4 + (u128)a.v[2] * b3 + (u128)a.v[3] * b2 + (u128)a.v[4] * b1;
    u128 r1 = (u128)a.v[0] * b.v[1] + (u128)a.v[1] * b.v[0] + (u128)a.v[2] * b4 + (u128)a.v[3] * b3 + (u128)a.v[4] * b2;
    u128 r2 = (u128)a.v[0] * b.v[2] + (u128)a.v[1] * b.v[1] + (u128)a.v[2] * b.v[0] + (u128)a.v[3] * b4 + (u128)a.v[4] * b3;
    u128 r3 = (u128)a.v[0] * b.v[3] + (u128)a.v[1] * b.v[2] + (u128)a.v[2] * b.v[1] + (u128)a.v[3] * b.v[0] + (u128)a.v[4] * b4;
    u128 r4 = (u128)a.v[0] * b.v[4] + (u128)a.v[1] * b.v[3] + (u128)a.v[2] * b.v[2] + (u128)a.v[3] * b.v[1] + (u128)a.v[4] * b.v[0];
    Fe r;
    uint64_t c;
    r.v[0] = uint64_t(r0) & kMask; c = uint64_t(r0 >> 51);
    r1 += c; r.v[1] = uint64_t(r1) & kMask; c = uint64_t(r1 >> 51);
    r2 += c; r.v[2] = uint64_t(r2) & kMask; c = uint64_t(r2 >> 51);
    r3 += c; r.v[3] = uint64_t(r3) & kMask; c = uint64_t(r3 >> 51);
    r4 += c; r.v[4] = uint64_t(r4) & kMask; c = uint64_t(r4 >> 51);
    r.v[0] += c * 19;
    return feCarry(r);
}

Fe feSquare(const Fe& a) { return feMul(a, a); }

void feToBytes(uint8_t s[32], Fe a) {
    a = feCarry(feCarry(a));
    uint64_t q = (a.v[0] + 19) >> 51;
    q = (a.v[1] + q) >> 51;
    q = (a.v[2] + q) >> 51;
    q = (a.v[3] + q) >> 51;
    q = (a.v[4] + q) >> 51;
    a.v[0] += 19 * q;
    uint64_t c;
    c = a.v[0] >> 51; a.v[0] &= kMask; a.v[1] += c;
    c = a.v[1] >> 51; a.v[1] &= kMask; a.v[2] += c;
    c = a.v[2] >> 51; a.v[2] &= kMask; a.v[3] += c;
    c = a.v[3] >> 51; a.v[3] &= kMask; a.v[4] += c;
    a.v[4] &= kMask;
    uint64_t w[4];
    w[0] = a.v[0] | (a.v[1] << 51);
    w[1] = (a.v[1] >> 13) | (a.v[2] << 38);
    w[2] = (a.v[2] >> 26) | (a.v[3] << 25);
    w[3] = (a.v[3] >> 39) | (a.v[4] << 12);
    for (int i = 0; i < 4; i++)
        for (int j = 0; j < 8; j++) s[8 * i + j] = uint8_t(w[i] >> (8 * j));
}

Fe fePow(const Fe& a, const uint8_t exponent[32]) {
    Fe r = feOne();
    for (int bit = 255; bit >= 0; bit--) {
        r = feSquare(r);
        if ((exponent[bit >> 3] >> (bit & 7)) & 1) r = feMul(r, a);
    }
    return r;
}

bool feEqual(const Fe& a, const Fe& b) {
    uint8_t x[32], y[32];
    feToBytes(x, a);
    feToBytes(y, b);
    return std::memcmp(x, y, 32) == 0;
}

bool feIsNegative(const Fe& a) {
    uint8_t s[32];
    feToBytes(s, a);
    return s[0] & 1;
}

void littleEndianConstant(uint8_t out[32], uint64_t subtract, int topShift) {
    std::memset(out, 0xff, 32);
    out[31] = uint8_t(0xff >> topShift);
    uint64_t borrow = subtract;
    for (int i = 0; i < 32 && borrow; i++) {
        uint64_t value = out[i];
        if (value >= (borrow & 0xff)) {
            out[i] = uint8_t(value - (borrow & 0xff));
            borrow >>= 8;
        } else {
            out[i] = uint8_t(value + 256 - (borrow & 0xff));
            borrow = (borrow >> 8) + 1;
        }
    }
}

Fe feInvert(const Fe& a) {
    uint8_t e[32];
    littleEndianConstant(e, 20, 1);
    return fePow(a, e);
}

struct Constants {
    Fe d;
    Fe d2;
    Fe sqrtMinusOne;
};

const Constants& constants() {
    static const Constants c = [] {
        Constants k;
        Fe n = feNeg(Fe{{121665, 0, 0, 0, 0}});
        k.d = feMul(n, feInvert(Fe{{121666, 0, 0, 0, 0}}));
        k.d2 = feAdd(k.d, k.d);
        uint8_t pMinusOneQuarter[32];
        littleEndianConstant(pMinusOneQuarter, 19, 1);
        uint64_t carry = 0;
        for (int i = 31; i >= 0; i--) {
            uint64_t value = (carry << 8) | pMinusOneQuarter[i];
            pMinusOneQuarter[i] = uint8_t(value >> 2);
            carry = value & 3;
        }
        k.sqrtMinusOne = fePow(Fe{{2, 0, 0, 0, 0}}, pMinusOneQuarter);
        return k;
    }();
    return c;
}

struct Point {
    Fe x, y, z, t;
};

Point identity() { return Point{feZero(), feOne(), feOne(), feZero()}; }

Point add(const Point& p, const Point& q) {
    const Constants& k = constants();
    Fe a = feMul(feSub(p.y, p.x), feSub(q.y, q.x));
    Fe b = feMul(feAdd(p.y, p.x), feAdd(q.y, q.x));
    Fe c = feMul(feMul(p.t, k.d2), q.t);
    Fe d = feMul(feAdd(p.z, p.z), q.z);
    Fe e = feSub(b, a);
    Fe f = feSub(d, c);
    Fe g = feAdd(d, c);
    Fe h = feAdd(b, a);
    return Point{feMul(e, f), feMul(g, h), feMul(f, g), feMul(e, h)};
}

Point negate(const Point& p) { return Point{feNeg(p.x), p.y, p.z, feNeg(p.t)}; }

Point multiply(const Point& p, const uint8_t scalar[32]) {
    Point r = identity();
    for (int bit = 255; bit >= 0; bit--) {
        r = add(r, r);
        if ((scalar[bit >> 3] >> (bit & 7)) & 1) r = add(r, p);
    }
    return r;
}

void encode(uint8_t out[32], const Point& p) {
    Fe inverse = feInvert(p.z);
    Fe x = feMul(p.x, inverse);
    Fe y = feMul(p.y, inverse);
    feToBytes(out, y);
    out[31] |= uint8_t(feIsNegative(x) << 7);
}

bool decode(Point& out, const uint8_t s[32]) {
    const Constants& k = constants();
    Fe y = feFromBytes(s);
    Fe yy = feSquare(y);
    Fe u = feSub(yy, feOne());
    Fe v = feAdd(feMul(k.d, yy), feOne());
    Fe xx = feMul(u, feInvert(v));
    uint8_t e[32];
    littleEndianConstant(e, 1, 4);
    Fe x = fePow(xx, e);
    if (!feEqual(feSquare(x), xx)) x = feMul(x, k.sqrtMinusOne);
    if (!feEqual(feSquare(x), xx)) return false;
    if (feIsNegative(x) != bool(s[31] >> 7)) x = feNeg(x);
    out = Point{x, y, feOne(), feMul(x, y)};
    return true;
}

const uint8_t kBase[32] = {0x58, 0x66, 0x66, 0x66, 0x66, 0x66, 0x66, 0x66, 0x66, 0x66, 0x66,
                           0x66, 0x66, 0x66, 0x66, 0x66, 0x66, 0x66, 0x66, 0x66, 0x66, 0x66,
                           0x66, 0x66, 0x66, 0x66, 0x66, 0x66, 0x66, 0x66, 0x66, 0x66};
const uint8_t kPointM[32] = {0x5a, 0xda, 0x7e, 0x4b, 0xf6, 0xdd, 0xd9, 0xad, 0xb6, 0x62, 0x6d,
                             0x32, 0x13, 0x1c, 0x6b, 0x5c, 0x51, 0xa1, 0xe3, 0x47, 0xa3, 0x47,
                             0x8f, 0x53, 0xcf, 0xcf, 0x44, 0x1b, 0x88, 0xee, 0xd1, 0x2e};
const uint8_t kPointN[32] = {0x10, 0xe3, 0xdf, 0x0a, 0xe3, 0x7d, 0x8e, 0x7a, 0x99, 0xb5, 0xfe,
                             0x74, 0xb4, 0x46, 0x72, 0x10, 0x3d, 0xbd, 0xdc, 0xbd, 0x06, 0xaf,
                             0x68, 0x0d, 0x71, 0x32, 0x9a, 0x11, 0x69, 0x3b, 0xc7, 0x78};
const uint8_t kOrder[32] = {0xed, 0xd3, 0xf5, 0x5c, 0x1a, 0x63, 0x12, 0x58, 0xd6, 0x9c, 0xf7,
                            0xa2, 0xde, 0xf9, 0xde, 0x14, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
                            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x10};

struct Wide {
    uint64_t w[4];
};

Wide wideFrom(const uint8_t b[32]) {
    Wide r;
    for (int i = 0; i < 4; i++) r.w[i] = load64(b + 8 * i);
    return r;
}

void wideTo(uint8_t b[32], const Wide& a) {
    for (int i = 0; i < 4; i++)
        for (int j = 0; j < 8; j++) b[8 * i + j] = uint8_t(a.w[i] >> (8 * j));
}

bool wideGreaterEqual(const Wide& a, const Wide& b) {
    for (int i = 3; i >= 0; i--) {
        if (a.w[i] != b.w[i]) return a.w[i] > b.w[i];
    }
    return true;
}

Wide wideAdd(const Wide& a, const Wide& b) {
    Wide r;
    u128 carry = 0;
    for (int i = 0; i < 4; i++) {
        u128 s = (u128)a.w[i] + b.w[i] + carry;
        r.w[i] = uint64_t(s);
        carry = s >> 64;
    }
    return r;
}

Wide wideSub(const Wide& a, const Wide& b) {
    Wide r;
    uint64_t borrow = 0;
    for (int i = 0; i < 4; i++) {
        u128 d = (u128)a.w[i] - b.w[i] - borrow;
        r.w[i] = uint64_t(d);
        borrow = uint64_t(d >> 64) ? 1 : 0;
    }
    return r;
}

void reduce512(uint8_t out[32], const uint8_t in[64]) {
    Wide order = wideFrom(kOrder);
    Wide r{{0, 0, 0, 0}};
    for (int bit = 511; bit >= 0; bit--) {
        r = wideAdd(r, r);
        if ((in[bit >> 3] >> (bit & 7)) & 1) r.w[0] |= 1;
        if (wideGreaterEqual(r, order)) r = wideSub(r, order);
    }
    wideTo(out, r);
}

void appendWithLength(Sha512& hash, const uint8_t* data, size_t length) {
    uint8_t prefix[8];
    uint64_t l = length;
    for (int i = 0; i < 8; i++) {
        prefix[i] = uint8_t(l);
        l >>= 8;
    }
    hash.update(prefix, 8);
    hash.update(data, length);
}

}

Context create(Role role, const uint8_t* myName, size_t myNameLength, const uint8_t* theirName, size_t theirNameLength) {
    Context c;
    c.role = role;
    c.myName.assign(myName, myName + myNameLength);
    c.theirName.assign(theirName, theirName + theirNameLength);
    return c;
}

bool generate(Context& c, const uint8_t* password, size_t passwordLength, const uint8_t random[64]) {
    uint8_t reduced[32];
    reduce512(reduced, random);
    Wide privateKey = wideFrom(reduced);
    for (int i = 0; i < 3; i++) privateKey = wideAdd(privateKey, privateKey);
    wideTo(c.privateKey, privateKey);

    Point base;
    if (!decode(base, kBase)) return false;
    Point p = multiply(base, c.privateKey);

    sha512(password, passwordLength, c.passwordHash);
    uint8_t scalarBytes[32];
    reduce512(scalarBytes, c.passwordHash);
    Wide scalar = wideFrom(scalarBytes);
    Wide order = wideFrom(kOrder);
    for (uint64_t bit = 1; bit <= 4; bit <<= 1) {
        if (scalar.w[0] & bit) scalar = wideAdd(scalar, order);
        order = wideAdd(order, order);
    }
    wideTo(c.passwordScalar, scalar);

    Point mask;
    if (!decode(mask, c.role == Role::Alice ? kPointM : kPointN)) return false;
    mask = multiply(mask, c.passwordScalar);
    encode(c.message, add(p, mask));
    return true;
}

bool process(const Context& c, const uint8_t theirMessage[32], uint8_t key[64]) {
    Point theirs;
    if (!decode(theirs, theirMessage)) return false;
    Point mask;
    if (!decode(mask, c.role == Role::Alice ? kPointN : kPointM)) return false;
    mask = multiply(mask, c.passwordScalar);
    Point unmasked = add(theirs, negate(mask));
    uint8_t shared[32];
    encode(shared, multiply(unmasked, c.privateKey));

    Sha512 hash;
    if (c.role == Role::Alice) {
        appendWithLength(hash, c.myName.data(), c.myName.size());
        appendWithLength(hash, c.theirName.data(), c.theirName.size());
        appendWithLength(hash, c.message, 32);
        appendWithLength(hash, theirMessage, 32);
    } else {
        appendWithLength(hash, c.theirName.data(), c.theirName.size());
        appendWithLength(hash, c.myName.data(), c.myName.size());
        appendWithLength(hash, theirMessage, 32);
        appendWithLength(hash, c.message, 32);
    }
    appendWithLength(hash, shared, 32);
    appendWithLength(hash, c.passwordHash, 64);
    hash.finish(key);
    return true;
}

}
